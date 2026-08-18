(ns bookkeeping.store-contract-test
  "Every assertion here runs against BOTH backends.

  That is the whole point of the file. `actor-test`, `governor-test`,
  `tax-rules-test`, `ledger-test` and the edge tests all construct
  `mem-store` and only `mem-store`, so a `DatomicStore` that answers
  differently would not redden a single one of them.

  ## Why this matters more here than as hygiene

  `bookkeeping.trial-balance` reads THROUGH `postings-of`. A backend that
  returned postings out of order, unscoped to the client, or de-duplicated
  would not raise an error — it would produce a different balance sheet.
  Silent, and authoritative-looking. Order and scoping are therefore
  asserted directly rather than through counts."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.posting :as posting]
            [bookkeeping.trial-balance :as tb]))

(def backends {:mem store/mem-store :datomic store/datomic-store})

(deftest there-are-still-two-backends
  (testing "evidence floor: a contract test that quietly degrades to one
            backend passes forever and proves nothing"
    (is (= 2 (count backends)))
    (is (= 2 (count (into #{} (map #(type ((val %))) backends)))))))

(defn- seeded [make]
  (let [st (make)]
    (store/register-client! st {:client-id "c-1" :name "Hanako's Bakery"})
    (store/register-client! st {:client-id "c-2" :name "Taro's Garage"})
    (store/register-source-doc! st {:doc-id "d-1" :client-id "c-1" :kind :receipt})
    st))

(defn- lines [amount currency]
  [{:side :dr :account "supplies" :amount amount :currency currency}
   {:side :cr :account "cash" :amount amount :currency currency}])

(defmacro ^:private on-both [[st-sym label-sym] & body]
  `(doseq [[~label-sym make#] backends]
     (testing (str "backend " ~label-sym)
       (let [~st-sym (seeded make#)]
         ~@body))))

;; ---------------------------------------------------------------------------
;; registries
;; ---------------------------------------------------------------------------

(deftest a-registered-client-reads-back
  (on-both [st _]
    (is (= "Hanako's Bakery" (:name (store/client st "c-1"))))
    (is (nil? (store/client st "nobody")) "absence is nil, not an empty map")))

(deftest a-registered-source-doc-reads-back-whole
  (on-both [st _]
    (let [d (store/source-doc st "d-1")]
      (is (= "c-1" (:client-id d)))
      (is (= :receipt (:kind d))))
    (is (nil? (store/source-doc st "no-such-doc")))))

(deftest re-registering-replaces-rather-than-forking
  (on-both [st _]
    (store/register-client! st {:client-id "c-1" :name "Renamed"})
    (is (= "Renamed" (:name (store/client st "c-1"))))))

;; ---------------------------------------------------------------------------
;; records
;; ---------------------------------------------------------------------------

(deftest records-append-in-order-and-are-scoped
  (on-both [st _]
    (doseq [n [1 2 3]]
      (store/commit-record! st {:client-id "c-1" :op :draft-entry :n n}))
    (store/commit-record! st {:client-id "c-2" :op :draft-entry :n 99})
    (is (= [1 2 3] (mapv :n (store/records-of st "c-1")))
        "order, not count — a backend that returns a set passes a count check")
    (is (= [99] (mapv :n (store/records-of st "c-2"))))))

;; ---------------------------------------------------------------------------
;; the ledger
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-append-only-and-ordered
  (on-both [st _]
    (doseq [n [1 2 3]] (store/append-ledger! st {:disposition :commit :n n}))
    (is (= [1 2 3] (mapv :n (store/ledger st))))))

(deftest identical-ledger-facts-both-land
  (testing "two indistinguishable facts are two events, not one — a backend
            that de-duplicates them loses an audit entry"
    (on-both [st _]
      (store/append-ledger! st {:disposition :hold :rule :unbalanced-entry})
      (store/append-ledger! st {:disposition :hold :rule :unbalanced-entry})
      (is (= 2 (count (store/ledger st)))))))

;; ---------------------------------------------------------------------------
;; postings — what the trial balance reads through
;; ---------------------------------------------------------------------------

(deftest postings-append-in-order-and-are-scoped-to-their-client
  (on-both [st _]
    (store/commit-posting! st "c-1" (posting/project "je-1" (lines 5000 "JPY")))
    (store/commit-posting! st "c-1" (posting/project "je-2" (lines 3000 "JPY")))
    (store/commit-posting! st "c-2" (posting/project "je-9" (lines 100 "JPY")))
    (is (= ["je-1" "je-2"] (mapv :ledger/posting (store/postings-of st "c-1")))
        "order matters: the trial balance reads through this")
    (is (= ["je-9"] (mapv :ledger/posting (store/postings-of st "c-2"))))
    (is (empty? (store/postings-of st "c-3"))
        "an unknown client has no postings, not everyone's")))

(deftest a-posting-survives-the-round-trip-whole
  (testing "the trial balance reads :ledger/entries out of what came back —
            a backend that stored only the id would balance to nothing"
    (on-both [st _]
      (store/commit-posting! st "c-1" (posting/project "je-1" (lines 5000 "JPY")))
      (let [p (first (store/postings-of st "c-1"))]
        (is (= 2 (count (:ledger/entries p))))
        (is (true? (:ledger/balanced? p)))
        (is (= #{:debit :credit} (into #{} (map :ledger/side (:ledger/entries p)))))
        (is (= #{"JPY"} (into #{} (map :ledger/currency (:ledger/entries p)))))))))

(deftest the-trial-balance-is-identical-on-both-backends
  (testing "the property that actually matters: the same commits produce the
            same balance sheet whichever backend held them"
    (let [reports (into {}
                        (map (fn [[label make]]
                               (let [st (seeded make)]
                                 (store/commit-posting! st "c-1" (posting/project "je-1" (lines 5000 "JPY")))
                                 (store/commit-posting! st "c-1" (posting/project "je-2" (lines 3000 "JPY")))
                                 [label (tb/report (store/postings-of st "c-1"))])))
                        backends)]
      (is (= 2 (count reports)))
      (is (apply = (vals reports)))
      (is (= 8000 (:balance (get-in (val (first reports))
                                    [:trial-balance/balances ["supplies" "JPY"]]))))
      (is (true? (:trial-balance/balanced? (val (first reports))))))))

(deftest an-empty-store-reads-empty-on-both
  (on-both [st _]
    (is (empty? (store/records-of st "c-1")))
    (is (empty? (store/postings-of st "c-1")))
    (is (empty? (store/ledger st)))
    (is (not (tb/balanced? (store/postings-of st "c-1")))
        "and an empty ledger is still not a balanced one")))

;; ---------------------------------------------------------------------------
;; idempotency — added because a one-backend mutation proved it was not covered
;; ---------------------------------------------------------------------------
;;
;; The idempotency tests live in idempotency_test.clj and construct mem-store
;; only, so breaking ONLY the DatomicStore's dedup reddened nothing. Measured
;; 2026-08-18: 0 failures. A carrier retrying against the durable backend
;; would have doubled that client's books and not the other's.

(deftest committing-the-same-posting-twice-posts-once-on-both-backends
  (on-both [st _]
    (let [p (posting/project "fixed" (lines 5000 "JPY"))]
      (dotimes [_ 3] (store/commit-posting! st "c-1" p))
      (is (= 1 (count (store/postings-of st "c-1"))))
      (is (= 5000 (:balance (get (tb/balances (store/postings-of st "c-1"))
                                 ["supplies" "JPY"])))
          "the trial balance is what was wrong, so it is what is asserted"))))

(deftest the-second-write-is-a-no-op-not-an-overwrite-on-both-backends
  (on-both [st _]
    (store/commit-posting! st "c-1" (posting/project "fixed" (lines 5000 "JPY")))
    (store/commit-posting! st "c-1" (posting/project "fixed" (lines 9999 "JPY")))
    (let [ps (store/postings-of st "c-1")]
      (is (= 1 (count ps)))
      (is (= 5000 (:ledger/amount (first (:ledger/entries (first ps)))))
          "a posting that changed under a stable id would be an edit to an
           append-only ledger"))))

(deftest idempotency-is-per-client-on-both-backends
  (on-both [st _]
    (let [p (posting/project "shared" (lines 100 "JPY"))]
      (store/commit-posting! st "c-1" p)
      (store/commit-posting! st "c-2" p)
      (is (= 1 (count (store/postings-of st "c-1"))))
      (is (= 1 (count (store/postings-of st "c-2")))
          "two clients may legitimately hold a posting with the same id"))))
