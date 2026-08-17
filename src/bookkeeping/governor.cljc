(ns bookkeeping.governor
  "BookkeepingClerksGovernor — the independent safety/traceability layer
  for the ISCO-08 4311 community bookkeeping actor. Wired as its own
  `:govern` node in `bookkeeping.actor`'s StateGraph, downstream of
  `:advise` — the Advisor has no notion of client provenance, source-
  document truth or double-entry balance, so this MUST be a separate
  system able to reject a proposal (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-2411's accounting.governor with two
  bookkeeping-specific HARD checks.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance   — the request's client must be registered.
    2. no-actuation        — proposal :effect must be :propose.
    3. source-doc basis    — a :draft-entry proposal must cite a
                             REGISTERED source document belonging to the
                             same client (no invented transactions —
                             the fleet's fabricated-spec-basis rule,
                             bookkeeping edition).
    4. double-entry balance — a :draft-entry's debit total must equal
                             its credit total. An unbalanced entry is
                             structurally wrong; a human approver cannot
                             approve their way past bad arithmetic.
    5. checked jurisdiction — a proposal CLAIMING a tax consequence
                             (:tax-treatment :input-tax-credit) in a
                             jurisdiction `bookkeeping.jurisdictions` does
                             not cover is HELD. An unchecked jurisdiction
                             is a hold, not a pass (kintai's
                             :unchecked-law, bookkeeping edition).
    6. qualified invoice   — where the jurisdiction conditions input-tax
                             credit on a qualified invoice, the cited
                             source document must carry a registration
                             number in that jurisdiction's format. A
                             missing number is not a zero-rated entry;
                             it is an unanswered question.
  ESCALATION invariants (:escalate? true, human sign-off):
    7. :op :issue-invoice  (external-send to a counterparty).
    8. :op :close-period   (hard-to-reverse bookkeeping act).
    9. low confidence (< `confidence-floor`).

  Checks 5 and 6 fire ONLY on a proposal that claims the credit. A
  bookkeeping entry with no tax claim is unaffected — the actor does not
  invent a tax position in order to have one to check, and widening these
  to every entry would be a separate decision with its own evidence."
  (:require [bookkeeping.store :as store]
            [bookkeeping.jurisdictions :as law]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:issue-invoice :close-period})

(defn- line-total [lines side]
  (transduce (comp (filter #(= side (:side %))) (map :amount)) + 0 lines))

(defn- claims-input-tax-credit? [proposal]
  (= :input-tax-credit (:tax-treatment proposal)))

(defn- hard-violations [{:keys [request proposal]} client-record doc-record]
  (let [{:keys [op lines]} proposal
        draft? (= :draft-entry op)
        ;; the jurisdiction is the CLIENT's, not the proposal's — an
        ;; advisor that could pick its own jurisdiction could pick one
        ;; whose rules it satisfies.
        juris (:jurisdiction client-record)
        tax-claim? (claims-input-tax-credit? proposal)
        covered? (law/covered? juris)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and draft? (nil? (:source-doc proposal)))
      (conj {:rule :no-source-doc :detail "仕訳 draft は原始証憑の引用が必須（取引の捏造禁止）"})

      (and draft? (:source-doc proposal) (nil? doc-record))
      (conj {:rule :unknown-source-doc
             :detail (str "未登録の原始証憑: " (:source-doc proposal))})

      (and draft? doc-record
           (not= (:client-id doc-record) (:client-id request)))
      (conj {:rule :source-doc-wrong-client
             :detail "原始証憑が別 client のもの"})

      (and draft? (not= (line-total lines :dr) (line-total lines :cr)))
      (conj {:rule :unbalanced-entry
             :detail (str "借方合計 " (line-total lines :dr)
                          " ≠ 貸方合計 " (line-total lines :cr))})

      ;; 5. the claim is made in a jurisdiction nobody has catalogued.
      (and tax-claim? (not covered?))
      (conj {:rule :unchecked-jurisdiction
             :detail (str "仕入税額控除を主張しているが、法域 "
                          (pr-str juris)
                          " は bookkeeping.jurisdictions に無い（未検査は合格ではない）")})

      ;; 6. the jurisdiction IS catalogued and conditions the credit on a
      ;; qualified invoice — so the cited document must carry a valid
      ;; registration number.
      (and tax-claim? covered?
           (law/requires-qualified-invoice? juris)
           (not (law/registration-number-valid?
                 juris (:registration-number doc-record))))
      (conj {:rule :invalid-registration-number
             :detail (str "仕入税額控除には適格請求書発行事業者の登録番号が要る。"
                          "証憑 " (pr-str (:source-doc proposal)) " の登録番号: "
                          (pr-str (:registration-number doc-record)))}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `bookkeeping.store/Store`. Pure — never mutates
  the store. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        doc-record (some->> (:source-doc proposal) (store/source-doc store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record doc-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
