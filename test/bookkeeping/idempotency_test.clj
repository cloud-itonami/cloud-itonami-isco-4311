(ns bookkeeping.idempotency-test
  "A carrier retries. Before this, retrying corrupted the books.

  Measured 2026-08-18 against the previous tip: submitting the same entry
  twice produced TWO postings, both called `d1`, and the trial balance for
  supplies went 5000 -> 10000. The posting id was
  `(or entry-id source-doc)`, which is wrong from both sides — a retry of one
  entry collided with itself, and two different entries citing one receipt
  collided with each other."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.posting :as posting]
            [bookkeeping.trial-balance :as tb]
            [bookkeeping.edge.endpoints :as e]))

(def ^:private did "did:key:z6MkAAA")
(def ^:private allow (e/parse-allowlist (str did "=c-1")))

(defn- seeded []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "c-1" :name "Hanako's Bakery"})
    (store/register-source-doc! st {:doc-id "d1" :client-id "c-1" :kind :receipt})
    st))

(defn- body [amount account]
  (pr-str {:source-doc "d1"
           :lines [{:side :dr :account account :amount amount :currency "JPY"}
                   {:side :cr :account "cash" :amount amount :currency "JPY"}]}))

;; ---------------------------------------------------------------------------
;; the bug
;; ---------------------------------------------------------------------------

(deftest submitting-the-same-entry-twice-posts-once
  (testing "a carrier that retries is normal; before this it doubled the books"
    (let [st (seeded)]
      (dotimes [_ 3] (e/draft-entry-core! st :ephemeral allow did (body 5000 "supplies")))
      (let [ps (store/postings-of st "c-1")]
        (is (= 1 (count ps)))
        (is (= {:debit 5000 :credit 0 :balance 5000}
               (get (tb/balances ps) ["supplies" "JPY"]))
            "the trial balance is the thing that was wrong, so it is what is asserted")))))

(deftest two-different-entries-citing-one-receipt-do-not-collide
  (testing "the same defect from the other side: keying on the document made
            a second, genuinely different entry overwrite or merge with the
            first"
    (let [st (seeded)]
      (e/draft-entry-core! st :ephemeral allow did (body 5000 "supplies"))
      (e/draft-entry-core! st :ephemeral allow did (body 3000 "utilities"))
      (let [ps (store/postings-of st "c-1")]
        (is (= 2 (count ps)))
        (is (= 2 (count (distinct (map :ledger/posting ps)))))
        (is (= 5000 (:balance (get (tb/balances ps) ["supplies" "JPY"]))))
        (is (= 3000 (:balance (get (tb/balances ps) ["utilities" "JPY"]))))))))

;; ---------------------------------------------------------------------------
;; the id
;; ---------------------------------------------------------------------------

(deftest identical-content-gets-an-identical-id
  (let [ls [{:side :dr :account "a" :amount 1 :currency "JPY"}
            {:side :cr :account "b" :amount 1 :currency "JPY"}]]
    (is (= (posting/content-id "d1" ls) (posting/content-id "d1" ls)))
    (testing "line order does not change identity — the same entry written
              the other way round is the same entry"
      (is (= (posting/content-id "d1" ls) (posting/content-id "d1" (reverse ls)))))))

(deftest different-content-gets-a-different-id
  (let [base [{:side :dr :account "a" :amount 1 :currency "JPY"}
              {:side :cr :account "b" :amount 1 :currency "JPY"}]]
    (doseq [[what other]
            [[:amount   [{:side :dr :account "a" :amount 2 :currency "JPY"}
                         {:side :cr :account "b" :amount 2 :currency "JPY"}]]
             [:account  [{:side :dr :account "z" :amount 1 :currency "JPY"}
                         {:side :cr :account "b" :amount 1 :currency "JPY"}]]
             [:currency [{:side :dr :account "a" :amount 1 :currency "USD"}
                         {:side :cr :account "b" :amount 1 :currency "USD"}]]
             [:side     [{:side :cr :account "a" :amount 1 :currency "JPY"}
                         {:side :dr :account "b" :amount 1 :currency "JPY"}]]]]
      (is (not= (posting/content-id "d1" base) (posting/content-id "d1" other))
          (str what " must change the id")))
    (testing "and so does the source document"
      (is (not= (posting/content-id "d1" base) (posting/content-id "d2" base))))))

(deftest the-id-is-content-with-no-escape-hatch
  (testing "the previous form was `(or (:entry-id proposal) …)`, and that
            branch was DEAD -- the advisor builds a fixed map with no
            :entry-id, so nothing reaching the commit node ever carried one.
            It is removed rather than wired up: a caller-chosen id can name
            two different entries the same and would defeat the idempotency
            this exists for.

            A first draft of this test asserted `(some? (content-id \"d1\" []))`,
            which was true of almost any implementation and checked nothing.
            This one drives the actor and compares the id it actually stored."
    (let [st (seeded)
          lines [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
                 {:side :cr :account "cash" :amount 5000 :currency "JPY"}]]
      (e/draft-entry-core! st :ephemeral allow did (body 5000 "supplies"))
      (is (= (posting/content-id "d1" lines)
             (:ledger/posting (first (store/postings-of st "c-1"))))
          "the stored id is exactly the content id, not the document")
      (is (not= "d1" (:ledger/posting (first (store/postings-of st "c-1"))))
          "and specifically not the source document, which is what it was"))))

;; ---------------------------------------------------------------------------
;; what idempotency must NOT become
;; ---------------------------------------------------------------------------

(deftest a-committed-posting-is-never-replaced
  (testing "idempotent means the second write is a no-op, not an overwrite —
            a posting that changed under a stable id would be an edit to an
            append-only ledger"
    (let [st (seeded)
          p1 (posting/project "fixed-id" [{:side :dr :account "a" :amount 1 :currency "JPY"}
                                          {:side :cr :account "b" :amount 1 :currency "JPY"}])
          p2 (posting/project "fixed-id" [{:side :dr :account "a" :amount 999 :currency "JPY"}
                                          {:side :cr :account "b" :amount 999 :currency "JPY"}])]
      (store/commit-posting! st "c-1" p1)
      (store/commit-posting! st "c-1" p2)
      (let [ps (store/postings-of st "c-1")]
        (is (= 1 (count ps)))
        (is (= 1 (:ledger/amount (first (:ledger/entries (first ps)))))
            "the first write stands; the second is dropped, not applied")))))

(deftest idempotency-is-per-client
  (testing "two clients may legitimately hold a posting with the same id"
    (let [st (seeded)
          _ (store/register-client! st {:client-id "c-2" :name "Taro's Garage"})
          p (posting/project "same-id" [{:side :dr :account "a" :amount 1 :currency "JPY"}
                                        {:side :cr :account "b" :amount 1 :currency "JPY"}])]
      (store/commit-posting! st "c-1" p)
      (store/commit-posting! st "c-2" p)
      (is (= 1 (count (store/postings-of st "c-1"))))
      (is (= 1 (count (store/postings-of st "c-2")))))))

(deftest the-ledger-still-records-every-submission
  (testing "the POSTING is deduplicated; the audit trail is not. A retry is a
            thing that happened and an append-only ledger that hid it would
            be an audit trail of the wrong thing."
    (let [st (seeded)]
      (dotimes [_ 3] (e/draft-entry-core! st :ephemeral allow did (body 5000 "supplies")))
      (is (= 1 (count (store/postings-of st "c-1"))))
      (is (= 3 (count (filter #(= :commit (:disposition %)) (store/ledger st))))
          "three submissions, three ledger facts, one posting"))))
