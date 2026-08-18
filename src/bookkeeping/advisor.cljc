(ns bookkeeping.advisor
  "BookkeepingAdvisor — proposes a bookkeeping operation (draft a
  journal entry from a source document, reconcile a ledger, issue an
  invoice, close a period) for a registered client. The advisor is
  swappable: `mock-advisor` (deterministic, default in dev/tests/CI) or
  `llm-advisor` (wraps a real `langchain.model/ChatModel`). Either way
  the advisor ONLY produces a PROPOSAL — it never writes to the store
  and has no notion of client provenance, source-document truth or
  double-entry balance; `bookkeeping.governor` is the independent
  system that decides whether the proposal may proceed. Modeled on
  cloud-itonami-isco-2411's accounting.advisor.

  A proposal is a map:
    {:op :draft-entry|:reconcile|:issue-invoice|:close-period
     :effect :propose        ; the advisor NEVER emits a raw store write
     :source-doc str-or-nil  ; cited source document id (:draft-entry)
     :tax-treatment kw-or-nil ; e.g. :input-tax-credit — a CLAIM the
                              ; governor then verifies, never a finding
     :transaction-date str-or-nil ; 取引年月日, ISO-8601 YYYY-MM-DD
     :counterparty str-or-nil     ; 取引先
     :lines [{:side :dr|:cr :account str :amount n} ...]
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}
  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared fields
  straight through (a stand-in for what an LLM would extract from a
  source document image / free text), with a stake-derived confidence."
  [_store {:keys [op stake source-doc lines transaction-date counterparty
                  tax-treatment]
           :as request}]
  {:op op
   :effect :propose
   :source-doc source-doc
   :lines (vec lines)
   ;; 税務処理の主張 — read straight through, like every other declared field
   ;; here. Measured 2026-08-18: this key was the ONE the mock dropped, and
   ;; dropping it made the governor's rules 5 and 6 (unchecked jurisdiction,
   ;; qualified invoice — 消費税法 第三十条第七項) structurally unreachable
   ;; through `bookkeeping.actor`, since they fire only on a proposal
   ;; claiming :input-tax-credit and no proposal the default advisor built
   ;; could carry one. They were exercised only by calling the governor
   ;; directly. A rule that no path can reach is not enforced.
   ;;
   ;; It is still the ADVISOR's claim and not the truth: the governor
   ;; verifies it against the registered source document, and
   ;; `bookkeeping.shinkoku` reports every posting whose claim was never
   ;; made as :no-tax-claim rather than as credited.
   :tax-treatment tax-treatment
   ;; 取引年月日 / 取引先 — two of the three 記録項目 規則第五条第五項第一号ハ
   ;; requires a 優良帳簿 to be searchable by. Read straight through like
   ;; :source-doc: the advisor extracts, it does not invent, and a fabricated
   ;; counterparty is exactly what the governor's source-doc rule exists to
   ;; stop from the other direction.
   :transaction-date transaction-date
   :counterparty counterparty
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a bookkeeping advisor. Given an operation request, propose
   an :op, the cited :source-doc, balanced double-entry :lines, an
   honest :confidence (0.0-1.0) and a :stake (:low/:medium/:high).
   Never fabricate a source document or confidence you don't have.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
