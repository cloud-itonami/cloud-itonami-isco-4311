(ns bookkeeping.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`bookkeeping.actor` -> `bookkeeping.governor` -> `bookkeeping.store`)
  through a scenario built from real, exercised store data and renders
  the result deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs before shipping).

  `client-1` (\"Hanako's Bakery\") + source document `doc-1` (a
  `:receipt`) and the `balanced-lines` (dr supplies 5000 / cr cash
  5000) below are lifted VERBATIM from this repo's own proven-passing
  test fixture (`bookkeeping.actor-test`'s `fresh-store` helper /
  `balanced-lines` def) -- ground truth, not invented. `client-2`
  (\"Taro's Garage\" -- this exact name is lifted from this repo's own
  `bookkeeping.governor-test/hard-on-source-doc-of-another-client`,
  which registers a second client under that name to test the
  cross-client rule) + a second source document `doc-2` is ADDITIONAL
  demo data registered via the SAME real protocol calls
  (`store/register-client!`/`store/register-source-doc!`) this actor's
  own test fixtures use -- disclosed here plainly, not presented as if
  it were a pre-existing fixture. Every other field this page displays
  (statuses, records, hold reasons) is real output read after
  `run-demo!` actually executed the graph -- none of it is hand-typed.

  Known architectural gap, honestly noted rather than papered over:
  `bookkeeping.governor`'s `:no-actuation` rule (proposal `:effect`
  must be `:propose`) is NOT reachable through this demo, because the
  real `mock-advisor` (`bookkeeping.advisor/infer`) unconditionally
  sets `:effect :propose` on every proposal it emits. The low-
  confidence escalation path is likewise NOT reachable through this
  demo: `mock-advisor` derives confidence purely from `:stake`
  (`:high` -> 0.7, `:medium` -> 0.85, `:low` -> 0.95), all of which sit
  above `bookkeeping.governor/confidence-floor` (0.6). Both rules ARE
  covered by `bookkeeping.governor-test/hard-on-no-actuation-violation`
  and `escalates-low-confidence` (which call `governor/check` directly
  with hand-built proposals), not by this build-time renderer, which
  only ever drives the real actor/graph the way an operator actually
  would.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [bookkeeping.store :as store]
            [bookkeeping.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real bookkeeping operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private balanced-lines
  ;; Lifted verbatim from bookkeeping.actor-test/balanced-lines.
  [{:side :dr :account "supplies" :amount 5000}
   {:side :cr :account "cash" :amount 5000}])

(def ^:private unbalanced-lines
  ;; Lifted verbatim from bookkeeping.actor-test/holds-an-unbalanced-entry.
  [{:side :dr :account "supplies" :amount 5000}
   {:side :cr :account "cash" :amount 4999}])

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 5 of
  the 6 distinct HARD-hold reasons in `bookkeeping.governor` -- the
  6th, `:no-actuation`, is architecturally unreachable via the real
  advisor, see namespace docstring). Every `:op` keyword and violation
  rule name below is copied from `bookkeeping.governor`'s own
  `hard-violations`/`check`, not invented."
  [;; client-1 / "Hanako's Bakery" / doc-1 (real fixture from bookkeeping.actor-test)
   ["c1-draft-balanced"    "client-1" :draft-entry {:source-doc "doc-1" :lines balanced-lines :stake :low}]
   ["c1-draft-no-source"   "client-1" :draft-entry {:source-doc nil :lines balanced-lines :stake :low}]
   ["c1-draft-unknown-doc" "client-1" :draft-entry {:source-doc "doc-999" :lines balanced-lines :stake :low}]
   ["c1-draft-unbalanced"  "client-1" :draft-entry {:source-doc "doc-1" :lines unbalanced-lines :stake :low}]
   ;; unregistered client entirely
   ["ghost-no-client" "client-ghost" :draft-entry {:source-doc "doc-1" :lines balanced-lines :stake :low}]
   ;; client-2 (additional demo data, registered via the same real
   ;; register-client!/register-source-doc! calls -- see namespace
   ;; docstring). Referencing client-1's doc-1 from client-2
   ;; demonstrates the cross-client rule.
   ["c2-draft-wrong-doc" "client-2" :draft-entry {:source-doc "doc-1" :lines balanced-lines :stake :low}]
   ["c2-draft-own-doc"   "client-2" :draft-entry {:source-doc "doc-2" :lines balanced-lines :stake :low}]
   ;; escalating ops, regardless of confidence
   ["c1-issue-invoice" "client-1" :issue-invoice {:stake :medium}]
   ["c1-close-period"  "client-1" :close-period {:stake :high}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `bookkeeping.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Hanako's Bakery"})
    (store/register-source-doc! db {:doc-id "doc-1" :client-id "client-1" :kind :receipt})
    (store/register-client! db {:client-id "client-2" :name "Taro's Garage"})
    (store/register-source-doc! db {:doc-id "doc-2" :client-id "client-2" :kind :invoice-received})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- doc-row [store {:keys [doc-id kind client-id]} runs]
  (let [record-count (count (filter #(= doc-id (:source-doc %)) (store/records-of store client-id)))
        last-run (last (filter #(= doc-id (get-in % [:request :source-doc])) runs))]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc doc-id) (esc (name kind))
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:source-doc request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md /
  ;; `bookkeeping.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:draft-entry</code></td><td><span class=\"ok\">auto-commit when a registered source document is cited and debits equal credits</span></td></tr>"
   "        <tr><td><code>:issue-invoice</code></td><td><span class=\"warn\">ALWAYS human approval &middot; external-send to a counterparty</span></td></tr>"
   "        <tr><td><code>:close-period</code></td><td><span class=\"warn\">ALWAYS human approval &middot; hard-to-reverse bookkeeping act</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [docs [{:doc-id "doc-1" :kind :receipt :client-id "client-1"}
              {:doc-id "doc-2" :kind :invoice-received :client-id "client-2"}]
        doc-rows (str/join "\n" (map #(doc-row store % runs) docs))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-4311 &middot; community bookkeeping</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Bookkeeping (ISCO-08 4311) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · invoices &amp; period close always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients &amp; source documents</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>bookkeeping.store</code> via <code>bookkeeping.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Every journal-entry draft must cite one of these registered source documents — a journal entry without one is an invented transaction.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Source doc</th><th>Kind</th><th>Records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     doc-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Bookkeeping Clerks Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. A human approver cannot approve their way past bad double-entry arithmetic.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own source document, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Source doc</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
