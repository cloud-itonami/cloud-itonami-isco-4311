(ns bookkeeping.edge.endpoints
  "The HTTP surface this actor exposes — exactly these routes:

      POST /api/entry           submit a journal entry draft
      POST /api/entries         submit many, and get one outcome each
      GET  /api/trial-balance   read what the committed postings add up to
      GET  /api/journal         仕訳帳 — every posting, in commit order
      GET  /api/ledger/:account 総勘定元帳 — one account, with a running balance
      GET  /api/search          検索機能 — 規則第五条第五項第一号ハ
      GET  /api/statements      read 貸借対照表 / 損益計算書

  and nothing else. Per `manifest/repository-rules.edn` an itonami actor is
  `:on-demand`: it answers a request and stops.

  ## Why these writes

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
            [bookkeeping.motochou :as motochou]
            [bookkeeping.kensaku :as kensaku]
            [bookkeeping.posting :as posting]
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
  "EDN body -> `{:source-doc str :lines [...]}`, plus the two optional
  記録項目 `:transaction-date` and `:counterparty`.

  Every line must carry a recognised `:side`, a string `:account` and a
  numeric `:amount`. A client system sending something else is a system to
  fix, not a stream to guess at — and a line whose side is unrecognised
  would drop out of the projection, which balances by having vanished.

  Read with `clojure.edn/read-string`, which evaluates nothing.

  **A body naming a client is rejected**, rather than ignored. Silently
  dropping it would let a caller believe it had written somewhere it had
  not.

  ## 取引年月日 and 取引先 are OPTIONAL, and this was a decision

  規則第五条第五項第一号ハ requires all three 記録項目, so making them
  required is the tempting read. It is wrong here, twice:

  1. **ハ only binds a 保存義務者 claiming 法第八条第四項（優良帳簿）.**
     Ordinary preservation under 法第四条第一項 requires no search function
     at all. Requiring the fields would impose a 優良帳簿 obligation on every
     deployment of this actor, including the ones not claiming it — the
     software deciding a tax election on the client's behalf, which is the
     exact thing `bookkeeping.kensaku/conformance` refuses to do.
  2. **Some journal entries genuinely have no 取引先.** 減価償却費,
     決算整理仕訳, a transfer between the client's own accounts. A required
     field whose honest answer is `there is not one` gets filled with an
     invention, and an invented counterparty is what this actor's governor
     spends its `:no-source-doc` rule preventing from the other direction.

  The price is that an entry submitted without them can never be searched by
  them, so `conformance` COUNTS those entries and names them, and reports
  非適合 rather than 適合 when the operator has declared they are claiming
  優良帳簿. Optional at the door, measured at the claim.

  Measured 2026-08-18: every existing caller in this repo's suites — 8 test
  namespaces, `bookkeeping.render-html`, and the batch route — constructs
  entry bodies with no `:transaction-date` and no `:counterparty`. Required
  would have broken all of them, and the fix would have been to invent dates.

  ## What is NOT optional

  A `:transaction-date` that is present must be an ISO-8601 `YYYY-MM-DD`
  calendar date (`bookkeeping.posting/valid-transaction-date?`) — a
  `2026-02-30` sits in a range query forever answering nothing — and a
  `:counterparty` that is present must be a non-blank string. A blank one is
  refused here with a 400 rather than normalised away, so the caller learns
  its field was empty instead of believing it recorded a 取引先."
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
                         (:lines m))
                 (or (nil? (:transaction-date m))
                     (posting/valid-transaction-date? (:transaction-date m)))
                 (or (nil? (:counterparty m))
                     (some? (posting/normalize-counterparty (:counterparty m)))))
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
            before (into #{} (map :ledger/posting) (store/postings-of store client-id))
            r (actor/run-request! g {:client-id client-id :op :draft-entry
                                     :stake :low
                                     :source-doc (:source-doc body)
                                     ;; the other two 記録項目, nil when the
                                     ;; caller sent none — see parse-entry-body
                                     ;; for why that is permitted and what it
                                     ;; costs the deployment
                                     :transaction-date (:transaction-date body)
                                     :counterparty (:counterparty body)
                                     :lines (:lines body)}
                                  {} (str "edge-" caller-did "-" (:source-doc body)))
            posting-outcome {:duplicate? (contains? before
                                                    (get-in r [:state :posting :ledger/posting]))}
            disposition (get-in r [:state :disposition])
            verdict (get-in r [:state :verdict])]
        (cond
          (= :commit disposition)
          (let [posting (get-in r [:state :posting])
                ps (store/postings-of store client-id)]
            {:status 200
             :body {:ok true :ephemeral (= :ephemeral mode) :client client-id
                    ;; Whether this call ADDED the posting or found it already
                    ;; there. `commit-posting!` is idempotent on the posting
                    ;; id, so a retry is safe -- but until this key existed a
                    ;; retry and a first post returned byte-identical 200s and
                    ;; a carrier could not tell whether it had just written or
                    ;; merely re-sent. Idempotent and indistinguishable is only
                    ;; half of what a carrier needs.
                    :duplicate? (boolean (:duplicate? posting-outcome))
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

(def max-batch
  "A cap, because an uncapped batch is a way to hold the actor for an
  unbounded time on one request. 200 is a number, not a measurement -- it is
  chosen to be obviously enough for a day's entries and obviously not
  unbounded, and it is named so a caller learns the limit from the refusal
  rather than from a timeout."
  200)

(defn entries-core!
  "`POST /api/entries`. `caller-did` is already verified. The carrier's route.

    503  no allow-list configured
    403  caller not on the allow-list
    400  the body is not a non-empty vector of entries, or is over `max-batch`
    207  one outcome per entry, in the order submitted

  ## Always 207, never 200

  A batch of fifty with three refusals is not a success and is not a
  failure, and collapsing it to either loses the three or discards the
  forty-seven. So the status is the same whatever the outcomes are, and the
  ANSWER IS THE PER-ENTRY LIST -- a caller that reads only the status learns
  nothing, which is the correct amount to learn from a status here.

  `:summary` counts the outcomes. It is a convenience over `:results` and
  never a substitute: `:posted`, `:duplicate`, `:held` and `:rejected` are
  reported separately, so no single number can be read as \"it worked\".

  ## Not atomic, and that is the design

  Entries are applied one at a time and an earlier refusal does not stop a
  later entry. Making it all-or-nothing would mean one malformed line in a
  carrier's batch discarded a day of good entries, and the actor has no
  transaction to roll back into anyway -- `commit-posting!` has already
  appended by the time the next entry is read. Stated rather than left for
  someone to discover.

  Retrying the whole batch is safe: posting ids are content-addressed and
  `commit-posting!` is idempotent, so a re-sent entry comes back
  `:duplicate` rather than posting twice."
  [store mode allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (let [parsed (try (edn/read-string raw-body)
                      (catch #?(:clj Exception :cljs :default) _ nil))]
      (cond
        (not (and (vector? parsed) (seq parsed)))
        {:status 400 :body {:ok false :error "body must be a non-empty vector of entries"}}

        (> (count parsed) max-batch)
        {:status 400 :body {:ok false :error "batch too large"
                            :max max-batch :submitted (count parsed)}}

        :else
        (let [results
              (mapv (fn [entry]
                      (let [one (draft-entry-core! store mode allowlist caller-did
                                                   (pr-str entry))]
                        {:status (:status one)
                         :outcome (cond
                                    (not= 200 (:status one))
                                    (case (long (:status one))
                                      409 :held
                                      202 :awaiting-approval
                                      :rejected)
                                    (get-in one [:body :duplicate?]) :duplicate
                                    :else :posted)
                         :source-doc (:source-doc entry)
                         :posting (get-in one [:body :posting])
                         :violations (get-in one [:body :violations])
                         :error (get-in one [:body :error])}))
                    parsed)]
          {:status 207
           :body {:client (client-for allowlist caller-did)
                  :submitted (count parsed)
                  :summary (frequencies (map :outcome results))
                  :results results}})))))

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

(defn journal-core
  "`GET /api/journal`. Read-only, the caller's own book.

    503 / 403  as everywhere else
    200        the 仕訳帳, in commit order"
  [store allowlist caller-did]
  (cond
    (nil? allowlist) {:status 503 :body {:ok false :error "no allow-list configured"}}
    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}
    :else
    (let [client-id (client-for allowlist caller-did)
          j (motochou/journal (store/postings-of store client-id))]
      {:status 200
       :body {:ok true :client client-id
              :entry-count (:motochou/entry-count j)
              :entries (:motochou/entries j)}})))

(defn ledger-core
  "`GET /api/ledger/:account`. Read-only, the caller's own book.

    503 / 403  as everywhere else
    404        the chart does not name that account — **not** an empty 200,
               because a blank page cannot be told from a typo
    200        the 総勘定元帳 for it, per currency, with a running balance

  `:accounts` is returned on the 404 rather than only an error string: an
  account that received a posting while absent from the chart is real
  activity, and a caller looking for it needs to see that it exists before
  it can be classified."
  [store allowlist caller-did account-name]
  (cond
    (nil? allowlist) {:status 503 :body {:ok false :error "no allow-list configured"}}
    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}
    :else
    (let [client-id (client-for allowlist caller-did)
          postings (store/postings-of store client-id)
          chart (or (store/chart-of store client-id) {})
          r (motochou/account chart postings account-name)]
      (if (= :unknown-account (:motochou/coverage r))
        {:status 404
         :body {:ok false :error "no such account in the chart of accounts"
                :account account-name
                :accounts-with-activity (mapv (fn [[a c]] {:account a :currency c})
                                              (motochou/accounts-with-activity postings))}}
        {:status 200
         :body {:ok true :client client-id :account account-name
                :by-currency (:motochou/by-currency r)}}))))

(defn search-core
  "`GET /api/search`. Read-only, the caller's own book. 検索機能 per
  電子帳簿保存法施行規則 第五条第五項第一号ハ.

    503 / 403  as everywhere else
    400        no 記録項目 was set, or a condition the provision does not
               define — **not** a 200 over the whole book
    200        what matched, and what this deployment can claim

  `query` is a `{param value}` map of URL query parameters, taken as an
  argument the way `ledger-core` takes its account name — the core stays pure
  and the Cloudflare handler is the only thing that knows about URLs.

      ?date=2026-01-15                     取引年月日, exact
      ?date-from=…&date-to=…               ハ（２）, inclusive, either side alone
      ?amount=5000  ?amount-from=…&amount-to=…
      ?counterparty=…                      取引先, exact
      any two or more of the above         ハ（３）, AND

  ## Why a conditionless search is a 400

  It could have returned the whole book with a marker. It does not, for two
  reasons: this actor ALREADY has a route that hands back the whole book and
  says so (`GET /api/journal`), and a caller counting rows would otherwise
  read `I applied no filter` and `nothing matched` off the same response.
  `bookkeeping.kensaku/search` keeps them apart at the value level too — the
  `:no-conditions` answer carries no `:kensaku/results` key at all, so there
  is no empty list to misread.

  ## Why the conformance verdict rides on this response

  Because this is the route whose existence the provision is about. A
  deployment that can search and a deployment that may CLAIM 優良帳簿 are
  different facts, and returning results without the second would let a
  working search stand in for a compliance answer it does not give — ハ binds
  only a 保存義務者 claiming 法第八条第四項, which the software cannot
  observe. `:not-declared` is the honest default and it is not a pass."
  [store allowlist caller-did query]
  (cond
    (nil? allowlist) {:status 503 :body {:ok false :error "no allow-list configured"}}
    (nil? (client-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}
    :else
    (let [client-id (client-for allowlist caller-did)
          postings (store/postings-of store client-id)
          parsed (kensaku/parse-query query)]
      (if-let [problems (:kensaku/problems parsed)]
        {:status 400 :body {:ok false :error "invalid search condition"
                            :provision kensaku/provision
                            :problems problems}}
        (let [r (kensaku/search postings (:kensaku/conditions parsed))]
          (case (:kensaku/coverage r)
            :no-conditions
            {:status 400 :body {:ok false :error "no 記録項目 set"
                                :provision kensaku/provision
                                :why (:kensaku/why r)}}

            :invalid-condition
            {:status 400 :body {:ok false :error "invalid search condition"
                                :provision kensaku/provision
                                :problems (:kensaku/problems r)}}

            {:status 200
             :body {:ok true :client client-id
                    :provision kensaku/provision
                    :conditions (:kensaku/conditions r)
                    ;; both, always: nothing matched out of nothing examined
                    ;; and nothing matched out of four hundred are different
                    ;; answers
                    :searched-count (:kensaku/searched-count r)
                    :match-count (:kensaku/match-count r)
                    :results (:kensaku/results r)
                    ;; The declaration is the OPERATOR's, held on the client
                    ;; record, never read from the request — the same rule
                    ;; that keeps a caller from naming its own client. A
                    ;; caller that could declare its own 優良帳簿 election
                    ;; could declare compliance into existence.
                    :conformance (kensaku/conformance
                                  {:postings postings
                                   :declared? (:yuryo-chobo-declared?
                                               (store/client store client-id))
                                   :search-fn kensaku/search})}}))))))

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

#?(:cljs
   (defn- query-map
     "The request URL's query string as a `{param value}` map.

     A repeated parameter keeps the LAST value rather than silently building a
     list the core cannot type. `?date=a&date=b` is a caller that meant one
     thing and said two; the condition it gets is one of them, and
     `:conditions` is echoed in the response so it can see which."
     [context]
     (let [url (js/URL. (aget (aget context "request") "url"))]
       (persistent!
        (reduce (fn [acc pair] (assoc! acc (aget pair 0) (aget pair 1)))
                (transient {})
                (es6-iterator-seq (.entries (.-searchParams url))))))))

#?(:cljs
   (defn on-request-get-search
     "Verifies the CACAO and hands an already-verified caller to
     `search-core`. No policy of its own — the same shape as
     `on-request-get-trial-balance`."
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
                      (json-response (search-core (store/mem-store) allowlist (:iss v)
                                                  (query-map context))))))
           (.catch (fn [e]
                     (json-response {:status 500
                                     :body {:ok false :error "request failed"
                                            :reason (ex-message e)}})))))))
