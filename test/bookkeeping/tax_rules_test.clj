(ns bookkeeping.tax-rules-test
  "The two governor rules that read `kotoba-lang/taxlaw`.

  The catalog's own tests live in that library now. What has to stay HERE is
  the wiring: that this actor asks the right question, uses the CLIENT's
  jurisdiction rather than one the proposal could choose for itself, and
  splits the library's three-valued answer into two distinct holds.

  When `jurisdictions.cljc` moved out, deleting its test file wholesale took
  these with it and the suite fell from 30 tests to 18 — a silent loss of
  actor coverage dressed up as a clean extraction. They are restored here."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.governor :as governor]))

(def ^:private balanced
  [{:side :dr :account "supplies" :amount 5000}
   {:side :cr :account "cash" :amount 5000}])

(defn- store-with [client doc]
  (let [st (store/mem-store)]
    (store/register-client! st client)
    (store/register-source-doc! st doc)
    st))

(defn- tax-claim [doc-id]
  {:op :draft-entry :effect :propose :source-doc doc-id
   :tax-treatment :input-tax-credit
   :lines balanced :confidence 0.95 :stake :low})

(deftest ok-on-jp-claim-with-valid-registration-number
  (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                       {:doc-id "d" :client-id "c" :kind :invoice-received
                        :registration-number "T1234567890123"})
        v (governor/check {:client-id "c"} {} (tax-claim "d") st)]
    (is (:ok? v))
    (is (not (:hard? v)))))

(deftest hard-on-tax-claim-in-uncatalogued-jurisdiction
  (testing "an unchecked jurisdiction is a HOLD, not a pass"
    (let [st (store-with {:client-id "c" :name "Atlantis Co" :jurisdiction :atlantis}
                         {:doc-id "d" :client-id "c" :kind :invoice-received
                          :registration-number "T1234567890123"})
          v (governor/check {:client-id "c"} {} (tax-claim "d") st)]
      (is (:hard? v))
      (is (some #(= :unchecked-jurisdiction (:rule %)) (:violations v))))))

(deftest hard-on-tax-claim-with-no-declared-jurisdiction
  (testing "an undeclared jurisdiction is unchecked, not domestic-by-default"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery"}
                         {:doc-id "d" :client-id "c" :kind :invoice-received
                          :registration-number "T1234567890123"})
          v (governor/check {:client-id "c"} {} (tax-claim "d") st)]
      (is (:hard? v))
      (is (some #(= :unchecked-jurisdiction (:rule %)) (:violations v))))))

(deftest hard-on-missing-registration-number
  (testing "a receipt with no registration number does not become creditable
            by being silent about it"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :receipt})
          v (governor/check {:client-id "c"} {} (tax-claim "d") st)
          detail (:detail (first (filter #(= :invalid-registration-number (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (some? detail))
      (is (re-find #"missing-registration-number" detail)
          "the library's reason is surfaced, so the two failures are
           distinguishable to whoever reads the hold"))))

(deftest hard-on-malformed-registration-number
  (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                       {:doc-id "d" :client-id "c" :kind :invoice-received
                        :registration-number "1234567890123"})
        v (governor/check {:client-id "c"} {} (tax-claim "d") st)
        detail (:detail (first (filter #(= :invalid-registration-number (:rule %))
                                       (:violations v))))]
    (is (:hard? v))
    (is (re-find #"malformed-registration-number" detail))))

(deftest the-jurisdiction-is-the-clients-not-the-proposals
  (testing "an advisor that could pick its own jurisdiction could pick one
            whose rules it satisfies"
    (let [st (store-with {:client-id "c" :name "Atlantis Co" :jurisdiction :atlantis}
                         {:doc-id "d" :client-id "c" :kind :invoice-received
                          :registration-number "T1234567890123"})
          v (governor/check {:client-id "c"} {}
                            (assoc (tax-claim "d") :jurisdiction :jp) st)]
      (is (:hard? v))
      (is (some #(= :unchecked-jurisdiction (:rule %)) (:violations v))
          "declaring :jp on the proposal must not rescue an :atlantis client"))))

(deftest entry-without-a-tax-claim-is-unaffected
  (testing "the actor does not invent a tax position in order to check one"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery"}
                         {:doc-id "d" :client-id "c" :kind :receipt})
          proposal {:op :draft-entry :effect :propose :source-doc "d"
                    :lines balanced :confidence 0.9 :stake :low}
          v (governor/check {:client-id "c"} {} proposal st)]
      (is (:ok? v))
      (is (empty? (filter #(#{:unchecked-jurisdiction :invalid-registration-number}
                            (:rule %))
                          (:violations v)))))))

;; ---------------------------------------------------------------------------
;; 電子帳簿保存法 第七条 — an electronic transaction kept only on paper
;; ---------------------------------------------------------------------------

(defn- plain-entry [doc-id]
  {:op :draft-entry :effect :propose :source-doc doc-id
   :lines balanced :confidence 0.95 :stake :low})

(defn- rules-of [v] (into #{} (map :rule) (:violations v)))

(deftest hard-on-an-electronic-transaction-kept-only-on-paper
  (testing "the obligation is to preserve the 電磁的記録 itself; printing it
            and keeping the paper is not that"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :invoice-received
                          :origin :electronic-transaction :preservation :paper})
          v (governor/check {:client-id "c"} {} (plain-entry "d") st)
          detail (:detail (first (filter #(= :electronic-record-not-preserved (:rule %))
                                         (:violations v))))]
      (is (:hard? v))
      (is (contains? (rules-of v) :electronic-record-not-preserved))
      (is (re-find #"第七条" detail)
          "a refusal names the article it rests on"))))

(deftest hard-when-preservation-was-never-recorded
  (testing "silence about preservation is not preservation"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :invoice-received
                          :origin :electronic-transaction})
          v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
      (is (:hard? v))
      (is (re-find #"preservation-not-recorded"
                   (:detail (first (filter #(= :electronic-record-not-preserved (:rule %))
                                           (:violations v)))))))))

(deftest ok-when-the-electronic-record-is-preserved-electronically
  (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                       {:doc-id "d" :client-id "c" :kind :invoice-received
                        :origin :electronic-transaction :preservation :electronic})
        v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
    (is (:ok? v))
    (is (true? (:taxlaw/preserved? (get-in v [:tax :preservation]))))))

(deftest the-rule-is-not-scoped-to-a-tax-claim
  (testing "第七条 binds the 保存義務者 whenever an 電子取引 happened, not only
            when a credit is being claimed for it — unlike rules 5 and 6"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :invoice-received
                          :origin :electronic-transaction :preservation :paper})
          v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
      (is (contains? (rules-of v) :electronic-record-not-preserved))
      (is (not (contains? (rules-of v) :unchecked-jurisdiction))
          "no tax treatment was claimed, so rules 5 and 6 stay silent"))))

(deftest a-paper-transaction-on-paper-raises-no-article-7-question
  (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                       {:doc-id "d" :client-id "c" :kind :receipt
                        :origin :paper :preservation :paper})
        v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
    (is (:ok? v))
    (is (not (contains? (rules-of v) :electronic-record-not-preserved)))))

;; ---------------------------------------------------------------------------
;; the not-checked cases must stay visible
;; ---------------------------------------------------------------------------

(deftest a-document-that-declares-no-origin-is-not-held
  (testing "it has asserted nothing, and holding here would stop every
            existing caller"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :receipt})
          v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
      (is (:ok? v))
      (is (not (contains? (rules-of v) :electronic-record-not-preserved))))))

(deftest a-document-that-declares-no-origin-is-not-a-silent-pass
  (testing "otherwise `we looked and it was fine` and `nobody looked` are
            the same output"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :receipt})
          v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
      (is (= :not-declared (get-in v [:tax :preservation :taxlaw/coverage])))
      (is (not (contains? (get-in v [:tax :preservation]) :taxlaw/preserved?))))))

(deftest the-tax-report-is-absent-when-there-is-nothing-to-report
  (testing "the actor does not manufacture an assessment to have one"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                         {:doc-id "d" :client-id "c" :kind :receipt})
          v (governor/check {:client-id "c"} {}
                            {:op :reconcile :effect :propose :confidence 0.9} st)]
      (is (nil? (:tax v))))))

(deftest credit-is-reported-only-when-a-credit-was-claimed
  (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                       {:doc-id "d" :client-id "c" :kind :invoice-received
                        :origin :electronic-transaction :preservation :electronic
                        :registration-number "T1234567890123"})]
    (testing "no claim"
      (let [v (governor/check {:client-id "c"} {} (plain-entry "d") st)]
        (is (nil? (get-in v [:tax :credit])))
        (is (some? (get-in v [:tax :preservation])))))
    (testing "a claim"
      (let [v (governor/check {:client-id "c"} {} (tax-claim "d") st)]
        (is (true? (get-in v [:tax :credit :taxlaw/supported?])))))))
