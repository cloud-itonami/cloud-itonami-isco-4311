(ns bookkeeping.ledger-test
  "An approved entry lands, and the ledger can be read back.

  Two things are pinned here that were separately missing:

  1. **The commit path posts.** `bookkeeping.posting` existed, was tested,
     and was called by nothing but its own tests — the projection was
     written and never invoked. `posting-is-reachable-from-the-actor` is the
     test that would have caught that, and it is deliberately phrased about
     the ACTOR rather than the projection.

  2. **Something reads the postings back.** Until `bookkeeping.trial-balance`
     there was no way to ask what an account's balance was, which is the one
     question a ledger exists to answer."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.actor :as actor]
            [bookkeeping.posting :as posting]
            [bookkeeping.trial-balance :as tb]))

(defn- fresh []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "c" :name "Hanako's Bakery"})
    (store/register-source-doc! st {:doc-id "d1" :client-id "c" :kind :receipt})
    (store/register-source-doc! st {:doc-id "d2" :client-id "c" :kind :receipt})
    st))

(defn- lines [dr-acct cr-acct amount currency]
  [{:side :dr :account dr-acct :amount amount :currency currency}
   {:side :cr :account cr-acct :amount amount :currency currency}])

(defn- commit! [st doc dr cr amount currency]
  (actor/run-request! (actor/build-graph {:store st})
                      {:client-id "c" :op :draft-entry :stake :low
                       :source-doc doc :lines (lines dr cr amount currency)}
                      {}
                      (str "t-" doc "-" currency)))

;; ---------------------------------------------------------------------------
;; 1. the commit path actually posts
;; ---------------------------------------------------------------------------

(deftest posting-is-reachable-from-the-actor
  (testing "the projection was written, tested, and invoked by nothing but
            its own tests — this asserts the ACTOR reaches it"
    (let [st (fresh)]
      (commit! st "d1" "supplies" "cash" 5000 "JPY")
      (let [ps (store/postings-of st "c")]
        (is (= 1 (count ps)))
        (is (true? (:ledger/balanced? (first ps))))
        (is (= 2 (count (:ledger/entries (first ps)))))))))

(deftest the-ledger-records-whether-a-posting-was-produced
  (testing "an entry that produced none must be auditable, not invisible"
    (let [st (fresh)]
      (commit! st "d1" "supplies" "cash" 5000 "JPY")
      (let [facts (filter #(= :commit (:disposition %)) (store/ledger st))]
        (is (seq facts))
        (is (every? #(contains? % :posting) facts))
        (is (some? (:posting (last facts))))))))

(deftest a-held-entry-posts-nothing
  (testing "the governor refuses it, so nothing may land"
    (let [st (fresh)]
      (actor/run-request! (actor/build-graph {:store st})
                          {:client-id "c" :op :draft-entry :stake :low
                           :source-doc "d1"
                           ;; unbalanced across currencies — the bug this
                           ;; plane shipped until 2026-08-17
                           :lines [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
                                   {:side :cr :account "cash" :amount 5000 :currency "USD"}]}
                          {} "t-held")
      (is (empty? (store/postings-of st "c"))))))

(deftest a-non-journal-op-posts-nothing
  (testing "the actor must not manufacture a posting to look complete"
    (let [st (fresh)]
      (actor/run-request! (actor/build-graph {:store st})
                          {:client-id "c" :op :reconcile :stake :low}
                          {} "t-rec")
      (is (empty? (store/postings-of st "c"))))))

;; ---------------------------------------------------------------------------
;; 2. the trial balance reads it back
;; ---------------------------------------------------------------------------

(deftest a-committed-entry-shows-up-in-the-balances
  (let [st (fresh)]
    (commit! st "d1" "supplies" "cash" 5000 "JPY")
    (let [b (tb/balances (store/postings-of st "c"))]
      (is (= {:debit 5000 :credit 0 :balance 5000} (get b ["supplies" "JPY"])))
      (is (= {:debit 0 :credit 5000 :balance -5000} (get b ["cash" "JPY"]))))))

(deftest two-entries-on-one-account-accumulate
  (let [st (fresh)]
    (commit! st "d1" "supplies" "cash" 5000 "JPY")
    (commit! st "d2" "supplies" "cash" 3000 "JPY")
    (let [ps (store/postings-of st "c")
          b (tb/balances ps)]
      (is (= 2 (count ps)))
      (is (= 8000 (:balance (get b ["supplies" "JPY"]))))
      (is (= -8000 (:balance (get b ["cash" "JPY"]))))
      (is (tb/balanced? ps)))))

(deftest balances-are-keyed-by-account-AND-currency
  (testing "aggregating by account alone would reintroduce the currency bug
            one layer up, where a netting-to-zero total hides it"
    (let [ps [(posting/project "a" (lines "cash" "income" 100 "JPY"))
              (posting/project "b" (lines "cash" "income" 100 "USD"))]
          b (tb/balances ps)]
      (is (= 4 (count b)))
      (is (= 100 (:debit (get b ["cash" "JPY"]))))
      (is (= 100 (:debit (get b ["cash" "USD"]))))
      (is (nil? (get b ["cash" nil]))))))

(deftest an-empty-ledger-is-not-a-balanced-one
  (testing "zero equals zero, but an empty ledger has not been shown to
            balance — it has been shown to be empty"
    (is (not (tb/balanced? [])))
    (is (= 0 (:posting-count (tb/totals []))))
    (is (empty? (tb/out-of-balance [])))))

(deftest out-of-balance-names-the-currency
  (let [ps [{:ledger/posting "x"
             :ledger/entries [{:ledger/account "a" :ledger/side :debit
                               :ledger/amount 100 :ledger/currency "JPY"}
                              {:ledger/account "b" :ledger/side :credit
                               :ledger/amount 90 :ledger/currency "JPY"}]}]]
    (is (not (tb/balanced? ps)))
    (is (= {:debit 100 :credit 90 :difference 10}
           (get (tb/out-of-balance ps) "JPY")))))

(deftest report-carries-both-the-boolean-and-the-detail
  (let [st (fresh)]
    (commit! st "d1" "supplies" "cash" 5000 "JPY")
    (let [r (tb/report (store/postings-of st "c"))]
      (is (true? (:trial-balance/balanced? r)))
      (is (empty? (:trial-balance/out-of-balance r)))
      (is (= 1 (get-in r [:trial-balance/totals :posting-count])))
      (is (= 2 (count (:trial-balance/balances r)))))))

(deftest a-clients-postings-are-that-clients-only
  (testing "knowing an id must not be enough to read another party's ledger"
    (let [st (fresh)]
      (store/register-client! st {:client-id "other" :name "Taro's Garage"})
      (commit! st "d1" "supplies" "cash" 5000 "JPY")
      (is (= 1 (count (store/postings-of st "c"))))
      (is (empty? (store/postings-of st "other"))))))

(deftest a-non-journal-op-with-lines-still-posts-nothing
  (testing "the :draft-entry guard is load-bearing, and this is the test that
            shows it. `a-non-journal-op-posts-nothing` does NOT: :reconcile
            carries no :lines, so the projection refuses on its own and
            removing the guard changes nothing observable. Measured — that
            mutation survived until this case existed. An op that is not a
            journal entry but DOES carry lines is the discriminating one."
    (let [st (fresh)]
      (actor/run-request! (actor/build-graph {:store st})
                          {:client-id "c" :op :reconcile :stake :low
                           :source-doc "d1"
                           :lines (lines "supplies" "cash" 5000 "JPY")}
                          {} "t-rec-lines")
      (is (empty? (store/postings-of st "c"))
          "only a journal entry may become a ledger posting"))))
