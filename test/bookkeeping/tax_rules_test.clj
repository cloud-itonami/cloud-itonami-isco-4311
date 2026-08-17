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
