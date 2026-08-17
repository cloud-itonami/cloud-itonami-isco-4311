(ns bookkeeping.jurisdictions-test
  "Tests for the jurisdiction catalog itself, and for the two governor
  checks that read it.

  The catalog tests are deliberately about SHAPE and ABSENCE-HANDLING,
  not about the legal content — this repo cannot verify Japanese tax law
  in a unit test, and a test that pretended to would be the same failure
  the catalog exists to fix. What CAN be tested, and is:

    - every source carries a title, an authority and an http(s) URL
    - `covered?` says no to nil and to an unknown jurisdiction
    - `registration-number-valid?` never answers yes on absence
    - the governor holds a tax claim it could not check"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bookkeeping.store :as store]
            [bookkeeping.jurisdictions :as law]
            [bookkeeping.governor :as governor]))

;; ---------------------------------------------------------------------------
;; the catalog
;; ---------------------------------------------------------------------------

(deftest every-source-is-well-formed
  (doseq [[id s] law/sources]
    (testing (str id)
      (is (string? (:source/title s)))
      (is (not (str/blank? (:source/title s))))
      (is (not (str/blank? (:source/authority s))))
      (is (str/starts-with? (:source/url s) "https://")
          "a citation that is not a fetchable https URL is not a citation"))))

(deftest source-urls-are-distinct
  (testing "the same URL twenty times is one source, not twenty"
    (let [urls (law/source-urls)]
      (is (= (count urls) (count (distinct urls)))))))

(deftest verification-record-separates-reachability-from-content
  (let [v law/catalog-verification]
    (is (= 200 (:catalog/status v)))
    (is (= (count law/sources) (:catalog/reachable-count v)))
    (testing "content was verified for strictly fewer claims than sources"
      (is (< (count (:catalog/content-verified v)) (count law/sources))))
    (testing "what could not be fetched is recorded, not silently dropped"
      (is (seq (:catalog/rejected v))))))

(deftest uncovered-jurisdiction-is-not-covered
  (is (law/covered? :jp))
  (testing "nil is the unchecked case, not a default one"
    (is (not (law/covered? nil))))
  (is (not (law/covered? :atlantis))))

(deftest registration-number-never-passes-on-absence
  (testing "the NTA format: T followed by 13 digits"
    (is (law/registration-number-valid? :jp "T1234567890123")))
  (testing "absence, wrong length, wrong prefix and extra text all fail"
    (is (not (law/registration-number-valid? :jp nil)))
    (is (not (law/registration-number-valid? :jp "")))
    (is (not (law/registration-number-valid? :jp "   ")))
    (is (not (law/registration-number-valid? :jp "T123456789012")))
    (is (not (law/registration-number-valid? :jp "T12345678901234")))
    (is (not (law/registration-number-valid? :jp "1234567890123")))
    (is (not (law/registration-number-valid? :jp "T1234567890123 "))))
  (testing "an uncovered jurisdiction cannot validate anything"
    (is (not (law/registration-number-valid? :atlantis "T1234567890123")))
    (is (not (law/registration-number-valid? nil "T1234567890123")))))

(deftest requires-qualified-invoice-is-nil-not-false-when-unknown
  (testing "unknown must not read as `no requirement`"
    (is (true? (law/requires-qualified-invoice? :jp)))
    (is (nil? (law/requires-qualified-invoice? :atlantis)))
    (is (nil? (law/requires-qualified-invoice? nil)))))

;; ---------------------------------------------------------------------------
;; the governor checks that read it
;; ---------------------------------------------------------------------------

(def ^:private balanced-lines
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
   :lines balanced-lines :confidence 0.95 :stake :low})

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
          v (governor/check {:client-id "c"} {} (tax-claim "d") st)]
      (is (:hard? v))
      (is (some #(= :invalid-registration-number (:rule %)) (:violations v))))))

(deftest hard-on-malformed-registration-number
  (let [st (store-with {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp}
                       {:doc-id "d" :client-id "c" :kind :invoice-received
                        :registration-number "1234567890123"})
        v (governor/check {:client-id "c"} {} (tax-claim "d") st)]
    (is (:hard? v))
    (is (some #(= :invalid-registration-number (:rule %)) (:violations v)))))

(deftest entry-without-a-tax-claim-is-unaffected
  (testing "the actor does not invent a tax position in order to check one"
    (let [st (store-with {:client-id "c" :name "Hanako's Bakery"}
                         {:doc-id "d" :client-id "c" :kind :receipt})
          proposal {:op :draft-entry :effect :propose :source-doc "d"
                    :lines balanced-lines :confidence 0.9 :stake :low}
          v (governor/check {:client-id "c"} {} proposal st)]
      (is (:ok? v))
      (is (not (:hard? v)))
      (is (empty? (filter #(#{:unchecked-jurisdiction :invalid-registration-number}
                            (:rule %))
                          (:violations v)))))))
