(ns bookkeeping.conformance-test
  "Every verdict this actor can emit is a well-formed verdict.

  `kotoba-lang/governor` measured 376 hand-copied governors in this fleet and
  found one that had silently drifted: it reported a HARD violation as
  escalatable, so an approval queue would show a permanently-refused
  operation as awaiting sign-off and invite a human to try to approve
  something no approval can pass. The drift was invisible through the actor's
  own graph — the router tests `:hard?` first — and so no test caught it.

  This is the test that would have. It does not check WHAT the governor
  decided; the other suites do that. It checks that whatever it decided is
  internally consistent, across the whole space of dispositions this actor
  has: clean, HARD (each rule), escalating op, and low confidence."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.governor :as governor]
            [governor.core :as gov]))

(def ^:private balanced
  [{:side :dr :account "supplies" :amount 5000}
   {:side :cr :account "cash" :amount 5000}])

(defn- store-with [client doc]
  (let [st (store/mem-store)]
    (store/register-client! st client)
    (store/register-source-doc! st doc)
    st))

(def ^:private jp-client {:client-id "c" :name "Hanako's Bakery" :jurisdiction :jp})
(def ^:private qualified-doc {:doc-id "d" :client-id "c" :kind :invoice-received
                              :registration-number "T1234567890123"})

(def ^:private cases
  "Each entry drives `check` into one disposition. Named, so a failure says
  which shape broke rather than which index."
  [{:name :clean
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc "d"
               :lines balanced :confidence 0.9}}

   {:name :hard/no-client
    :client jp-client :doc qualified-doc :request {:client-id "nobody"}
    :proposal {:op :draft-entry :effect :propose :source-doc "d"
               :lines balanced :confidence 0.9}}

   {:name :hard/no-actuation
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :direct-write :source-doc "d"
               :lines balanced :confidence 0.9}}

   {:name :hard/no-source-doc
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc nil
               :lines balanced :confidence 0.9}}

   {:name :hard/unknown-source-doc
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc "missing"
               :lines balanced :confidence 0.9}}

   {:name :hard/source-doc-wrong-client
    :client jp-client :doc {:doc-id "d" :client-id "other" :kind :receipt}
    :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc "d"
               :lines balanced :confidence 0.9}}

   {:name :hard/unbalanced
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc "d"
               :lines [{:side :dr :account "supplies" :amount 5000}
                       {:side :cr :account "cash" :amount 4500}]
               :confidence 0.9}}

   {:name :hard/unchecked-jurisdiction
    :client {:client-id "c" :name "Atlantis Co" :jurisdiction :atlantis}
    :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc "d"
               :tax-treatment :input-tax-credit
               :lines balanced :confidence 0.9}}

   {:name :hard/invalid-registration-number
    :client jp-client :doc {:doc-id "d" :client-id "c" :kind :receipt}
    :request {:client-id "c"}
    :proposal {:op :draft-entry :effect :propose :source-doc "d"
               :tax-treatment :input-tax-credit
               :lines balanced :confidence 0.9}}

   {:name :escalate/issue-invoice
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :issue-invoice :effect :propose :confidence 0.9}}

   {:name :escalate/close-period
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :close-period :effect :propose :confidence 0.9}}

   {:name :escalate/low-confidence
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    :proposal {:op :reconcile :effect :propose :confidence 0.3}}

   {:name :escalate/no-confidence-key
    :client jp-client :doc qualified-doc :request {:client-id "c"}
    ;; a proposal that does not say how confident it is has not said it is
    ;; confident — the absent key must read as 0.0, never as trustworthy.
    :proposal {:op :reconcile :effect :propose}}])

(defn- verdict-for [{:keys [client doc request proposal]}]
  (governor/check request {} proposal (store-with client doc)))

(deftest every-verdict-is-well-formed
  (doseq [{:keys [name] :as c} cases]
    (testing (str name)
      (let [v (verdict-for c)]
        (is (empty? (gov/conformance-failures v))
            (str "非適合: " (pr-str (gov/conformance-failures v))))))))

(deftest the-drift-that-happened-elsewhere-cannot-happen-here
  (testing "a HARD violation is never reported as escalatable"
    (doseq [{:keys [name] :as c} cases
            :let [v (verdict-for c)]
            :when (:hard? v)]
      (testing (str name)
        (is (not (:escalate? v))
            "an approver cannot be invited to wave through a HARD hold")
        (is (not (:ok? v)))
        (is (seq (:violations v)) "a hold must say what it refused")))))

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; evidence floor: a conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing.
  (let [vs (map verdict-for cases)]
    (is (>= (count (filter :ok? vs)) 1) "no clean case")
    (is (>= (count (filter :hard? vs)) 8) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 4) "escalation under-covered")
    (is (= (count cases) (count vs)))))

(deftest escalation-carries-a-reason
  (testing "the console has something to show the approver — free with gov/verdict"
    (doseq [{:keys [name] :as c} cases
            :let [v (verdict-for c)]
            :when (:escalate? v)]
      (testing (str name)
        (is (some? (:escalation-reason v)))))))
