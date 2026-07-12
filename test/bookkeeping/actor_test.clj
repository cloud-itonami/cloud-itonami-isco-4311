(ns bookkeeping.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.actor :as actor]
            [bookkeeping.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Hanako's Bakery"})
    (store/register-source-doc! st {:doc-id "doc-1" :client-id "client-1" :kind :receipt})
    st))

(def ^:private balanced-lines
  [{:side :dr :account "supplies" :amount 5000}
   {:side :cr :account "cash" :amount 5000}])

(deftest commits-a-clean-balanced-draft-entry
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-entry :stake :low
                 :source-doc "doc-1" :lines balanced-lines}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= "doc-1" (get-in result [:state :record :source-doc])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-invented-transaction-without-committing
  (testing "no source document -> HARD hold, nothing written"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})
          request {:client-id "client-1" :op :draft-entry :stake :low
                   :source-doc nil :lines balanced-lines}
          result (actor/run-request! graph request {} "thread-2")]
      (is (= :done (:status result)))
      (is (nil? (get-in result [:state :record])))
      (is (empty? (store/records-of st "client-1")))
      (is (= :hold (:disposition (:state result)))))))

(deftest holds-an-unbalanced-entry
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :draft-entry :stake :low
                 :source-doc "doc-1"
                 :lines [{:side :dr :account "supplies" :amount 5000}
                         {:side :cr :account "cash" :amount 4999}]}
        result (actor/run-request! graph request {} "thread-3")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-commits-invoice-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; issuing an invoice is external-send: always escalates
        request {:client-id "client-1" :op :issue-invoice :stake :medium}
        interrupted (actor/run-request! graph request {} "thread-4")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-4")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "client-1")))))))
