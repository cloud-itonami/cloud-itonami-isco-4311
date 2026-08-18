(ns bookkeeping.shohizei-test
  "消費税 — the figures a 確定申告 starts from, and the refusal to call them
  the answer.

  Most of this file is about what the namespace will NOT say."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bookkeeping.posting :as posting]
            [bookkeeping.shohizei :as sz]))

(def ^:private chart
  {"売上高"       {:type :revenue}
   "仮受消費税"   {:type :liability :tax-role :output :tax-rate 10}
   "仮受消費税8"  {:type :liability :tax-role :output :tax-rate 8}
   "仕入"         {:type :expense}
   "仮払消費税"   {:type :asset     :tax-role :input  :tax-rate 10}})

(defn- p [id & lines] (posting/project id (vec lines)))
(defn- dr [a n] {:side :dr :account a :amount n :currency "JPY"})
(defn- cr [a n] {:side :cr :account a :amount n :currency "JPY"})

(def ^:private books
  [(p "s1" (dr "売掛金" 110000) (cr "売上高" 100000) (cr "仮受消費税" 10000))
   (p "s2" (dr "売掛金" 108000) (cr "売上高" 100000) (cr "仮受消費税8" 8000))
   (p "b1" (dr "仕入" 50000) (dr "仮払消費税" 5000) (cr "買掛金" 55000))])

;; ---------------------------------------------------------------------------
;; what it refuses to say
;; ---------------------------------------------------------------------------

(deftest there-is-no-tax-payable-key
  (testing "第四十五条第一項第四号 is 第二号 minus 第三号, and 第三号 is
            broader than a 仮払消費税 balance. Emitting a number labelled
            納付税額 would be arithmetically clean and wrong."
    (let [s (sz/summary chart books)]
      (is (= :aggregated (:shohizei/coverage s)))
      (is (not (contains? s :shohizei/tax-payable)))
      (is (not-any? #(str/includes? (str %) "payable") (keys s))))))

(deftest what-it-cannot-compute-is-listed-not-skipped
  (let [s (sz/summary chart books)
        items (set (map :item (:shohizei/not-computed s)))]
    (is (contains? items :sales-returns) "第三十八条")
    (is (contains? items :bad-debts) "第三十九条")
    (is (contains? items :taxable-sales-ratio) "第三十条第二項")
    (is (contains? items :simplified-taxation) "第三十七条")
    (is (contains? items :interim-payments))
    (is (every? #(seq (:why %)) (:shohizei/not-computed s))
        "each one says why, or a reader cannot judge the distance to a filing")))

(deftest the-difference-is-called-a-difference
  (let [s (sz/summary chart books)
        d (sz/difference s)]
    (is (= 5000 (get d [10 "JPY"])) "10% : 仮受 10000 - 仮払 5000")
    (is (= 8000 (get d [8 "JPY"])) "8% : 仮受 8000, no input at that rate")
    (testing "and it is keyed PER RATE. A caller can still add the values up,
              and nothing here can stop that -- what this guarantees is that
              the rates arrive separated, which is what 第一号 and 第二号
              require and what a single 仮受消費税 balance cannot give.

              (An earlier draft asserted the summed total was not 13000. It
              is 13000, unavoidably: 5000 + 8000. The assertion said nothing
              and was removed rather than adjusted to whatever passed.)"
      (is (= #{[10 "JPY"] [8 "JPY"]} (set (keys d)))))))

;; ---------------------------------------------------------------------------
;; rate separation — the thing a single balance cannot give
;; ---------------------------------------------------------------------------

(deftest figures-are-separated-by-rate
  (testing "第一号 and 第二号 both say 税率の異なるごとに区分した"
    (let [s (sz/summary chart books)
          by (:shohizei/by-rate s)]
      (is (= #{[10 "JPY"] [8 "JPY"]} (set (keys by))))
      (is (= 10000 (get-in by [[10 "JPY"] :output])))
      (is (= 8000 (get-in by [[8 "JPY"] :output])))
      (is (= 5000 (get-in by [[10 "JPY"] :input])))
      (is (nil? (get-in by [[8 "JPY"] :input]))))))

(deftest a-tax-account-with-no-rate-stops-the-aggregation
  (testing "one balance covering two rates satisfies neither 第一号 nor
            第二号, and no arithmetic recovers the split — so this refuses
            rather than totalling"
    (let [s (sz/summary (assoc chart "仮受消費税" {:type :liability :tax-role :output})
                        books)]
      (is (= :rates-not-declared (:shohizei/coverage s)))
      (is (= ["仮受消費税"] (:shohizei/accounts-without-rate s)))
      (is (not (contains? s :shohizei/by-rate)))
      (is (empty? (sz/difference s)) "and nothing to subtract"))))

(deftest a-chart-with-no-tax-accounts-says-so
  (let [s (sz/summary {"cash" {:type :asset}} books)]
    (is (= :no-tax-accounts (:shohizei/coverage s)))
    (is (empty? (sz/difference s))
        "zeros here would look like a computed nil liability")))

;; ---------------------------------------------------------------------------
;; signs
;; ---------------------------------------------------------------------------

(deftest both-sides-are-reported-as-positive-magnitudes
  (testing "the article asks for 消費税額, not a signed balance; 仮受 is
            credit-normal and 仮払 debit-normal, and reporting one negative
            would make the difference read backwards"
    (let [by (:shohizei/by-rate (sz/summary chart books))]
      (is (pos? (get-in by [[10 "JPY"] :output])))
      (is (pos? (get-in by [[10 "JPY"] :input]))))))

;; ---------------------------------------------------------------------------
;; the deadline
;; ---------------------------------------------------------------------------

(deftest the-filing-deadline-is-two-months-from-the-period-end
  (testing "第四十五条第一項本文: 当該課税期間の末日の翌日から二月以内"
    (is (= "2026-05-31" (:deadline (sz/filing-deadline "2026-03-31"))))
    (is (= "2027-02-28" (:deadline (sz/filing-deadline "2026-12-31"))))
    (is (true? (:clamped? (sz/filing-deadline "2026-12-31")))
        "the clamp is a convention, not the article's, and says so")
    (is (false? (:clamped? (sz/filing-deadline "2026-03-31"))))
    (is (= "第四十五条第一項本文" (:article (sz/filing-deadline "2026-03-31"))))
    (testing "an unparseable date is nil, not a guess"
      (doseq [bad ["2026-3-31" "2026/03/31" "" nil "yesterday"]]
        (is (nil? (sz/filing-deadline bad)))))))

;; ---------------------------------------------------------------------------
;; provenance
;; ---------------------------------------------------------------------------

(deftest every-article-relied-on-is-quoted
  (let [p sz/provisions]
    (is (= "363AC0000000108" (:law/id p)))
    (is (= "363AC0000000108_20260401_508AC0000000012" (:law/revision p)))
    (is (= 5 (count (:articles p))))
    (doseq [{:keys [article quote]} (:articles p)]
      (is (str/starts-with? article "第四十五条"))
      (is (> (count quote) 20) (str article " quote is too short to be the text")))
    (testing "the two articles that force rate separation actually say so"
      (doseq [a ["第四十五条第一項第一号" "第四十五条第一項第二号"]]
        (is (str/includes? (:quote (first (filter #(= a (:article %)) (:articles p))))
                           "税率の異なるごとに")
            (str a " omits the clause the refusal rests on"))))))
