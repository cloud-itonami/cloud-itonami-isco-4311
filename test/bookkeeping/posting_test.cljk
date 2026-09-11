(ns bookkeeping.posting-test
  "The ledger seam, and the bug that delegating to it exposed.

  Before `bookkeeping.posting`, an approved journal entry had no
  destination: `:lines` was a private shape read by one function in the
  governor and by nothing else. It now projects onto `kotoba.banking`, the
  double-entry contract `kotoba-lang/kakeibo` was already using.

  The first test below is the reason this is a fix and not a refactor."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.banking :as banking]
            [bookkeeping.store :as store]
            [bookkeeping.posting :as posting]
            [bookkeeping.governor :as governor]))

;; ---------------------------------------------------------------------------
;; The bug
;; ---------------------------------------------------------------------------

(def ^:private cross-currency
  [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
   {:side :cr :account "cash"     :amount 5000 :currency "USD"}])

(deftest an-entry-across-two-currencies-does-not-balance
  (testing "the check this replaced summed :amount and ignored :currency, so
            5000 JPY debit against 5000 USD credit balanced — in exactly the
            direction the invariant exists to catch"
    (is (not (posting/balanced? cross-currency)))))

(deftest the-governor-now-holds-a-cross-currency-entry
  (let [st (store/mem-store)
        _  (store/register-client! st {:client-id "c" :name "Hanako's Bakery"})
        _  (store/register-source-doc! st {:doc-id "d" :client-id "c" :kind :receipt})
        v (governor/check {:client-id "c"} {}
                          {:op :draft-entry :effect :propose :source-doc "d"
                           :lines cross-currency :confidence 0.95}
                          st)]
    (is (:hard? v))
    (is (some #(= :unbalanced-entry (:rule %)) (:violations v)))
    (is (not (:escalate? v))
        "a human cannot approve their way past bad arithmetic — including
         arithmetic that was wrong because nobody compared currencies")))

;; ---------------------------------------------------------------------------
;; The projection
;; ---------------------------------------------------------------------------

(def ^:private balanced-jpy
  [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
   {:side :cr :account "cash"     :amount 5000 :currency "JPY"}])

(deftest lines-project-onto-banking-entries
  (let [es (posting/entries balanced-jpy :ref "je-1")]
    (is (= 2 (count es)))
    (is (= [:debit :credit] (mapv :ledger/side es))
        ":dr/:cr is this actor's spelling; the ledger's is :debit/:credit")
    (is (every? #(= "je-1" (:ledger/ref %)) es)
        "every entry carries the journal entry it came from")
    (is (= ["supplies" "cash"] (mapv :ledger/account es)))
    (is (banking/balanced? es))))

(deftest a-line-that-cannot-be-represented-projects-to-nothing
  (testing "a partial projection is the dangerous outcome: it would balance,
            because the unrepresentable line is missing from BOTH sides"
    (let [bad [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
               {:side :sideways :account "cash" :amount 5000 :currency "JPY"}]]
      (is (nil? (posting/entries bad)))
      (is (not (posting/balanced? bad))
          "and it must not come back balanced by having vanished")
      (is (nil? (posting/project "je-x" bad))))))

(deftest no-lines-is-not-a-balanced-entry
  (is (nil? (posting/entries [])))
  (is (not (posting/balanced? []))
      "an empty entry trivially satisfies debits=credits; it is still not a
       journal entry, and calling it balanced is the empty-scan mistake"))

(deftest lines-without-a-currency-behave-as-before
  (testing "they all group under nil together — a single-currency ledger
            sees no change from this seam"
    (is (posting/balanced? [{:side :dr :account "a" :amount 100}
                            {:side :cr :account "b" :amount 100}]))
    (is (not (posting/balanced? [{:side :dr :account "a" :amount 100}
                                 {:side :cr :account "b" :amount 90}])))))

(deftest project-produces-a-banking-posting
  (let [p (posting/project "je-1" balanced-jpy :memo "supplies purchase")]
    (is (= "je-1" (:ledger/posting p)))
    (is (true? (:ledger/balanced? p)))
    (is (= "supplies purchase" (:ledger/memo p)))
    (is (not (contains? p :ledger/unbalanced)))))

(deftest project-does-not-hide-an-unbalanced-posting
  (testing "banking marks it :ledger/unbalanced so a governor can refuse it;
            dropping it here would hide a refusal that must stay visible"
    (let [p (posting/project "je-2" cross-currency)]
      (is (some? p))
      (is (false? (:ledger/balanced? p)))
      (is (true? (:ledger/unbalanced p))))))
