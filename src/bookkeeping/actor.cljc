(ns bookkeeping.actor
  "BookkeepingActor — the ISCO-08 4311 community bookkeeping actor as a
  `langgraph.graph/state-graph` (ADR-2607011000 / CLAUDE.md Actors
  section). One graph run = one bookkeeping operation request
  (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite
  internal loop; checkpointed per superstep so an interrupted run can
  resume after human sign-off. Modeled on cloud-itonami-isco-2411's
  accounting.actor.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                             +-> :request-approval   (:escalate? true, interrupt-before)
                                             +-> :hold               (:hard? true)
  ```

  The unconditional invariant: the BookkeepingAdvisor can never
  directly commit a record the BookkeepingClerksGovernor refuses —
  every commit-record! call is gated behind `:decide`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [bookkeeping.advisor :as advisor]
            [bookkeeping.governor :as governor]
            [bookkeeping.store :as store]
            [bookkeeping.posting :as posting]))

(defn build-graph
  "Build a compiled BookkeepingActor graph. `store` implements
  `bookkeeping.store/Store`. `advisor` implements
  `bookkeeping.advisor/Advisor` (defaults to `mock-advisor`).
  `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [verdict]}]
                     {:disposition (cond
                                     (:hard? verdict) :hold
                                     (:escalate? verdict) :request-approval
                                     :else :commit)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                   (fn [{:keys [request proposal]}]
                     (let [record {:client-id (:client-id request)
                                    :op (:op proposal)
                                    :source-doc (:source-doc proposal)
                                    :payload proposal}
                           ;; An approved journal entry LANDS here. Until
                           ;; this call existed, `bookkeeping.posting` was
                           ;; reachable only from its own tests: the
                           ;; projection was written, verified, and never
                           ;; invoked by the actor — the same "checkable but
                           ;; nobody calls it" shape this fleet keeps
                           ;; finding elsewhere.
                           ;;
                           ;; Only :draft-entry projects. :reconcile,
                           ;; :issue-invoice and :close-period are not
                           ;; journal entries and must not manufacture a
                           ;; posting to look complete.
                           post (when (= :draft-entry (:op proposal))
                                  (posting/project
                                   ;; Content-addressed, with no escape
                                   ;; hatch. Keying on the source document
                                   ;; made a retry double-post and made two
                                   ;; different entries citing one receipt
                                   ;; collide -- see
                                   ;; bookkeeping.posting/content-id.
                                   ;;
                                   ;; The previous form was
                                   ;; `(or (:entry-id proposal) …)`, which
                                   ;; was DEAD: `bookkeeping.advisor/infer`
                                   ;; builds a fixed map with no :entry-id,
                                   ;; so nothing reaching this node ever
                                   ;; carried one. Removed rather than wired
                                   ;; up, because a caller-chosen id can name
                                   ;; two different entries the same and
                                   ;; would defeat the idempotency this
                                   ;; whole change exists for. Dead code that
                                   ;; looks like a feature is worse than no
                                   ;; feature.
                                   ;; 取引年月日 and 取引先 are part of the
                                   ;; content, not decoration on it: two
                                   ;; entries citing one monthly statement on
                                   ;; different days to different suppliers
                                   ;; are different entries, and leaving them
                                   ;; out of the id would silently drop the
                                   ;; second as a duplicate.
                                   (posting/content-id
                                    (:source-doc proposal)
                                    (:lines proposal)
                                    :transaction-date (:transaction-date proposal)
                                    :counterparty (:counterparty proposal))
                                   (:lines proposal)
                                   :memo (:memo proposal)
                                   :transaction-date (:transaction-date proposal)
                                   :counterparty (:counterparty proposal)))]
                       (store/commit-record! store record)
                       (when post
                         (store/commit-posting! store (:client-id request) post))
                       (store/append-ledger!
                        store (cond-> {:disposition :commit :record record}
                                ;; The ledger says whether a posting was
                                ;; produced, so "this entry produced none"
                                ;; is auditable rather than invisible.
                                true (assoc :posting (:ledger/posting post))))
                       {:record record
                        :posting post
                        :audit [{:node :commit :record record :posting post}]})))
      (g/add-node :hold
                   (fn [{:keys [verdict]}]
                     (store/append-ledger! store {:disposition :hold :verdict verdict})
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation request to completion or interrupt. `thread-id`
  scopes checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of
  resuming the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
