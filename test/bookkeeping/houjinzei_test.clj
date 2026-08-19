(ns bookkeeping.houjinzei-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bookkeeping.houjinzei :as hz]
            [bookkeeping.actor :as actor]
            [bookkeeping.store :as store]))

;; ---------------------------------------------------------------------------
;; fixtures — the real actor, never hand-seeded postings
;; ---------------------------------------------------------------------------

(def ^:private chart
  {"仕入"   {:type :expense}
   "買掛金" {:type :liability}
   "売掛金" {:type :asset}
   "売上高" {:type :revenue}
   "資本金" {:type :equity}})

(def ^:private fy {:from "2026-04-01" :to "2027-03-31"})

(defn- store-for [& {:keys [jurisdiction chart*] :or {jurisdiction [:jp]}}]
  (let [s (store/mem-store)]
    (store/register-client! s {:client-id "c" :name "K" :jurisdiction jurisdiction})
    (store/register-source-doc! s {:doc-id "d1" :client-id "c" :kind :receipt})
    (when (or chart* chart) (store/register-chart! s "c" (or chart* chart)))
    s))

(defn- sale! [s & {:keys [date] :or {date "2026-06-01"}}]
  (let [g (actor/build-graph {:store s})]
    (actor/run-request!
     g {:client-id "c" :op :draft-entry :stake :low :source-doc "d1"
        :transaction-date date :counterparty "X"
        :lines [{:side :dr :account "売掛金" :amount 100000}
                {:side :cr :account "売上高" :amount 100000}]}
     {} (str "t-" date))))

(defn- ret [s & {:keys [fiscal-year] :or {fiscal-year fy}}]
  (hz/return-for {:store s :client-id "c" :fiscal-year fiscal-year}))

;; ---------------------------------------------------------------------------
;; the provisions this file decides on
;; ---------------------------------------------------------------------------

(deftest every-provision-carries-its-own-text
  (testing "quoted verbatim with the retrieval and the revision, so a reader
            can re-fetch rather than trust this file"
    (is (= "340AC0000000034" (:law/id hz/provisions)))
    (is (= "340AC0000000034_20260812_508AC0000000064" (:law/revision hz/provisions)))
    (is (str/includes? (:retrieved-via hz/provisions) "laws.e-gov.go.jp/api/2/law_data"))
    (is (= 5 (count (:articles hz/provisions))))
    (doseq [{:keys [article quote]} (:articles hz/provisions)]
      (is (seq article))
      (is (< 30 (count quote)) (str article " must carry its text, not a summary"))))
  (testing "民法 rides along because it decides the arithmetic, with its own id"
    (is (= "129AC0000000089" (get-in hz/provisions [:also :law/id])))
    (is (= 2 (count (get-in hz/provisions [:also :articles]))))))

;; ---------------------------------------------------------------------------
;; the deadline — 民法 第百四十三条第二項 and its 但書
;; ---------------------------------------------------------------------------

(deftest the-deadline-follows-the-civil-code-not-a-rule-of-thumb
  (testing "第七十四条第一項: 終了の日の翌日から二月以内. 起算日 is the day
            after, and 民法 第百四十三条第二項 ends the period on the day
            BEFORE the corresponding day"
    (let [r (hz/due-date "2026-03-31")]
      (is (= "2026-04-01" (:houjinzei/reckoned-from r)))
      (is (= "2026-05-31" (:houjinzei/due r)))
      (is (= :civil-code-143-2 (:houjinzei/basis r)))))
  (testing "the 但書 branch, which a naive +2 months would get wrong: 起算日
            2026-12-31 has no corresponding day in February, so the period
            ends on that month's last day"
    (let [r (hz/due-date "2026-12-30")]
      (is (= "2026-12-31" (:houjinzei/reckoned-from r)))
      (is (= "2027-02-28" (:houjinzei/due r)))
      (is (= :civil-code-143-2-proviso (:houjinzei/basis r)))))
  (testing "and it is calendar-correct across a leap year"
    (is (= "2028-02-29" (:houjinzei/due (hz/due-date "2027-12-30"))))
    (is (= "2027-02-28" (:houjinzei/due (hz/due-date "2026-12-31"))))
    (is (= "2026-03-31" (:houjinzei/due (hz/due-date "2026-01-31")))))
  (testing "an unusable date is nil, not a guess"
    (is (nil? (hz/due-date "not-a-date")))
    (is (nil? (hz/due-date nil)))))

;; ---------------------------------------------------------------------------
;; the six items — the point of the namespace
;; ---------------------------------------------------------------------------

(deftest a-ledger-supplies-none-of-the-six-figures
  (testing "第七十四条第一項 一〜六. 第二十二条第二項・第三項 both begin
            「別段の定めがあるものを除き」 and 第四項 sends the rest to
            一般に公正妥当と認められる会計処理の基準 — so the tax figure is the
            accounting figure minus provisions that are not postings"
    (let [nc (hz/not-computed)]
      (is (= 6 (count nc)) "all six, and the list is the article's own order")
      (is (= [1 2 3 4 5 6] (mapv :item nc)))
      (doseq [{:keys [item why]} nc]
        (is (seq why) (str "item " item " must say WHY, not just be absent")))))
  (testing "and there is no function here that would produce one"
    (is (empty? (filter #(re-find #"income|所得|tax-payable|filable" (name %))
                        (keys (ns-publics 'bookkeeping.houjinzei)))))))

;; ---------------------------------------------------------------------------
;; the statuses
;; ---------------------------------------------------------------------------

(deftest an-unread-jurisdiction-is-not-a-pass
  (let [r (ret (store-for :jurisdiction [:us]))]
    (is (= :unchecked-jurisdiction (:houjinzei/status r)))
    (is (not (hz/attachments-ready? r)))
    (is (string? (:houjinzei/why r))
        "kotoba.taxlaw's recorded reason rides along rather than a bare status")))

(deftest an-unbounded-fiscal-year-is-named-not-defaulted
  (testing "a 事業年度 is fixed by 定款 or 届出 — it is not in the postings, and
            defaulting to the calendar year would answer for a March-end
            company too"
    (let [r (ret (store-for) :fiscal-year nil)]
      (is (= :fiscal-year-not-bounded (:houjinzei/status r)))
      (is (seq (:houjinzei/fiscal-year-problems r))))
    (is (= :reversed (:problem (first (hz/fiscal-year-problems
                                       {:from "2027-03-31" :to "2026-04-01"})))))))

(deftest no-chart-means-the-attachments-cannot-be-produced
  (let [s (store/mem-store)]
    (store/register-client! s {:client-id "c" :name "K" :jurisdiction [:jp]})
    (let [r (ret s)]
      (is (= :attachments-unavailable (:houjinzei/status r)))
      (is (= :no (get-in r [:houjinzei/attachments :houjinzei/producible])))
      (is (str/includes? (get-in r [:houjinzei/attachments :houjinzei/why]) "chart")))))

(deftest statements-that-are-short-are-not-attachments
  (testing "a balance sheet that omits an account still balances. Producing
            one and attaching it to a return would be attaching a document
            that looks right"
    (let [s (store-for :chart* (dissoc chart "売上高"))]
      (sale! s)
      (let [r (ret s)]
        (is (contains? #{:attachments-not-whole :attachments-unavailable}
                       (:houjinzei/status r)))
        (is (not (hz/attachments-ready? r)))))))

(deftest the-pass-is-about-the-attachments-and-says-so
  (let [s (store-for)]
    (sale! s)
    (let [r (ret s)]
      (is (= :attachments-ready (:houjinzei/status r)))
      (is (hz/attachments-ready? r))
      (is (= ["貸借対照表" "損益計算書"]
             (get-in r [:houjinzei/attachments :houjinzei/required]))
          "read from kotoba.taxlaw, not hard-coded here")
      (testing "and the six unsupplied figures ride along ON THE PASS, so a
                caller reading :attachments-ready as ready-to-file is reading
                past them"
        (is (= 6 (count (:houjinzei/not-computed r)))))
      (testing "the deadline is computed from the fiscal year end"
        (is (= "2027-05-31" (:houjinzei/due r))))
      (testing "and 第七十四条第二項 is named as unread rather than assumed away"
        (is (str/includes? (:houjinzei/liquidation-exception-unread r) "第七十四条第二項"))))))
