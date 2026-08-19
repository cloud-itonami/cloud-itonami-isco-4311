(ns bookkeeping.hozon-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bookkeeping.hozon :as hz]
            [bookkeeping.store :as store]))

(defn- store-for [& {:keys [jurisdiction measure] :or {jurisdiction [:jp]}}]
  (let [s (store/mem-store)]
    (store/register-client! s (cond-> {:client-id "c" :name "K" :jurisdiction jurisdiction}
                                measure (assoc :preservation-measure measure)))
    s))

(def ^:private good-records
  [{:record-id "r1" :transaction-date "2026-06-01" :amount 100000 :counterparty "X"}
   {:record-id "r2" :transaction-date "2026-06-02" :amount 5000 :counterparty "Y"}])

(defn- conf [s & {:keys [records evidence] :or {records good-records}}]
  (hz/conformance {:store s :client-id "c" :records records :evidence evidence}))

(deftest any-one-of-the-four-satisfies-the-article
  (testing "第四条第一項 lists four measures and any one satisfies it. A caller
            must be able to see the other three, so the list rides along on
            every status — otherwise an operator concludes the measure it
            implements is the only one that counts"
    (let [r (conf (store-for))]
      (is (= 4 (count (:hozon/measures-available r))))
      (is (= #{:timestamp-before-exchange :timestamp-after-exchange
               :tamper-evident-system :written-procedure}
             (set (:hozon/measures-available r)))))))

(deftest an-unread-jurisdiction-is-not-a-pass
  (let [r (conf (store-for :jurisdiction [:us] :measure :written-procedure))]
    (is (= :unchecked-jurisdiction (:hozon/status r)))
    (is (not (hz/conformant? r)))
    (is (string? (:hozon/why r)) "taxlaw's recorded reason rides along")))

(deftest nobody-declared-is-not-nobody-needed
  (testing "the article is satisfied by any one of four and this client may
            well satisfy one — but nothing here says so, and :conformant would
            be a claim about a system nobody described"
    (let [r (conf (store-for))]
      (is (= :measure-not-declared (:hozon/status r)))
      (is (not (hz/conformant? r)))
      (is (nil? (:hozon/measure r))))))

(deftest a-measure-outside-the-four-is-refused
  (let [r (conf (store-for :measure :blockchain) :evidence {:system-attestation "chain"})]
    (is (= :measure-not-recognised (:hozon/status r)))))

(deftest a-declaration-with-nothing-behind-it-is-not-the-measure
  (testing "writing :tamper-evident-system in a client record satisfies a
            keyword, not 第三号"
    (let [r (conf (store-for :measure :tamper-evident-system))]
      (is (= :measure-unevidenced (:hozon/status r)))
      (is (= :system-attestation (:needs (:hozon/evidence-required r))))
      (is (seq (:hozon/evidence-problems r)))))
  (testing "evidence of the wrong kind is refused too — a 規程 is not an attestation"
    (let [r (conf (store-for :measure :tamper-evident-system)
                  :evidence {:procedure-document "doc-1"})]
      (is (= :measure-unevidenced (:hozon/status r)))
      (is (= :wrong-evidence (:problem (first (:hozon/evidence-problems r)))))))
  (testing "and blank evidence is not evidence"
    (let [r (conf (store-for :measure :written-procedure) :evidence {:procedure-document "  "})]
      (is (= :measure-unevidenced (:hozon/status r))))))

(deftest the-three-record-items-are-named-per-record
  (let [r (conf (store-for :measure :written-procedure)
                :evidence {:procedure-document "doc-1"}
                :records [{:record-id "r1" :transaction-date "2026-06-01"}
                          (first good-records)])]
    (is (= :records-missing-items (:hozon/status r)))
    (is (= [{:record-id "r1" :missing [:amount :counterparty]}]
           (:hozon/record-problems r))
        "named, per record — a count is not something anyone can act on")))

(deftest the-pass-is-narrow-and-carries-the-standing-limit
  (let [r (conf (store-for :measure :written-procedure)
                :evidence {:procedure-document "doc-1"})]
    (is (= :conformant (:hozon/status r)))
    (is (hz/conformant? r))
    (testing "and even the pass says the store evidences none of the four, so
              nobody reads :conformant as this actor having guaranteed it"
      (is (str/includes? (:hozon/store-evidences-nothing r) "no correction history")))))

(deftest a-measure-the-store-cannot-evidence-is-not-satisfied-by-the-store
  (testing "bookkeeping.store/Store has no correction history and no
            immutability, so 第三号 is the operator's to satisfy elsewhere.
            The namespace refuses rather than believing the declaration"
    (let [r (conf (store-for :measure :tamper-evident-system))]
      (is (not (hz/conformant? r)))
      (is (str/includes? (str (:why (get hz/evidence-required :tamper-evident-system)))
                         "SYSTEM")))))
