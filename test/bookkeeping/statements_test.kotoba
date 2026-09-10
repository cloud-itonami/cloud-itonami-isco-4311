(ns bookkeeping.statements-test
  "The last link: 仕訳 → posting → trial balance → 貸借対照表 / 損益計算書,
  reachable from outside the process.

  `kotoba-lang/shohyo` had zero consumers when it was written. A library
  nothing calls is the same shape as a projection nothing invokes, which
  this repo shipped once already — so the first test here is about the
  ENDPOINT, not the namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.statements :as statements]
            [bookkeeping.edge.endpoints :as e]))

(def ^:private did "did:key:z6MkAAA")
(def ^:private other-did "did:key:z6MkBBB")
(def ^:private allow (e/parse-allowlist (str did "=c-1," other-did "=c-2")))

(def ^:private chart
  {"cash"     {:type :asset   :section :current-assets :concept "cash-and-equivalents"}
   "sales"    {:type :revenue :section :net-sales      :concept "revenue"}
   "cogs"     {:type :expense :section :cost-of-sales}
   "rent"     {:type :expense :section :sga}
   "capital"  {:type :equity  :section :shareholders-equity}})

(defn- seeded [& {:keys [with-chart?] :or {with-chart? true}}]
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "c-1" :name "Hanako's Bakery"})
    (store/register-client! st {:client-id "c-2" :name "Taro's Garage"})
    (store/register-source-doc! st {:doc-id "d1" :client-id "c-1" :kind :receipt})
    (when with-chart? (store/register-chart! st "c-1" chart))
    st))

(def ^:private entry
  (pr-str {:source-doc "d1"
           :lines [{:side :dr :account "cogs" :amount 600 :currency "JPY"}
                   {:side :cr :account "cash" :amount 600 :currency "JPY"}]}))

;; ---------------------------------------------------------------------------
;; reachable
;; ---------------------------------------------------------------------------

(deftest the-statements-are-reachable-from-the-endpoint
  (testing "shohyo had no consumers when it was written; this is the test
            that says it does now, phrased about the surface"
    (let [st (seeded)]
      (e/draft-entry-core! st :ephemeral allow did entry)
      (let [r (e/statements-core st allow did)]
        (is (= 200 (:status r)))
        (is (= "c-1" (get-in r [:body :client])))
        (is (contains? (get-in r [:body :by-currency]) "JPY"))))))

(deftest one-caller-cannot-read-anothers-statements
  (let [st (seeded)]
    (e/draft-entry-core! st :ephemeral allow did entry)
    (store/register-chart! st "c-2" chart)
    (is (empty? (get-in (e/statements-core st allow other-did) [:body :by-currency])))))

(deftest an-absent-allowlist-serves-503-here-too
  (is (= 503 (:status (e/statements-core (seeded) nil did))))
  (is (= 403 (:status (e/statements-core (seeded) allow "did:key:z6MkZZZ")))))

;; ---------------------------------------------------------------------------
;; no chart is a refusal, not an empty statement
;; ---------------------------------------------------------------------------

(deftest without-a-chart-the-actor-refuses-rather-than-inferring-one
  (testing "an empty statement is exactly what an inferred-and-wrong one
            would look like, so this is 409 and not 200"
    (let [st (seeded :with-chart? false)]
      (e/draft-entry-core! st :ephemeral allow did entry)
      (let [r (e/statements-core st allow did)]
        (is (= 409 (:status r)))
        (is (re-find #"chart" (get-in r [:body :error])))))
    (is (= :no-chart (:statements/coverage
                      (statements/for-client (seeded :with-chart? false) "c-1"))))))

(deftest a-chart-that-contradicts-会社計算規則-is-refused
  (let [st (seeded)]
    (store/register-chart! st "c-1" (assoc-in chart ["cogs" :type] :revenue))
    (let [r (e/statements-core st allow did)]
      (is (= 409 (:status r)))
      (is (= [:section-type-conflict] (mapv :problem (get-in r [:body :problems])))))))

;; ---------------------------------------------------------------------------
;; the numbers
;; ---------------------------------------------------------------------------

(defn- with-books []
  (let [st (seeded)]
    ;; sales 1000 on credit to capital, cogs 600 and rent 200 paid in cash
    (store/commit-posting! st "c-1"
      {:ledger/posting "je-1"
       :ledger/entries [{:ledger/account "cash" :ledger/side :debit :ledger/amount 1000 :ledger/currency "JPY"}
                        {:ledger/account "sales" :ledger/side :credit :ledger/amount 1000 :ledger/currency "JPY"}]})
    (store/commit-posting! st "c-1"
      {:ledger/posting "je-2"
       :ledger/entries [{:ledger/account "cogs" :ledger/side :debit :ledger/amount 600 :ledger/currency "JPY"}
                        {:ledger/account "cash" :ledger/side :credit :ledger/amount 600 :ledger/currency "JPY"}]})
    (store/commit-posting! st "c-1"
      {:ledger/posting "je-3"
       :ledger/entries [{:ledger/account "rent" :ledger/side :debit :ledger/amount 200 :ledger/currency "JPY"}
                        {:ledger/account "cash" :ledger/side :credit :ledger/amount 200 :ledger/currency "JPY"}]})
    st))

(deftest the-statement-adds-up
  (let [r (statements/for-client (with-books) "c-1")
        jpy (get-in r [:statements/shohyo :shohyo/by-currency "JPY"])]
    (is (= :ok (:statements/coverage r)))
    (is (= 200 (:assets (:totals jpy))) "1000 in, 800 out")
    (is (= 1000 (:revenue (:totals jpy))))
    (is (= 800 (:expenses (:totals jpy))))
    (is (= 200 (:net-income (:totals jpy))))
    (is (true? (get-in jpy [:equation :holds?])))
    (is (true? (:statements/complete? r)))))

(deftest the-jp-ladder-needs-every-section-declared
  (testing "the chart here declares 売上高 / 売上原価 / 販管費 and no more,
            so the ladder says which sections it lacks rather than treating
            営業外収益 as zero"
    (let [l (get-in (statements/for-client (with-books) "c-1") [:statements/jp "JPY"])]
      (is (= :not-declared (:shohyo.jp/coverage l)))
      (is (= [:non-operating-income :non-operating-expense
              :extraordinary-income :extraordinary-loss]
             (:shohyo.jp/missing-sections l))))))

(deftest a-fully-sectioned-chart-produces-the-ladder
  (let [st (with-books)]
    (store/register-chart! st "c-1"
      (merge chart {"interest"     {:type :revenue :section :non-operating-income}
                    "interest-exp" {:type :expense :section :non-operating-expense}
                    "gain"         {:type :revenue :section :extraordinary-income}
                    "loss"         {:type :expense :section :extraordinary-loss}}))
    (let [l (get-in (statements/for-client st "c-1") [:statements/jp "JPY"])]
      (is (= :checked (:shohyo.jp/coverage l)))
      (is (= 400 (:amount (:shohyo.jp/gross l))) "1000 - 600")
      (is (= "売上総利益金額" (:label (:shohyo.jp/gross l))))
      (is (= 200 (:amount (:shohyo.jp/operating l))) "400 - 200 rent"))))

(deftest a-loss-reaches-the-endpoint-with-its-own-label
  (testing "第八十九条第二項 must survive the JSON boundary — a caller must
            not receive a negative 売上総利益"
    (let [st (with-books)]
      (store/register-chart! st "c-1"
        (merge chart {"interest"     {:type :revenue :section :non-operating-income}
                      "interest-exp" {:type :expense :section :non-operating-expense}
                      "gain"         {:type :revenue :section :extraordinary-income}
                      "loss"         {:type :expense :section :extraordinary-loss}}))
      (store/commit-posting! st "c-1"
        {:ledger/posting "je-4"
         :ledger/entries [{:ledger/account "cogs" :ledger/side :debit :ledger/amount 900 :ledger/currency "JPY"}
                          {:ledger/account "cash" :ledger/side :credit :ledger/amount 900 :ledger/currency "JPY"}]})
      (let [g (get-in (e/statements-core st allow did)
                      [:body :by-currency "JPY" :jp "gross"])]
        (is (= 500 (:amount g)) "positive magnitude")
        (is (= "売上総損失金額" (:label g)))
        (is (= "第八十九条" (:article g)))))))

(deftest an-unclassified-account-is-named-in-the-response
  (testing "it is the one thing a reader of a balance sheet cannot see for
            themselves"
    (let [st (with-books)]
      (store/commit-posting! st "c-1"
        {:ledger/posting "je-x"
         :ledger/entries [{:ledger/account "mystery" :ledger/side :debit :ledger/amount 1 :ledger/currency "JPY"}
                          {:ledger/account "cash" :ledger/side :credit :ledger/amount 1 :ledger/currency "JPY"}]})
      (let [r (e/statements-core st allow did)]
        (is (= 200 (:status r)))
        (is (= ["mystery"] (get-in r [:body :unclassified])))
        (is (false? (get-in r [:body :complete?]))
            "200 means the request was answered, not that the books are finished")))))

(deftest declared-and-empty-is-not-undeclared
  (testing "a chart that names 営業外収益 has said the section exists and is
            empty this period; a chart that does not name it has said
            nothing. Seeding only sections with balances would collapse the
            two and make every sparse period look unstated — measured, the
            ladder reported :not-declared for a fully sectioned chart until
            the seed was added."
    (let [st (with-books)]
      (store/register-chart! st "c-1"
        (merge chart {"interest" {:type :revenue :section :non-operating-income}
                      "ie"       {:type :expense :section :non-operating-expense}
                      "gain"     {:type :revenue :section :extraordinary-income}
                      "loss"     {:type :expense :section :extraordinary-loss}}))
      (let [l (get-in (statements/for-client st "c-1") [:statements/jp "JPY"])]
        (is (= :checked (:shohyo.jp/coverage l))
            "none of those four has a single posting, and that is fine")
        (is (= 200 (:amount (:shohyo.jp/ordinary l)))
            "empty 営業外 sections contribute zero, not absence")))))
