(ns bookkeeping.edge.endpoints
  "The HTTP surface this actor exposes — exactly two routes:

      POST /api/entry           submit a journal entry draft
      GET  /api/trial-balance   read what the committed postings add up to
      GET  /api/statements      read 貸借対照表 / 損益計算書

  and nothing else. Per `manifest/repository-rules.edn` an itonami actor is
  `:on-demand`: it answers a request and stops.

  ## Why these two

  Of the four ops, only `:draft-entry` both auto-commits and genuinely needs
  a network path — a client's system pushes entries as they happen. The
  other two that write end in a human's judgement and are therefore not
  reachable from a socket at all:

    :draft-entry     auto-commits when clean                  → exposed
    :reconcile       auto-commits, but nothing pushes it      → not exposed
    :issue-invoice   ALWAYS escalates (external send)         → not exposed
    :close-period    ALWAYS escalates (hard to reverse)       → not exposed

  The read is the other half, and it is the reason this surface is worth
  having at all: until `bookkeeping.trial-balance` there was nothing to ask
  the actor. An actor that only accepts is a write-only hole.

  ## The caller's client is derived, never accepted

  `client-for` maps a verified DID to a client id. **The request body cannot
  name a client.** This is the same rule the governor applies to
  jurisdiction — a caller that could nominate its own client could nominate
  one whose ledger it may read, and the store-level guarantee
  (`postings-of` is scoped per client) would be undone one layer up by the
  surface that is supposed to enforce it.

  ## Two gates, and neither is optional

  1. CACAO signature + temporal window (`cacao.edge.verify`, the shared
     library — not reimplemented here; ADR-2607268000).
  2. The verified caller DID must be on the allow-list.

  **An absent allow-list serves 503, never an open endpoint.** An open entry
  endpoint is an open write path into someone's books; an open read endpoint
  hands out their ledger."
  (:require [bookkeeping.actor :as actor]
            [bookkeeping.store :as store]
            [bookkeeping.trial-balance :as tb]
            [bookkeeping.statements :as statements]
            [kotoba.shohyo :as shohyo]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:cljs [cacao.edge.verify :as cacao])))

(defn parse-allowlist
  "`\"did:key:z6Mk…=c-1,did:key:z6Ml…=c-2\"` -> `{did client-id}`, or nil
  when absent, blank or wholly malformed — 'nobody is allowed' and 'nothing
  was configured' are different deployment states and get different status
  codes."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did client] (map #(.trim %) (.split entry "="))]
                          (when (and did client (seq did) (seq client))
                            [did client])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn client-for [allowlist did] (get allowlist did))

(def ^:private sides #{:dr :cr})

(defn parse-entry-body
  "EDN body -> `{:source-doc str :lines [...]}`.

  Every line must carry a recognised `:side`, a string `:account` and a
  numeric `:amount`. A client system sending something else is a system to
  fix, not a stream to guess at — and a line whose side is unrecognised
  would drop out of the projection, which balances by having vanished.

  Read with `clojure.edn/read-string`, which evaluates nothing.

  **A body naming a client is rejected**, rather than ignored. Silently
  dropping it would let a caller believe it had written somewhere it had
  not."
  [s]
  (try
    (let [m (edn/read-string s)]
      (when (and (map? m)
                 (not (contains? m :client-id))
                 (string? (:source-doc m))
                 (vector? (:lines m)) (seq (:lines m))
                 (every? #(and (sides (:side %))
                               (string? (:account %))
                               (number? (:amount %)))
                         (:lines m)))
        m))
    (catch #?(:clj Exception :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment stores what it accepts, from `BOOKKEEPING_STORE`.

    nil          nothing configured
    :ephemeral   an in-process store that does not survive the request

  nil for anything else, including a typo — a misspelled deployment
  variable must not silently select a storage mode."
  [env]
  (case (some-> (get env "BOOKKEEPING_STORE") .trim)
    "ephemeral" :ephemeral
    nil))

(defn store-unconfigured-response
  "503, and deliberately NOT an empty in-process store.

  An empty store makes every request fail the governor's registration
  check, so the caller is told `:no-client` — blamed for a deployment that
  has no store at all. Misattributed blame is worse than a refusal: the
  operator goes looking at their own registration while the fault is here."
  []
  {:status 503
   :body {:ok false :error "no store configured"
          :hint (str "bind a durable store, or set BOOKKEEPING_STORE=ephemeral"
                     " for a non-persisting smoke test")}})

;; ---------------------------------------------------------------------------
;; POST /api/entry
;; ---------------------------------------------------------------------------

(defn draft-entry-core!
  "`POST /api/entry`. `caller-did` is already verified.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body, a malformed line, or a body naming a client
    409  the governor held it, with the violations
    202  accepted but awaiting human approval
    200  committed; the body reports whether a posting was produced and
         what the client's trial balance now says, so a caller learns at
         the moment it matters rather than at period close"
  [store mode allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (if-let [body (parse-entry-body raw-body)]
      (let [client-id (client-for allowlist caller-did)
            g (actor/build-graph {:store store})
            r (actor/run-request! g {:client-id client-id :op :draft-entry
                                     :stake :low
                                     :source-doc (:source-doc body)
                                     :lines (:lines body)}
                                  {} (str "edge-" caller-did "-" (:source-doc body)))
            disposition (get-in r [:state :disposition])
            verdict (get-in r [:state :verdict])]
        (cond
          (= :commit disposition)
          (let [posting (get-in r [:state :posting])
                ps (store/postings-of store client-id)]
            {:status 200
             :body {:ok true :ephemeral (= :ephemeral mode) :client client-id
                    ;; nil when the entry produced none. Reported rather
                    ;; than omitted: an entry that committed without
                    ;; posting is exactly what a caller must be able to see.
                    :posting (:ledger/posting posting)
                    :posting-count (count ps)
                    :balanced? (tb/balanced? ps)}})

          (:escalate? verdict)
          {:status 202
           :body {:ok false :disposition disposition
                  :reason (:escalation-reason verdict)}}

          :else
          {:status 409
           :body {:ok false :disposition disposition
                  :violations (mapv #(select-keys % [:rule :detail])
                                    (:violations verdict))}}))
      {:status 400 :body {:ok false :error "invalid request body"}})))

;; ---------------------------------------------------------------------------
;; GET /api/trial-balance
;; ---------------------------------------------------------------------------

(defn trial-balance-core
  "`GET /api/trial-balance`. `caller-did` is already verified. Read-only.

    503  no allow-list configured
    403  caller not on the allow-list
    200  the caller's OWN trial balance

  `:posting-count` is always present. Without it a caller cannot tell an
  empty ledger from a balanced one, and `:balanced?` is false for both —
  correctly, since an empty ledger has not been shown to balance, only to
  be empty. Reporting the count is what makes those two distinguishable to
  someone reading JSON."
  [store allowlist caller-did]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (let [client-id (client-for allowlist caller-did)
          ps (store/postings-of store client-id)
          r (tb/report ps)]
      {:status 200
       :body {:ok true :client client-id
              :posting-count (get-in r [:trial-balance/totals :posting-count])
              :balanced? (:trial-balance/balanced? r)
              :out-of-balance (:trial-balance/out-of-balance r)
              :balances (into {} (map (fn [[[account currency] v]]
                                        [(str account "/" currency) v]))
                              (:trial-balance/balances r))}})))

;; ---------------------------------------------------------------------------
;; GET /api/statements
;; ---------------------------------------------------------------------------

(defn- shohyo-out-of-balance [r]
  (shohyo/out-of-balance (:statements/shohyo r)))

(defn- ladder-body [l]
  (if (= :not-declared (:shohyo.jp/coverage l))
    {:coverage "not-declared" :missing (mapv name (:shohyo.jp/missing-sections l))}
    (into {:coverage "checked"}
          (map (fn [k] [(name k) (select-keys (get l k) [:amount :label :article])]))
          [:shohyo.jp/gross :shohyo.jp/operating
           :shohyo.jp/ordinary :shohyo.jp/pretax])))

(defn- statement-bodies [r]
  (into {}
        (map (fn [[currency v]]
               [currency
                {:bs (mapv #(select-keys % [:account :type :section :presented]) (:bs v))
                 :pl (mapv #(select-keys % [:account :type :section :presented]) (:pl v))
                 :totals (:totals v)
                 :equation (:equation v)
                 :jp (ladder-body (get-in r [:statements/jp currency]))}]))
        (get-in r [:statements/shohyo :shohyo/by-currency])))

(defn statements-core
  "`GET /api/statements`. `caller-did` is already verified. Read-only.

    503  no allow-list configured
    403  caller not on the allow-list
    409  no chart registered, or a chart the regulation does not accept —
         a 4xx and not an empty 200, because 「we cannot classify your
         accounts」 is a refusal the caller has to act on, and an empty
         statement is exactly what it would otherwise look like
    200  the caller's OWN statements

  `:complete?` is reported separately from the 200. The request succeeded;
  whether the statement is whole is a different question, and collapsing
  them would let a caller read 200 as `these books are finished`."
  [store allowlist caller-did]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (let [client-id (client-for allowlist caller-did)
          r (statements/for-client store client-id)]
      (case (:statements/coverage r)
        :no-chart
        {:status 409 :body {:ok false :error "no chart of accounts registered"
                            :detail (:statements/why r)}}

        :chart-invalid
        {:status 409 :body {:ok false :error "chart of accounts is not usable"
                            :problems (mapv #(select-keys % [:account :problem :detail])
                                            (:statements/chart-problems r))}}

        {:status 200
         :body {:ok true :client client-id
                :complete? (:statements/complete? r)
                ;; Named, always. An unclassified account is the one thing a
                ;; reader of a balance sheet cannot see for themselves.
                :unclassified (get-in r [:statements/shohyo :shohyo/unclassified])
                :out-of-balance (shohyo-out-of-balance r)
                :by-currency (statement-bodies r)}}))))

;; ---------------------------------------------------------------------------
;; Cloudflare entry points
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- json-response [{:keys [status body]}]
     (js/Response. (js/JSON.stringify (clj->js body))
                   #js {:status status
                        :headers #js {"content-type" "application/json"}})))

#?(:cljs
   (defn- env-of [context]
     (let [env (aget context "env")]
       {"BOOKKEEPING_STORE" (aget env "BOOKKEEPING_STORE")})))

#?(:cljs
   (defn- bearer [context]
     (let [header (or (.get (aget (aget context "request") "headers") "authorization") "")]
       (if (.startsWith header "Bearer ") (subs header 7) header))))

#?(:cljs
   (defn on-request-post-entry
     "Verifies the CACAO and hands an already-verified caller to
     `draft-entry-core!`. No policy of its own."
     [context]
     (let [env (aget context "env")
           mode (store-mode (env-of context))
           allowlist (parse-allowlist (aget env "BOOKKEEPING_CALLER_ALLOWLIST"))]
       (-> (js/Promise.all #js [(cacao/verify (bearer context))
                                (.text (aget context "request"))])
           (.then (fn [results]
                    (let [v (aget results 0) raw (aget results 1)]
                      (cond
                        (nil? mode) (json-response (store-unconfigured-response))
                        (not (:valid v))
                        (json-response {:status 401
                                        :body {:ok false :error "invalid or expired CACAO"}})
                        :else
                        (json-response (draft-entry-core! (store/mem-store) mode
                                                          allowlist (:iss v) raw))))))
           (.catch (fn [e]
                     (json-response {:status 500
                                     :body {:ok false :error "request failed"
                                            :reason (ex-message e)}})))))))

#?(:cljs
   (defn on-request-get-trial-balance
     [context]
     (let [env (aget context "env")
           mode (store-mode (env-of context))
           allowlist (parse-allowlist (aget env "BOOKKEEPING_CALLER_ALLOWLIST"))]
       (-> (cacao/verify (bearer context))
           (.then (fn [v]
                    (cond
                      (nil? mode) (json-response (store-unconfigured-response))
                      (not (:valid v))
                      (json-response {:status 401
                                      :body {:ok false :error "invalid or expired CACAO"}})
                      :else
                      (json-response (trial-balance-core (store/mem-store)
                                                         allowlist (:iss v))))))
           (.catch (fn [e]
                     (json-response {:status 500
                                     :body {:ok false :error "request failed"
                                            :reason (ex-message e)}})))))))
