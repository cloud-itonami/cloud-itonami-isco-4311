(ns bookkeeping.motochou-test
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.posting :as posting]
            [bookkeeping.motochou :as mc]
            [bookkeeping.store]
            [bookkeeping.edge.endpoints]))

(def ^:private chart
  {"cash" {:type :asset} "supplies" {:type :expense} "sales" {:type :revenue}})

(defn- dr [a n c] {:side :dr :account a :amount n :currency c})
(defn- cr [a n c] {:side :cr :account a :amount n :currency c})

;; committed in this order, and the order is load-bearing
(def ^:private books
  [(posting/project "j1" [(dr "cash" 1000 "JPY") (cr "sales" 1000 "JPY")])
   (posting/project "j2" [(dr "supplies" 600 "JPY") (cr "cash" 600 "JPY")])
   (posting/project "j3" [(dr "supplies" 200 "JPY") (cr "cash" 200 "JPY")])])

;; ---------------------------------------------------------------------------
;; order is the whole point
;; ---------------------------------------------------------------------------

(deftest the-running-balance-follows-commit-order
  (testing "a 元帳 whose lines are not in committed order is a set with a
            running total drawn on it, and the total is then meaningless"
    (let [jpy (get-in (mc/account chart books "cash") [:motochou/by-currency "JPY"])]
      (is (= ["j1" "j2" "j3"] (mapv :posting (:lines jpy))))
      (is (= [1000 400 200] (mapv :balance (:lines jpy))))
      (is (= 200 (:closing jpy))))))

(deftest the-namespace-does-not-re-sort
  (testing "store/postings-of returns commit order and both backends are held
            to it; re-sorting here would silently substitute a different
            order for the one that was enforced"
    (let [reordered (reverse books)
          jpy (get-in (mc/account chart reordered "cash") [:motochou/by-currency "JPY"])]
      (is (= ["j3" "j2" "j1"] (mapv :posting (:lines jpy)))
          "given a different order, it reports that order — it does not fix it")
      (is (= [-200 -800 200] (mapv :balance (:lines jpy)))
          "and the running balance follows the order it was given"))))

(deftest a-debit-raises-and-a-credit-lowers
  (let [jpy (get-in (mc/account chart books "supplies") [:motochou/by-currency "JPY"])]
    (is (= [600 800] (mapv :balance (:lines jpy))))
    (is (= 800 (:closing jpy))))
  (let [jpy (get-in (mc/account chart books "sales") [:motochou/by-currency "JPY"])]
    (is (= [-1000] (mapv :balance (:lines jpy)))
        "debit-positive: this is the working, not the statement")))

;; ---------------------------------------------------------------------------
;; per currency
;; ---------------------------------------------------------------------------

(deftest currencies-get-separate-running-balances
  (testing "one running total across currencies produces a column that looks
            exactly like a ledger and means nothing — the failure this actor
            shipped once at the entry level"
    (let [mixed (conj books (posting/project "j4" [(dr "cash" 50 "USD")
                                                   (cr "sales" 50 "USD")]))
          by (:motochou/by-currency (mc/account chart mixed "cash"))]
      (is (= #{"JPY" "USD"} (set (keys by))))
      (is (= 200 (:closing (get by "JPY"))))
      (is (= 50 (:closing (get by "USD"))))
      (is (= 1 (:entry-count (get by "USD")))))))

;; ---------------------------------------------------------------------------
;; two ways to be empty
;; ---------------------------------------------------------------------------

(deftest an-unknown-account-is-not-a-quiet-month
  (testing "a caller shown a blank page otherwise cannot tell a typo from an
            account that simply had no activity"
    (let [r (mc/account chart books "no-such-account")]
      (is (= :unknown-account (:motochou/coverage r)))
      (is (not (contains? r :motochou/by-currency))))
    (let [r (mc/account (assoc chart "quiet" {:type :asset}) books "quiet")]
      (is (= :ok (:motochou/coverage r)))
      (is (empty? (:motochou/by-currency r))
          "known, and nothing happened to it"))))

(deftest an-account-outside-the-chart-still-shows-in-the-activity-list
  (testing "an account that received a posting while absent from the chart is
            what statements reports as unclassified; it must not become
            invisible here just because `account` refuses to open it"
    (let [stray (conj books (posting/project "j9" [(dr "mystery" 1 "JPY")
                                                   (cr "cash" 1 "JPY")]))]
      (is (= :unknown-account (:motochou/coverage (mc/account chart stray "mystery"))))
      (is (some #(= ["mystery" "JPY"] %) (mc/accounts-with-activity stray))))))

;; ---------------------------------------------------------------------------
;; 仕訳帳
;; ---------------------------------------------------------------------------

(deftest the-journal-is-every-posting-in-order
  (let [j (mc/journal books)]
    (is (= 3 (:motochou/entry-count j)))
    (is (= ["j1" "j2" "j3"] (mapv :posting (:motochou/entries j))))
    (is (= [2 2 2] (mapv #(count (:lines %)) (:motochou/entries j))))))

(deftest the-journal-reports-the-balance-flag-it-was-given
  (testing "a second opinion computed here could disagree with the one the
            governor actually enforced"
    (let [bad (posting/project "jx" [(dr "a" 1 "JPY") (cr "b" 2 "JPY")])
          j (mc/journal [bad])]
      (is (false? (:balanced? (first (:motochou/entries j)))))
      (is (= (:ledger/balanced? bad) (:balanced? (first (:motochou/entries j))))))))

(deftest an-empty-book-is-reported-as-empty
  (is (= 0 (:motochou/entry-count (mc/journal []))))
  (is (empty? (mc/accounts-with-activity []))))

;; ---------------------------------------------------------------------------
;; reachable
;; ---------------------------------------------------------------------------

(deftest the-books-are-reachable-from-the-endpoints
  (let [st (bookkeeping.store/mem-store)
        did "did:key:z6MkAAA"
        allow (bookkeeping.edge.endpoints/parse-allowlist (str did "=c-1"))]
    (bookkeeping.store/register-client! st {:client-id "c-1" :name "X"})
    (bookkeeping.store/register-chart! st "c-1" chart)
    (doseq [p books] (bookkeeping.store/commit-posting! st "c-1" p))
    (testing "仕訳帳"
      (let [r (bookkeeping.edge.endpoints/journal-core st allow did)]
        (is (= 200 (:status r)))
        (is (= 3 (get-in r [:body :entry-count])))
        (is (= ["j1" "j2" "j3"] (mapv :posting (get-in r [:body :entries]))))))
    (testing "総勘定元帳"
      (let [r (bookkeeping.edge.endpoints/ledger-core st allow did "cash")]
        (is (= 200 (:status r)))
        (is (= 200 (get-in r [:body :by-currency "JPY" :closing])))))
    (testing "an unknown account is 404 and says what activity exists"
      (let [r (bookkeeping.edge.endpoints/ledger-core st allow did "nope")]
        (is (= 404 (:status r)))
        (is (some #(= "cash" (:account %)) (get-in r [:body :accounts-with-activity])))))
    (testing "and the two gates hold here too"
      (is (= 503 (:status (bookkeeping.edge.endpoints/journal-core st nil did))))
      (is (= 403 (:status (bookkeeping.edge.endpoints/ledger-core st allow "did:key:zZ" "cash")))))))
