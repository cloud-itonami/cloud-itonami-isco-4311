(ns bookkeeping.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Hanako's Bakery"})
    (store/register-source-doc! st {:doc-id "doc-1" :client-id "client-1" :kind :receipt})
    st))

(def ^:private balanced-lines
  [{:side :dr :account "supplies" :amount 5000}
   {:side :cr :account "cash" :amount 5000}])

(deftest ok-on-clean-balanced-draft-entry
  (let [st (fresh-store)
        proposal {:op :draft-entry :effect :propose :source-doc "doc-1"
                  :lines balanced-lines :confidence 0.9 :stake :low}
        v (governor/check {:client-id "client-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        proposal {:op :draft-entry :effect :propose :source-doc "doc-1"
                  :lines balanced-lines :confidence 0.9 :stake :low}
        v (governor/check {:client-id "no-such-client"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        proposal {:op :draft-entry :effect :direct-write :source-doc "doc-1"
                  :lines balanced-lines :confidence 0.9 :stake :low}
        v (governor/check {:client-id "client-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-missing-source-doc
  (testing "a journal entry without a source document is an invented transaction"
    (let [st (fresh-store)
          proposal {:op :draft-entry :effect :propose :source-doc nil
                    :lines balanced-lines :confidence 0.95 :stake :low}
          v (governor/check {:client-id "client-1"} {} proposal st)]
      (is (:hard? v))
      (is (some #(= :no-source-doc (:rule %)) (:violations v))))))

(deftest hard-on-unknown-source-doc
  (let [st (fresh-store)
        proposal {:op :draft-entry :effect :propose :source-doc "doc-999"
                  :lines balanced-lines :confidence 0.95 :stake :low}
        v (governor/check {:client-id "client-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :unknown-source-doc (:rule %)) (:violations v)))))

(deftest hard-on-source-doc-of-another-client
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Taro's Garage"})
    (let [proposal {:op :draft-entry :effect :propose :source-doc "doc-1"
                    :lines balanced-lines :confidence 0.95 :stake :low}
          v (governor/check {:client-id "client-2"} {} proposal st)]
      (is (:hard? v))
      (is (some #(= :source-doc-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unbalanced-entry
  (testing "an approver cannot approve their way past bad arithmetic"
    (let [st (fresh-store)
          proposal {:op :draft-entry :effect :propose :source-doc "doc-1"
                    :lines [{:side :dr :account "supplies" :amount 5000}
                            {:side :cr :account "cash" :amount 4500}]
                    :confidence 0.99 :stake :low}
          v (governor/check {:client-id "client-1"} {} proposal st)]
      (is (:hard? v))
      (is (some #(= :unbalanced-entry (:rule %)) (:violations v))))))

(deftest escalates-invoice-issue
  (let [st (fresh-store)
        proposal {:op :issue-invoice :effect :propose
                  :confidence 0.9 :stake :medium}
        v (governor/check {:client-id "client-1"} {} proposal st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-period-close
  (let [st (fresh-store)
        proposal {:op :close-period :effect :propose
                  :confidence 0.9 :stake :high}
        v (governor/check {:client-id "client-1"} {} proposal st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        proposal {:op :reconcile :effect :propose :confidence 0.3 :stake :low}
        v (governor/check {:client-id "client-1"} {} proposal st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
