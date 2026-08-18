(ns bookkeeping.kensaku-test
  "検索機能 — 規則第五条第五項第一号ハ, and the two 記録項目 that had to exist
  before a search over them could mean anything.

  The ordering of this file is the ordering of the work: the fields first,
  then （１）（２）（３） one at a time, then what the deployment may claim.
  A search that ran over `:source-doc` and `:lines` alone would have passed
  its own tests and satisfied none of the provision."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bookkeeping.posting :as posting]
            [bookkeeping.motochou :as mc]
            [bookkeeping.kensaku :as k]
            [bookkeeping.store :as store]
            [kotoba.taxlaw :as taxlaw]
            [bookkeeping.edge.endpoints :as e]))

(defn- dr [a n c] {:side :dr :account a :amount n :currency c})
(defn- cr [a n c] {:side :cr :account a :amount n :currency c})

(def ^:private books
  "Three entries: two fully recorded, one carrying neither 取引年月日 nor
  取引先 — the shape every entry in this actor had until 2026-08-18."
  [(posting/project "j1" [(dr "supplies" 5000 "JPY") (cr "cash" 5000 "JPY")]
                    :transaction-date "2026-01-15" :counterparty "Alpha Paper")
   (posting/project "j2" [(dr "rent" 90000 "JPY") (cr "cash" 90000 "JPY")]
                    :transaction-date "2026-06-30" :counterparty "Beta Realty")
   (posting/project "j3" [(dr "misc" 100 "JPY") (cr "cash" 100 "JPY")])])

(defn- ids [r] (mapv :posting (:kensaku/results r)))

;; ===========================================================================
;; 1. the two 記録項目 that did not exist
;; ===========================================================================

(deftest a-date-that-is-not-a-date-is-refused
  (testing "ISO-8601 YYYY-MM-DD, because ハ（２） needs a RANGE and in this
            form lexicographic order is chronological order"
    (is (posting/valid-transaction-date? "2026-01-15"))
    (is (posting/valid-transaction-date? "2024-02-29") "2024 is a leap year")
    (is (not (posting/valid-transaction-date? "2026-02-29")) "2026 is not")
    (is (not (posting/valid-transaction-date? "2026-02-30")))
    (is (not (posting/valid-transaction-date? "2026-13-01")))
    (is (not (posting/valid-transaction-date? "2026-00-10")))
    (is (not (posting/valid-transaction-date? "2026-04-31")) "April has 30 days")
    (is (posting/valid-transaction-date? "2000-02-29") "divisible by 400")
    (is (not (posting/valid-transaction-date? "1900-02-29")) "divisible by 100, not 400")
    (testing "and a shape that would sort as text while looking like a date"
      (is (not (posting/valid-transaction-date? "2026/01/15")))
      (is (not (posting/valid-transaction-date? "26-01-15")))
      (is (not (posting/valid-transaction-date? "2026-1-5"))))
    (is (not (posting/valid-transaction-date? nil)))
    (is (not (posting/valid-transaction-date? 20260115)))))

(deftest a-blank-counterparty-is-recorded-as-absent-never-as-a-name
  (testing "a 取引先 of \"\" is indistinguishable from a real one in an
            equality search, so it would match entries that never had one"
    (is (nil? (posting/normalize-counterparty "")))
    (is (nil? (posting/normalize-counterparty "   ")))
    (is (nil? (posting/normalize-counterparty "\t\n")))
    (is (nil? (posting/normalize-counterparty nil)))
    (is (= "Alpha Paper" (posting/normalize-counterparty "Alpha Paper")))
    (testing "and `project` is the floor under callers that are not the edge"
      (let [p (posting/project "x" [(dr "a" 1 "JPY") (cr "b" 1 "JPY")]
                               :counterparty "   ")]
        (is (nil? (:bookkeeping/counterparty p)))))))

(deftest the-keys-are-always-present-so-absent-is-readable
  (testing "omitting the key would make `this entry has no 取引先` and
            `nobody recorded one` the same read"
    (let [p (posting/project "x" [(dr "a" 1 "JPY") (cr "b" 1 "JPY")])]
      (is (contains? p :bookkeeping/transaction-date))
      (is (contains? p :bookkeeping/counterparty))
      (is (nil? (:bookkeeping/transaction-date p)))
      (is (nil? (:bookkeeping/counterparty p))))))

(deftest the-record-items-are-part-of-the-entry-id
  (testing "two entries citing one monthly statement, on different days, to
            different suppliers, with the same accounts and amount are
            DIFFERENT entries — before these fields they collided under one
            id and the second was dropped as a duplicate"
    (let [ls [(dr "supplies" 5000 "JPY") (cr "cash" 5000 "JPY")]
          base (posting/content-id "stmt" ls)]
      (is (not= base (posting/content-id "stmt" ls :transaction-date "2026-01-15")))
      (is (not= base (posting/content-id "stmt" ls :counterparty "Alpha")))
      (is (not= (posting/content-id "stmt" ls :transaction-date "2026-01-15")
                (posting/content-id "stmt" ls :transaction-date "2026-01-16")))
      (is (not= (posting/content-id "stmt" ls :counterparty "Alpha")
                (posting/content-id "stmt" ls :counterparty "Beta"))))
    (testing "and a blank 取引先 hashes as absent, not as a third value"
      (let [ls [(dr "a" 1 "JPY") (cr "b" 1 "JPY")]]
        (is (= (posting/content-id "d" ls)
               (posting/content-id "d" ls :counterparty "  ")))))))

(deftest the-books-carry-the-record-items
  (testing "仕訳帳"
    (let [entries (:motochou/entries (mc/journal books))]
      (is (= ["2026-01-15" "2026-06-30" nil] (mapv :transaction-date entries)))
      (is (= ["Alpha Paper" "Beta Realty" nil] (mapv :counterparty entries)))
      (is (every? #(contains? % :counterparty) entries)
          "present even when nil — that is the whole point")))
  (testing "総勘定元帳"
    (let [chart {"cash" {:type :asset}}
          lines (get-in (mc/account chart books "cash") [:motochou/by-currency "JPY" :lines])]
      (is (= ["2026-01-15" "2026-06-30" nil] (mapv :transaction-date lines)))
      (is (= ["Alpha Paper" "Beta Realty" nil] (mapv :counterparty lines))))))

;; ===========================================================================
;; 2. 取引金額 — what the amount of a multi-line entry IS
;; ===========================================================================

(deftest the-amount-is-the-debit-side-per-currency
  (testing "the debit total, because in a balanced entry it equals the credit
            total and so is the amount of the transaction, not of one leg"
    (let [p (posting/project "multi" [(dr "supplies" 3000 "JPY")
                                      (dr "tax" 300 "JPY")
                                      (cr "cash" 3300 "JPY")])]
      (is (= {"JPY" 3300} (k/debit-totals p))
          "both debit lines, and NOT the 6600 a both-sides sum would give"))))

(deftest amounts-are-never-summed-across-currencies
  (testing "this actor shipped that bug once — 5000 JPY against 5000 USD
            balanced — and a 取引金額 of 5050 would match range queries and
            mean nothing"
    (let [p (posting/project "fx" [(dr "supplies" 5000 "JPY")
                                   (dr "fees" 50 "USD")
                                   (cr "cash" 5000 "JPY")
                                   (cr "cash" 50 "USD")])]
      (is (= {"JPY" 5000 "USD" 50} (k/debit-totals p)))
      (testing "and each currency is separately searchable"
        (is (= ["fx"] (ids (k/search [p] {:amount 50}))))
        (is (= [["USD"]] (mapv :matched-currencies (:kensaku/results (k/search [p] {:amount 50})))))
        (is (= ["fx"] (ids (k/search [p] {:amount 5000}))))
        (is (empty? (ids (k/search [p] {:amount 5050})))
            "the cross-currency sum matches nothing, because it is not a thing")))))

(deftest an-entry-with-no-currency-is-still-searchable-by-amount
  (testing "lines with no :currency group under nil together, which
            `bookkeeping.posting` preserves for a single-currency ledger — an
            amount search must not drop them"
    (let [p (posting/project "nc" [{:side :dr :account "a" :amount 700}
                                   {:side :cr :account "b" :amount 700}])]
      (is (= {nil 700} (k/debit-totals p)))
      (is (= ["nc"] (ids (k/search [p] {:amount 700})))))))

;; ===========================================================================
;; 3. ハ（１） — each 記録項目 usable as a condition on its own
;; ===========================================================================

(deftest ha-1-transaction-date-alone
  (is (= ["j1"] (ids (k/search books {:transaction-date "2026-01-15"}))))
  (is (= ["j2"] (ids (k/search books {:transaction-date "2026-06-30"}))))
  (is (empty? (ids (k/search books {:transaction-date "2026-03-03"})))))

(deftest ha-1-amount-alone
  (is (= ["j1"] (ids (k/search books {:amount 5000}))))
  (is (= ["j3"] (ids (k/search books {:amount 100})))))

(deftest ha-1-counterparty-alone
  (is (= ["j2"] (ids (k/search books {:counterparty "Beta Realty"}))))
  (testing "exact, not substring — 取引先 is recorded verbatim and matching
            loosely would merge two parties whose names share a prefix"
    (is (empty? (ids (k/search books {:counterparty "Beta"}))))))

(deftest an-entry-lacking-a-record-item-does-not-match-a-condition-on-it
  (testing "j3 has neither. It must not fall through as a match — an entry
            that cannot be searched by 取引先 is exactly what makes the books
            fail ハ, and hiding it in the results would hide the failure"
    (is (not (some #{"j3"} (ids (k/search books {:counterparty "Alpha Paper"})))))
    (is (not (some #{"j3"} (ids (k/search books {:transaction-date {:from "1900-01-01"}})))))
    (testing "while a condition on what it DOES carry finds it"
      (is (= ["j3"] (ids (k/search books {:amount 100})))))))

;; ===========================================================================
;; 4. ハ（２） — ranges on 日付 and 金額
;; ===========================================================================

(deftest ha-2-date-range-is-inclusive-at-both-ends
  (is (= ["j1"] (ids (k/search books {:transaction-date {:from "2026-01-01" :to "2026-03-31"}}))))
  (is (= ["j1" "j2"] (ids (k/search books {:transaction-date {:from "2026-01-01" :to "2026-12-31"}}))))
  (testing "the boundary dates themselves are in"
    (is (= ["j1"] (ids (k/search books {:transaction-date {:from "2026-01-15" :to "2026-01-15"}}))))
    (is (= ["j2"] (ids (k/search books {:transaction-date {:from "2026-06-30" :to "2026-12-31"}}))))
    (is (= ["j1"] (ids (k/search books {:transaction-date {:from "2025-01-01" :to "2026-01-15"}}))))))

(deftest ha-2-one-sided-date-ranges
  (is (= ["j2"] (ids (k/search books {:transaction-date {:from "2026-04-01"}}))))
  (is (= ["j1"] (ids (k/search books {:transaction-date {:to "2026-04-01"}})))))

(deftest ha-2-amount-range-and-one-sided
  (is (= ["j1"] (ids (k/search books {:amount {:from 1000 :to 10000}}))))
  (is (= ["j1" "j2"] (ids (k/search books {:amount {:from 1000}}))))
  (is (= ["j1" "j3"] (ids (k/search books {:amount {:to 5000}}))))
  (testing "inclusive at both ends here too"
    (is (= ["j1"] (ids (k/search books {:amount {:from 5000 :to 5000}}))))))

(deftest ha-2-does-not-extend-to-counterparty
  (testing "（２）grants ranges on 日付又は金額 only. Accepting a range on
            取引先 as an equality would answer a question nobody asked"
    (let [r (k/search books {:counterparty {:from "A" :to "Z"}})]
      (is (= :invalid-condition (:kensaku/coverage r)))
      (is (= [:range-not-permitted] (mapv :problem (:kensaku/problems r))))
      (is (not (contains? r :kensaku/results))))))

;; ===========================================================================
;; 5. ハ（３） — two or more combined
;; ===========================================================================

(deftest ha-3-combining-narrows-rather-than-widens
  (testing "AND. A search that ORed its conditions would pass every single
            condition test above and still be wrong"
    (is (= ["j1"] (ids (k/search books {:counterparty "Alpha Paper" :amount 5000}))))
    (is (empty? (ids (k/search books {:counterparty "Alpha Paper" :amount 90000})))
        "Alpha exists and 90000 exists — but not together")
    (is (empty? (ids (k/search books {:counterparty "Beta Realty"
                                      :transaction-date "2026-01-15"}))))))

(deftest ha-3-all-three-together
  (is (= ["j2"] (ids (k/search books {:transaction-date {:from "2026-01-01" :to "2026-12-31"}
                                      :amount {:from 50000}
                                      :counterparty "Beta Realty"}))))
  (is (empty? (ids (k/search books {:transaction-date {:from "2026-01-01" :to "2026-12-31"}
                                    :amount {:from 50000}
                                    :counterparty "Alpha Paper"})))))

;; ===========================================================================
;; 6. a search with no conditions must not silently return everything
;; ===========================================================================

(deftest no-conditions-is-its-own-answer-not-an-empty-filter
  (let [r (k/search books {})]
    (is (= :no-conditions (:kensaku/coverage r)))
    (is (not (contains? r :kensaku/results))
        "there is no empty list to misread, and no full list either")
    (is (= r (k/search books nil)))))

(deftest searched-count-is-reported-next-to-match-count
  (testing "zero matches out of zero entries examined and zero out of three
            are different answers"
    (let [r (k/search books {:counterparty "Nobody"})]
      (is (= 3 (:kensaku/searched-count r)))
      (is (= 0 (:kensaku/match-count r))))
    (let [r (k/search [] {:counterparty "Nobody"})]
      (is (= 0 (:kensaku/searched-count r)))
      (is (= 0 (:kensaku/match-count r))))))

(deftest the-applied-conditions-are-echoed-back
  (testing "with a repeated ?date= the caller gets ONE of the two, and this
            echo is the only way it learns which — a mutation blanking it
            survived the whole suite on 2026-08-18, which is what this test
            is here for"
    (let [c {:counterparty "Alpha Paper" :amount {:from 1000 :to 10000}}]
      (is (= c (:kensaku/conditions (k/search books c)))))))

(deftest a-result-row-carries-the-entry-not-just-its-id
  (testing "a search that returns a column of posting ids has answered
            `something matched` and nothing else"
    (let [row (first (:kensaku/results (k/search books {:counterparty "Alpha Paper"})))]
      (is (= "j1" (:posting row)))
      (is (= "2026-01-15" (:transaction-date row)))
      (is (= "Alpha Paper" (:counterparty row)))
      (is (= {"JPY" 5000} (:amount row)))
      (is (= [{:account "supplies" :side :debit :amount 5000 :currency "JPY"}
              {:account "cash" :side :credit :amount 5000 :currency "JPY"}]
             (:lines row))))))

(deftest a-condition-the-provision-does-not-define-is-refused
  (doseq [[label conditions problem]
          [["an item ハ（１） does not name" {:account "cash"} :unknown-condition]
           ["a date that is not a date" {:transaction-date "2026-02-30"} :not-a-date]
           ["an amount that is not a number" {:amount "5000"} :not-a-number]
           ["a blank counterparty" {:counterparty "  "} :not-a-counterparty]
           ["a range bound that is not a date" {:transaction-date {:from "yesterday"}} :not-a-value]
           ["a range with neither bound" {:amount {}} :empty-range]
           ["a range with a stray key" {:amount {:from 1 :step 2}} :unknown-range-key]
           ["a range that runs backwards"
            {:transaction-date {:from "2026-12-31" :to "2026-01-01"}} :reversed-range]]]
    (let [r (k/search books conditions)]
      (is (= :invalid-condition (:kensaku/coverage r)) label)
      (is (some #{problem} (map :problem (:kensaku/problems r))) label)
      (is (not (contains? r :kensaku/results)) label))))

;; ===========================================================================
;; 7. query parameters
;; ===========================================================================

(deftest query-parameters-become-typed-conditions
  (is (= {:transaction-date "2026-01-15"}
         (:kensaku/conditions (k/parse-query {"date" "2026-01-15"}))))
  (is (= {:transaction-date {:from "2026-01-01" :to "2026-03-31"}}
         (:kensaku/conditions (k/parse-query {"date-from" "2026-01-01"
                                              "date-to" "2026-03-31"}))))
  (is (= {:amount {:from 1000}} (:kensaku/conditions (k/parse-query {"amount-from" "1000"}))))
  (is (= {:amount 5000 :counterparty "Alpha Paper"}
         (:kensaku/conditions (k/parse-query {"amount" "5000" "counterparty" "Alpha Paper"}))))
  (is (= {} (:kensaku/conditions (k/parse-query {})))))

(deftest an-unrecognised-parameter-is-a-problem-not-a-silence
  (testing "?conterparty=x would otherwise become a search with no conditions,
            and the caller would be told it asked nothing when it asked
            something and was not heard"
    (let [r (k/parse-query {"conterparty" "x"})]
      (is (= [:unknown-parameter] (mapv :problem (:kensaku/problems r))))
      (is (not (contains? r :kensaku/conditions))))))

(deftest a-non-numeric-amount-parameter-is-a-problem
  (is (= [:not-a-number] (mapv :problem (:kensaku/problems (k/parse-query {"amount" "5,000"})))))
  (is (= [:not-a-number] (mapv :problem (:kensaku/problems (k/parse-query {"amount-to" "lots"}))))))

(deftest an-exact-condition-and-a-range-on-the-same-item-conflict
  (testing "two different questions; answering one silently gives a result
            set the caller cannot account for"
    (is (= [:conflicting-condition]
           (mapv :problem (:kensaku/problems (k/parse-query {"date" "2026-01-15"
                                                             "date-from" "2026-01-01"})))))))

;; ===========================================================================
;; 8. what the deployment can actually claim
;; ===========================================================================

(deftest the-probe-measures-the-search-rather-than-asserting-it
  (testing "`the function is defined` is a claim any file makes by containing
            a defn; `it returned exactly probe-b for a 5000-20000 range` is a
            measurement"
    (let [good (k/probe-search-function k/search)]
      (is (true? (:kensaku/ha-1 good)))
      (is (true? (:kensaku/ha-2 good)))
      (is (true? (:kensaku/ha-3 good)))
      (is (= 8 (:kensaku/probe-count good)) "an empty probe set cannot report a pass")))
  (testing "a search whose ranges silently degrade to equality fails ハ（２）
            and passes the rest — which is exactly the failure a hardcoded
            `true` would have hidden"
    (let [degraded (fn [ps conds]
                     (k/search ps (into {} (map (fn [[item c]]
                                                  [item (if (map? c) (or (:from c) (:to c)) c)]))
                                        conds)))
          r (k/probe-search-function degraded)]
      (is (true? (:kensaku/ha-1 r)))
      (is (false? (:kensaku/ha-2 r)))))
  (testing "and a search-fn that is not a function at all"
    (let [r (k/probe-search-function nil)]
      (is (false? (:kensaku/ha-1 r)))
      (is (false? (:kensaku/ha-2 r)))
      (is (false? (:kensaku/ha-3 r))))))

(deftest an-undeclared-election-is-not-a-pass
  (testing "ハ binds only a 保存義務者 claiming 法第八条第四項. The software
            cannot observe a tax election, so `nobody said` must be its own
            answer and must not be 適合"
    (let [r (k/conformance {:jurisdiction :jp :postings (vec (take 2 books)) :declared? nil})]
      (is (= :not-declared (:kensaku/status r)))
      (is (nil? (:kensaku/declared r)))
      (is (false? (k/conformant? {:postings (vec (take 2 books)) :declared? nil}))))
    (testing "and a missing key is the same as an explicit nil"
      (is (= :not-declared (:kensaku/status (k/conformance {:jurisdiction :jp :postings books})))))
    (testing "and anything that is not literally true or false is undeclared —
              a truthy string must not mean yes"
      (is (= :not-declared (:kensaku/status (k/conformance {:jurisdiction :jp :postings (vec (take 2 books))
                                                            :declared? "yes"}))))
      (is (= :not-declared (:kensaku/status (k/conformance {:jurisdiction :jp :postings (vec (take 2 books))
                                                            :declared? "no"})))))))

(deftest not-applicable-is-distinguishable-from-compliant
  (testing "ordinary preservation under 法第四条第一項 requires no search at
            all — nothing was found compliant, the requirement was found not
            to apply"
    (let [r (k/conformance {:jurisdiction :jp :postings books :declared? false})]
      (is (= :not-applicable (:kensaku/status r)))
      (is (false? (:kensaku/declared r)))
      (is (false? (k/conformant? {:postings books :declared? false}))))))

(deftest an-empty-book-has-not-been-shown-to-be-searchable
  (testing "the same reason trial-balance/balanced? is false for an empty set"
    (is (= :no-entries (:kensaku/status (k/conformance {:jurisdiction :jp :postings [] :declared? true}))))
    (is (false? (k/conformant? {:postings [] :declared? true})))))

(deftest entries-missing-a-record-item-are-counted-and-named
  (testing "`some entries` is not something an operator can act on"
    (let [r (k/conformance {:jurisdiction :jp :postings books :declared? true})]
      (is (= :non-conformant (:kensaku/status r)))
      (is (= 3 (:kensaku/entry-count r)))
      (is (= 1 (:kensaku/entries-missing-record-items r)))
      (is (= [{:posting "j3" :missing [:transaction-date :counterparty]}]
             (:kensaku/missing r)))
      (testing ":why is the sentence an operator acts on, and it names the count"
        (is (str/includes? (:kensaku/why r) "1 件")))
      (is (false? (k/conformant? {:postings books :declared? true}))))))

(deftest a-fully-recorded-book-with-a-declaration-is-conformant
  (let [full (vec (take 2 books))
        r (k/conformance {:jurisdiction :jp :postings full :declared? true})]
    (is (= :conformant (:kensaku/status r)))
    (is (= 0 (:kensaku/entries-missing-record-items r)))
    (is (true? (k/conformant? {:jurisdiction :jp :postings full :declared? true})))
    (testing "and it cites the provision and where the text came from"
      (is (= "電子帳簿保存法施行規則 第五条第五項第一号ハ" (:kensaku/provision r)))
      (is (str/includes? (:url (:kensaku/law-source r)) "410M50000040043"))
      (is (= "2026-08-18" (:retrieved (:kensaku/law-source r)))))))

(deftest a-broken-search-makes-the-books-non-conformant-even-when-fully-recorded
  (testing "the entries carry all three 記録項目 and the software still
            cannot search them — ハ is about the FUNCTION as well as the data"
    (let [full (vec (take 2 books))
          r (k/conformance {:jurisdiction :jp :postings full :declared? true :search-fn nil})]
      (is (= :non-conformant (:kensaku/status r)))
      (is (= 0 (:kensaku/entries-missing-record-items r))
          "and it does not blame the data for it"))))

(deftest an-entry-with-no-debit-line-has-no-searchable-amount
  (let [odd {:ledger/posting "credit-only"
             :bookkeeping/transaction-date "2026-01-01"
             :bookkeeping/counterparty "Someone"
             :ledger/entries [{:ledger/account "a" :ledger/side :credit
                               :ledger/amount 5 :ledger/currency "JPY"}]}]
    (is (= [:amount] (k/missing-record-items odd)))
    (is (= :non-conformant (:kensaku/status (k/conformance {:jurisdiction :jp :postings [odd] :declared? true}))))))

;; ===========================================================================
;; 9. the route
;; ===========================================================================

(def ^:private did-a "did:key:z6MkAAA")
(def ^:private did-b "did:key:z6MkBBB")
(def ^:private allow (e/parse-allowlist (str did-a "=c-1," did-b "=c-2")))

(defn- fresh
  ([] (fresh nil))
  ([declared?]
   (let [st (store/mem-store)]
     (store/register-client! st (cond-> {:client-id "c-1" :name "Hanako's Bakery"
                                         :jurisdiction :jp}
                                  (some? declared?) (assoc :yuryo-chobo-declared? declared?)))
     (store/register-client! st {:client-id "c-2" :name "Taro's Garage"})
     (store/register-source-doc! st {:doc-id "d1" :client-id "c-1" :kind :receipt})
     (doseq [p books] (store/commit-posting! st "c-1" p))
     st)))

(deftest the-search-route-carries-the-same-two-gates
  (is (= 503 (:status (e/search-core (fresh) nil did-a {"amount" "5000"}))))
  (is (= 403 (:status (e/search-core (fresh) allow "did:key:z6MkZZZ" {"amount" "5000"})))))

(deftest one-caller-cannot-search-anothers-book
  (let [st (fresh)]
    (is (= 1 (get-in (e/search-core st allow did-a {"amount" "5000"}) [:body :match-count])))
    (is (= 0 (get-in (e/search-core st allow did-b {"amount" "5000"}) [:body :match-count])))
    (is (= 0 (get-in (e/search-core st allow did-b {"amount" "5000"}) [:body :searched-count]))
        "and it sees no entries at all, not merely no matches")))

(deftest the-route-answers-all-three-sub-requirements
  (let [st (fresh)
        got (fn [q] (mapv :posting (get-in (e/search-core st allow did-a q) [:body :results])))]
    (testing "（１）"
      (is (= ["j1"] (got {"date" "2026-01-15"})))
      (is (= ["j2"] (got {"amount" "90000"})))
      (is (= ["j2"] (got {"counterparty" "Beta Realty"}))))
    (testing "（２）"
      (is (= ["j1"] (got {"date-from" "2026-01-01" "date-to" "2026-03-31"})))
      (is (= ["j1" "j2"] (got {"amount-from" "1000"}))))
    (testing "（３）"
      (is (= ["j2"] (got {"date-from" "2026-04-01" "counterparty" "Beta Realty"})))
      (is (= [] (got {"date-from" "2026-04-01" "counterparty" "Alpha Paper"}))))
    (testing "and the response echoes what it actually filtered on"
      (is (= {:transaction-date {:from "2026-01-01"}}
             (get-in (e/search-core st allow did-a {"date-from" "2026-01-01"})
                     [:body :conditions]))))))

(deftest a-conditionless-search-is-400-not-the-whole-book
  (let [r (e/search-core (fresh) allow did-a {})]
    (is (= 400 (:status r)))
    (is (false? (get-in r [:body :ok])))
    (is (not (contains? (:body r) :results))))
  (testing "and so is a condition the provision does not define"
    (let [r (e/search-core (fresh) allow did-a {"account" "cash"})]
      (is (= 400 (:status r)))
      (is (= [:unknown-parameter] (mapv :problem (get-in r [:body :problems])))))
    (let [r (e/search-core (fresh) allow did-a {"date" "2026-02-30"})]
      (is (= 400 (:status r)))
      (is (= [:not-a-date] (mapv :problem (get-in r [:body :problems])))))))

(deftest the-route-reports-what-the-deployment-may-claim
  (testing "undeclared, which is the default and is not a pass"
    (let [r (e/search-core (fresh) allow did-a {"amount" "5000"})]
      (is (= 200 (:status r)))
      (is (= :not-declared (get-in r [:body :conformance :kensaku/status])))))
  (testing "declared, and the books do not yet qualify — j3 has neither field"
    (let [r (e/search-core (fresh true) allow did-a {"amount" "5000"})]
      (is (= :non-conformant (get-in r [:body :conformance :kensaku/status])))
      (is (= 1 (get-in r [:body :conformance :kensaku/entries-missing-record-items])))))
  (testing "declared NOT claiming — the requirement does not bite, which is
            still not a pass"
    (let [r (e/search-core (fresh false) allow did-a {"amount" "5000"})]
      (is (= :not-applicable (get-in r [:body :conformance :kensaku/status]))))))

(deftest the-declaration-is-the-operators-never-the-callers
  (testing "a caller that could declare its own 優良帳簿 election could
            declare compliance into existence — the same rule that keeps a
            caller from naming its own client"
    (let [st (fresh)
          r (e/search-core st allow did-a {"amount" "5000"
                                           "yuryo-chobo-declared" "true"})]
      (is (= 400 (:status r)) "it is not even a search parameter")
      (is (= [:unknown-parameter] (mapv :problem (get-in r [:body :problems])))))))

;; ===========================================================================
;; 10. the two 記録項目 through the whole actor, end to end
;; ===========================================================================

(defn- seeded []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "c-1" :name "Hanako's Bakery"
                                :jurisdiction :jp
                                :yuryo-chobo-declared? true})
    (store/register-source-doc! st {:doc-id "d1" :client-id "c-1" :kind :receipt})
    st))

(defn- body [& {:keys [date cp]}]
  (pr-str (cond-> {:source-doc "d1"
                   :lines [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
                           {:side :cr :account "cash" :amount 5000 :currency "JPY"}]}
            date (assoc :transaction-date date)
            cp (assoc :counterparty cp))))

(deftest an-entry-without-the-record-items-is-still-accepted
  (testing "ハ binds only a 保存義務者 claiming 優良帳簿, and 減価償却費 has no
            取引先 to record — requiring the field would get it invented"
    (let [r (e/draft-entry-core! (seeded) :ephemeral allow did-a (body))]
      (is (= 200 (:status r)))
      (is (true? (get-in r [:body :ok]))))))

(deftest an-entry-with-them-carries-them-to-the-store-and-back-out
  (let [st (seeded)
        _ (e/draft-entry-core! st :ephemeral allow did-a
                               (body :date "2026-03-01" :cp "Alpha Paper"))
        p (first (store/postings-of st "c-1"))]
    (is (= "2026-03-01" (:bookkeeping/transaction-date p)))
    (is (= "Alpha Paper" (:bookkeeping/counterparty p)))
    (testing "and it is findable by all three"
      (is (= 1 (get-in (e/search-core st allow did-a {"date" "2026-03-01"}) [:body :match-count])))
      (is (= 1 (get-in (e/search-core st allow did-a {"counterparty" "Alpha Paper"}) [:body :match-count])))
      (is (= 1 (get-in (e/search-core st allow did-a {"amount-from" "4000" "amount-to" "6000"})
                       [:body :match-count]))))
    (testing "and the books now qualify"
      (is (= :conformant (get-in (e/search-core st allow did-a {"amount" "5000"})
                                 [:body :conformance :kensaku/status]))))))

(deftest a-malformed-record-item-is-a-400-not-a-guess
  (doseq [[label b] [["a date that is not a date" (body :date "2026-02-30")]
                     ["a date in another format" (body :date "2026/03/01")]
                     ["a date that is not a string" (pr-str {:source-doc "d1" :transaction-date 20260301
                                                             :lines [{:side :dr :account "a" :amount 1}
                                                                     {:side :cr :account "b" :amount 1}]})]
                     ["a blank counterparty" (body :cp "   ")]
                     ["an empty counterparty" (body :cp "")]
                     ["a counterparty that is not a string" (body :cp 42)]]]
    (is (nil? (e/parse-entry-body b)) label)
    (is (= 400 (:status (e/draft-entry-core! (seeded) :ephemeral allow did-a b))) label)))

(deftest two-entries-on-one-document-no-longer-collide
  (testing "same receipt, same accounts, same amount, different day and
            different supplier. Before these fields there was nothing to tell
            them apart and the second was dropped as a duplicate"
    (let [st (seeded)
          a (e/draft-entry-core! st :ephemeral allow did-a
                                 (body :date "2026-03-01" :cp "Alpha Paper"))
          b (e/draft-entry-core! st :ephemeral allow did-a
                                 (body :date "2026-03-02" :cp "Beta Realty"))]
      (is (false? (get-in a [:body :duplicate?])))
      (is (false? (get-in b [:body :duplicate?])) "not a duplicate — a different entry")
      (is (= 2 (count (store/postings-of st "c-1")))))
    (testing "the day alone is enough to tell them apart"
      (let [st (seeded)]
        (e/draft-entry-core! st :ephemeral allow did-a (body :date "2026-03-01" :cp "Alpha Paper"))
        (e/draft-entry-core! st :ephemeral allow did-a (body :date "2026-03-02" :cp "Alpha Paper"))
        (is (= 2 (count (store/postings-of st "c-1"))))))
    (testing "and the supplier alone"
      (let [st (seeded)]
        (e/draft-entry-core! st :ephemeral allow did-a (body :date "2026-03-01" :cp "Alpha Paper"))
        (e/draft-entry-core! st :ephemeral allow did-a (body :date "2026-03-01" :cp "Beta Realty"))
        (is (= 2 (count (store/postings-of st "c-1"))))))
    (testing "while a genuine retry is still idempotent"
      (let [st (seeded)]
        (e/draft-entry-core! st :ephemeral allow did-a (body :date "2026-03-01" :cp "Alpha Paper"))
        (let [again (e/draft-entry-core! st :ephemeral allow did-a
                                         (body :date "2026-03-01" :cp "Alpha Paper"))]
          (is (true? (get-in again [:body :duplicate?])))
          (is (= 1 (count (store/postings-of st "c-1")))))))))

;; ---------------------------------------------------------------------------
;; Which country's rule
;;
;; The first version of this namespace asked whether the operator was
;; claiming 優良帳簿 and never asked which country's 優良帳簿. A ledger with
;; no jurisdiction at all reported `:conformant` against 電子帳簿保存法施行
;; 規則 第五条第五項第一号ハ — a Japanese ministerial ordinance that could not
;; reach it. Measured 2026-08-18 on a USD book with `:jurisdiction nil`.
;;
;; It is the same defect the namespace was built to avoid, one level up: a
;; check that could not apply returning what a check that applied and passed
;; returns. `:declared?` was guarded and `:jurisdiction` was not.
;; ---------------------------------------------------------------------------

(deftest a-book-with-no-jurisdiction-is-not-conformant-with-a-japanese-ordinance
  (let [full (mapv #(assoc % :bookkeeping/transaction-date "2026-03-01"
                             :bookkeeping/counterparty "Acme")
                   books)]
    (doseq [j [nil :us [:eu :de] :atlantis]]
      (let [r (k/conformance {:jurisdiction j :postings full :declared? true})]
        (is (= :unchecked-jurisdiction (:kensaku/status r)) (str "for " (pr-str j)))
        (is (false? (k/conformant? {:jurisdiction j :postings full :declared? true})))
        (testing "and the provision is not stamped where it cannot reach —
                  citing a Japanese ordinance on a verdict about a book kept
                  elsewhere is the same mistake in a different key"
          (is (nil? (:kensaku/provision r)))
          (is (nil? (:kensaku/law-source r))))
        (testing "the jurisdiction that was asked about is reported back"
          (is (= j (:kensaku/jurisdiction r))))))))

(deftest the-jurisdiction-is-asked-before-the-election
  (testing "`which country's rule` precedes `are you claiming it` — otherwise
            a US book that declares nothing is told :not-declared, which reads
            as `answer the question and you might qualify`"
    (let [r (k/conformance {:jurisdiction :us :postings books :declared? nil})]
      (is (= :unchecked-jurisdiction (:kensaku/status r))))
    (testing "and declaring you are NOT claiming it does not reach :not-applicable
              either — nothing established that the rule was ever in play"
      (is (= :unchecked-jurisdiction
             (:kensaku/status (k/conformance {:jurisdiction :us :postings books
                                              :declared? false})))))))

(deftest the-catalogue-not-this-namespace-decides-which-jurisdictions-have-the-rule
  (testing "a set of keywords kept here would be a second place to update,
            and it would drift from the library that reads the statute"
    (is (= :claiming-preferential-treatment (taxlaw/requires-book-search? :jp)))
    (is (nil? (taxlaw/requires-book-search? :us))
        "nil, never false — `there is no such rule here` and `nobody has
         looked` must stay distinct, which is what makes them one test away")
    (testing "and a jp book still reaches every other status"
      (is (= :not-declared (:kensaku/status
                            (k/conformance {:jurisdiction :jp :postings books}))))
      (is (= :not-applicable (:kensaku/status
                              (k/conformance {:jurisdiction :jp :postings books
                                              :declared? false})))))))

(deftest the-route-reads-the-jurisdiction-from-the-client-not-the-request
  (testing "a caller that could name its own jurisdiction could name the one
            whose rule it happens to satisfy — the same reason the election
            is read from the client record"
    (let [st (store/mem-store)]
      (store/register-client! st {:client-id "c-1" :name "Acme Inc"
                                  :jurisdiction :us :yuryo-chobo-declared? true})
      (store/register-source-doc! st {:doc-id "d1" :client-id "c-1" :kind :receipt})
      (doseq [p books] (store/commit-posting! st "c-1" p))
      (let [r (e/search-core st allow did-a {"amount-from" "1"})]
        (is (= 200 (:status r)) "the search itself is jurisdiction-neutral and works")
        (is (pos? (get-in r [:body :match-count])))
        (is (= :unchecked-jurisdiction
               (get-in r [:body :conformance :kensaku/status]))
            "but the 優良帳簿 verdict does not follow it")))))

(deftest a-jurisdiction-stored-as-a-path-is-the-same-jurisdiction
  (testing "`kotoba.taxlaw` normalizes `:jp` and `[:jp]` because actors store
            a jurisdiction however their own schema does — worklaw and taxlaw
            both key by PATH, and this repo's client records use the bare
            keyword. Asking the catalog rather than keeping a map of keywords
            here is what makes both forms work; a local lookup table would
            answer `unchecked` for the path form and nobody would see why"
    (let [full (mapv #(assoc % :bookkeeping/transaction-date "2026-03-01"
                               :bookkeeping/counterparty "Acme")
                     books)]
      (doseq [j [:jp [:jp]]]
        (is (= :conformant (:kensaku/status
                            (k/conformance {:jurisdiction j :postings full
                                            :declared? true})))
            (str "for " (pr-str j)))))))
