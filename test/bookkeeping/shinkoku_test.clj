(ns bookkeeping.shinkoku-test
  "消費税申告 — what the books supply, and what they cannot.

  Most of this file is about the eight answers that are NOT a pass, and
  about the one hazard the namespace exists for: input tax credited on an
  entry nobody checked against 消費税法 第三十条第七項."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [bookkeeping.actor :as actor]
            [bookkeeping.posting :as posting]
            [bookkeeping.shinkoku :as sk]
            [bookkeeping.shohizei :as sz]
            [bookkeeping.store :as store]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(def ^:private chart
  {"仕入"        {:type :expense}
   "買掛金"      {:type :liability}
   "売掛金"      {:type :asset}
   "売上高"      {:type :revenue}
   "仮払消費税"  {:type :asset     :tax-role :input  :tax-rate 10}
   "仮受消費税"  {:type :liability :tax-role :output :tax-rate 10}})

(def ^:private year {:from "2026-01-01" :to "2026-12-31"})

(defn- store-for
  "A store with one client in `jurisdiction`, one registered document and
  the chart above."
  [& {:keys [jurisdiction doc chart*]
      :or {jurisdiction [:jp]
           doc {:doc-id "d1" :client-id "c" :kind :receipt
                :registration-number "T1234567890123"}}}]
  (let [s (store/mem-store)]
    (store/register-client! s {:client-id "c" :name "K" :jurisdiction jurisdiction})
    (when doc (store/register-source-doc! s doc))
    (store/register-chart! s "c" (or chart* chart))
    s))

(defn- purchase
  "Drive the REAL actor for one purchase entry. Not a hand-seeded posting:
  the join `bookkeeping.shinkoku` performs is the actor's own content-id, and
  a fixture that assembled postings itself could agree with a join that was
  wrong."
  [s & {:keys [date claim? doc] :or {date "2026-03-01" claim? true doc "d1"}}]
  (let [g (actor/build-graph {:store s})]
    (actor/run-request!
     g (cond-> {:client-id "c" :op :draft-entry :stake :low :source-doc doc
                :transaction-date date :counterparty "X"
                :lines [{:side :dr :account "仕入" :amount 50000}
                        {:side :dr :account "仮払消費税" :amount 5000}
                        {:side :cr :account "買掛金" :amount 55000}]}
         claim? (assoc :tax-treatment :input-tax-credit))
     {} (str "t-" date "-" doc "-" claim?))))

(defn- ret [s & {:keys [period regime] :or {period year regime :general}}]
  (sk/return-for {:store s :client-id "c" :period period :regime regime}))

;; ---------------------------------------------------------------------------
;; what was read, and what was not
;; ---------------------------------------------------------------------------

(deftest every-provision-carries-its-own-text
  (testing "quoted verbatim with the retrieval and the revision, so a reader
            can re-fetch rather than trust this file"
    (is (= "363AC0000000108" (:law/id sk/provisions)))
    (is (= "363AC0000000108_20260401_508AC0000000012" (:law/revision sk/provisions))
        "the same revision bookkeeping.shohizei recorded — one retrieval, two files")
    (is (str/includes? (:retrieved-via sk/provisions) "laws.e-gov.go.jp/api/2/law_data"))
    (is (= 9 (count (:articles sk/provisions))))
    (doseq [{:keys [article quote]} (:articles sk/provisions)]
      (is (seq article))
      (is (< 40 (count quote)) (str article " must carry its text, not a summary")))))

(deftest the-articles-that-decide-something-are-quoted
  (testing "each check below names an article, and each of those articles is
            here with its text"
    (let [by (into {} (map (juxt :article identity)) (:articles sk/provisions))]
      (is (str/includes? (:quote (by "第九条第一項")) "千万円以下")
          "the ¥10,000,000 threshold, from the statute")
      (is (str/includes? (:quote (by "第九条第一項")) "適格請求書発行事業者を除く")
          "and the carve-out that inverts the naive rule: a registered issuer
           under the threshold is NOT 免税")
      (is (str/includes? (:quote (by "第十九条第一項")) "事業年度")
          "課税期間 is the 法人's 事業年度, which is why it cannot be defaulted")
      (is (str/includes? (:quote (by "第二十九条")) "百分の七・八")
          "the national rate — NOT the 10/100 of 施行令第七十条の十")
      (is (str/includes? (:quote (by "第三十条第七項")) "保存しない場合")
          "the condition the input-tax partition enforces")
      (is (str/includes? (:quote (by "第三十七条第一項")) "五千万円以下")
          "簡易課税's own threshold, distinct from 第九条's"))))

(deftest nothing-unread-backs-a-check
  (testing "every :read? false item names a law that is in `not-read`, and
            every :read? true item names an article some file actually quoted.
            A rule enforced only in prose is not enforced."
    (let [mine (set (map :article (:articles sk/provisions)))
          shohizei (set (map :article (:articles sz/provisions)))
          unread (set (map :law sk/not-read))]
      (is (seq sk/not-computed))
      (doseq [{:keys [item article read? read-by]} sk/not-computed]
        (is (= read? (some? read-by))
            (str item ": :read? and :read-by must agree"))
        (case read-by
          :bookkeeping.shinkoku
          (is (contains? mine article)
              (str item " claims this file read " article ", which is not in `provisions`"))
          :bookkeeping.shohizei
          (is (contains? shohizei article)
              (str item " claims bookkeeping.shohizei read " article))
          nil
          (when article
            (is (contains? unread article)
                (str item " is unread and must be listed in `not-read`: " article))))))))

(deftest the-two-elections-are-named-as-unread
  (testing "積上げ / 割戻し is a real election a taxpayer makes, on both sides.
            Neither article was read, so neither side is assumed."
    (let [unread (into {} (map (juxt :law identity)) sk/not-read)]
      (is (contains? unread "消費税法施行令 第六十二条") "売上税額の積上げ計算")
      (is (contains? unread "消費税法施行令 第四十六条") "仕入税額の積上げ／割戻し")
      (is (every? #(seq (:why %)) sk/not-read)))))

(deftest the-invoice-figure-is-not-the-return-figure
  (testing "施行令第七十条の十 answers the ISSUER's 消費税額等 (10/100, incl.
            地方消費税); 第四十五条第一項第二号 wants 第二十九条's 7.8/100. This
            namespace does not call taxlaw/consumption-tax-amount at all, and
            reports no output tax."
    (let [s (store-for)
          _ (purchase s)
          r (ret s)]
      (is (= :computed (:shinkoku/status r)))
      (is (not-any? #(str/includes? (str %) "output-tax") (keys r))
          "no key labelled output tax")
      (let [item (first (filter #(= :output-tax (:item %)) sk/not-computed))]
        (is (some? item))
        (is (str/includes? (:why item) "7.8/100"))
        (is (str/includes? (:why item) "地方消費税"))))))

;; ---------------------------------------------------------------------------
;; the refusals that are not this repo's to overturn
;; ---------------------------------------------------------------------------

(deftest there-is-no-tax-payable-and-no-filable-predicate
  (testing "bookkeeping.shohizei refuses 納付税額 because 第四十五条第一項第四号
            is 第二号 minus 第三号 and neither is a ledger balance. This
            namespace extends that refusal instead of overturning it."
    (let [s (store-for) _ (purchase s) r (ret s)]
      (is (= :computed (:shinkoku/status r)))
      (is (not-any? #(str/includes? (str %) "payable") (keys r)))
      (is (nil? (resolve 'bookkeeping.shinkoku/filable?))
          "the convenience predicate is `computed?`; a `filable?` would be read
           as an answer about filing, which nothing here can give")
      (is (some? (resolve 'bookkeeping.shinkoku/computed?))))))

(deftest the-figures-carry-shohizei-s-own-refusal-too
  (testing "two lists at two scopes, neither merged into the other"
    (let [s (store-for) _ (purchase s) r (ret s)]
      (is (seq (:shohizei/not-computed (:shinkoku/ledger-figures r))))
      (is (seq (:shinkoku/not-computed r)))
      (is (not= (set (map :item (:shohizei/not-computed (:shinkoku/ledger-figures r))))
                (set (map :item (:shinkoku/not-computed r))))))))

;; ---------------------------------------------------------------------------
;; 1. jurisdiction
;; ---------------------------------------------------------------------------

(deftest a-jurisdiction-whose-articles-were-not-read-is-held
  (doseq [j [[:us] [:eu] :atlantis nil]]
    (testing (str "jurisdiction " (pr-str j))
      (let [s (store-for :jurisdiction j)
            r (ret s)]
        (is (= :unchecked-jurisdiction (:shinkoku/status r)))
        (is (false? (sk/computed? {:store s :client-id "c" :period year :regime :general})))
        (is (nil? (:shinkoku/provisions r))
            "消費税法 is not stamped on a verdict about books kept elsewhere")
        (is (nil? (:shinkoku/law-source r)))))))

(deftest the-catalogued-non-vat-jurisdictions-say-why
  (testing "kotoba.taxlaw states a reason for [:us] and [:eu]; the refusal
            carries it rather than reissuing a bare `not read`"
    (is (str/includes? (:shinkoku/why (ret (store-for :jurisdiction [:us])))
                       "no federal consumption tax"))
    (is (str/includes? (:shinkoku/why (ret (store-for :jurisdiction [:eu])))
                       "Article 226(10)")))
  (testing "an uncatalogued one has no such sentence to carry, and still refuses"
    (let [w (:shinkoku/why (ret (store-for :jurisdiction :atlantis)))]
      (is (str/includes? w ":atlantis"))
      (is (str/includes? w "未検査は合格ではない")))))

(deftest read-for-is-this-file-s-own-statement
  (testing "not a copy of the catalog. Gating on the catalog would mean a
            third VAT jurisdiction silently acquires 消費税法 第四十五条."
    (is (= #{[:jp]} sk/read-for))))

;; ---------------------------------------------------------------------------
;; 2/3. 免税事業者 and 簡易課税
;; ---------------------------------------------------------------------------

(deftest the-regime-must-be-declared-and-is-read-strictly
  (doseq [r [nil "general" :sole-trader true]]
    (testing (str "regime " (pr-str r))
      (let [s (store-for) _ (purchase s)]
        (is (= :regime-not-declared (:shinkoku/status (ret s :regime r)))
            "anything not literally one of `regimes` is undeclared")
        (is (nil? (:shinkoku/regime (ret s :regime r)))
            "and the undeclared value is not echoed back as though it were one")))))

(deftest the-declared-regime-says-why-the-return-is-a-different-one
  (let [s (store-for) _ (purchase s)]
    (testing "免税事業者 — 第九条第一項"
      (let [r (ret s :regime :exempt)]
        (is (= :regime-not-general (:shinkoku/status r)))
        (is (str/includes? (:shinkoku/why r) "第九条第一項"))
        (is (str/includes? (:shinkoku/why r) "第三十条第一項")
            "and that the exemption also removes the input-tax credit")))
    (testing "簡易課税 — 第三十七条第一項"
      (let [r (ret s :regime :simplified)]
        (is (= :regime-not-general (:shinkoku/status r)))
        (is (str/includes? (:shinkoku/why r) "みなし仕入率"))))
    (testing "neither is a pass"
      (is (false? (sk/computed? {:store s :client-id "c" :period year :regime :exempt})))
      (is (false? (sk/computed? {:store s :client-id "c" :period year :regime :simplified}))))))

;; ---------------------------------------------------------------------------
;; 4. 課税期間
;; ---------------------------------------------------------------------------

(deftest a-period-is-supplied-not-inferred
  (let [s (store-for) _ (purchase s)]
    (doseq [[label p] [["missing" nil]
                       ["empty" {}]
                       ["not a map" "2026"]
                       ["bad :from" {:from "2026-1-1" :to "2026-12-31"}]
                       ["impossible day" {:from "2026-02-30" :to "2026-12-31"}]
                       ["reversed" {:from "2026-12-31" :to "2026-01-01"}]]]
      (testing label
        (let [r (ret s :period p)]
          (is (= :period-not-bounded (:shinkoku/status r)))
          (is (seq (:shinkoku/period-problems r))
              "and it says which part, not merely that something is wrong"))))
    (testing "第十九条第一項 is why: the period is the taxpayer's, not the book's"
      (is (str/includes? (:shinkoku/why (ret s :period nil)) "事業年度")))))

(deftest period-problems-names-each-fault-separately
  (is (= [:no-period] (mapv :problem (sk/period-problems nil))))
  (is (= [:bad-from :bad-to] (mapv :problem (sk/period-problems {:from "x" :to "y"}))))
  (is (= [:reversed] (mapv :problem (sk/period-problems {:from "2026-06-01" :to "2026-05-31"}))))
  (is (empty? (sk/period-problems {:from "2026-01-01" :to "2026-01-01"}))
      "a single-day period is a period"))

;; ---------------------------------------------------------------------------
;; 5. an entry that cannot be placed
;; ---------------------------------------------------------------------------

(deftest an-undated-entry-is-never-quietly-inside-or-outside
  (testing "bookkeeping.posting made 取引年月日 optional deliberately. An entry
            without one might belong to this period; calling it :out files the
            return without it and reports nothing."
    (let [s (store-for)
          _ (purchase s :date nil)
          r (ret s)]
      (is (= :entries-not-placeable (:shinkoku/status r)))
      (is (= 1 (count (:shinkoku/unplaceable r))))
      (is (some? (:posting (first (:shinkoku/unplaceable r))))
          "named by posting id — a count is not something an operator can act on")
      (is (str/includes? (:shinkoku/why r) "取引年月日")))))

(deftest a-date-that-cannot-exist-is-unplaceable-not-outside
  (let [p (posting/project "x" [{:side :dr :account "仕入" :amount 1}
                                {:side :cr :account "買掛金" :amount 1}]
                           :transaction-date "2026-02-30")]
    (is (= :unplaceable (sk/placement p year)))
    (is (= :unplaceable (sk/placement (assoc p :bookkeeping/transaction-date nil) year)))
    (is (= :out (sk/placement (assoc p :bookkeeping/transaction-date "2025-12-31") year)))
    (is (= :out (sk/placement (assoc p :bookkeeping/transaction-date "2027-01-01") year)))
    (is (= :in (sk/placement (assoc p :bookkeeping/transaction-date "2026-01-01") year))
        "inclusive at the start")
    (is (= :in (sk/placement (assoc p :bookkeeping/transaction-date "2026-12-31") year))
        "and at the end")))

(deftest one-undated-entry-taints-every-period-not-just-its-own
  (testing "there is no period it can be excluded from, because there is no
            date to exclude it by"
    (let [s (store-for)
          _ (purchase s :date "2026-03-01")
          _ (purchase s :date nil :doc "d1")
          r (ret s :period {:from "2026-01-01" :to "2026-01-31"})]
      (is (= :entries-not-placeable (:shinkoku/status r))
          "even though the dated entry is outside this period"))))

;; ---------------------------------------------------------------------------
;; 6. nothing to report
;; ---------------------------------------------------------------------------

(deftest an-empty-book-and-an-empty-period-are-both-refused-and-distinguishable
  (testing "empty book"
    (let [r (ret (store-for))]
      (is (= :no-entries (:shinkoku/status r)))
      (is (zero? (:shinkoku/entry-count r)))
      (is (str/includes? (:shinkoku/why r) "空である"))))
  (testing "a book with entries, none in this period"
    (let [s (store-for)
          _ (purchase s :date "2026-03-01")
          r (ret s :period {:from "2025-01-01" :to "2025-12-31"})]
      (is (= :no-entries (:shinkoku/status r)))
      (is (= 1 (:shinkoku/entry-count r)) "the evidence floor: the book was not empty")
      (is (zero? (:shinkoku/in-period-count r)))
      (is (not (str/includes? (:shinkoku/why r) "空である"))))))

;; ---------------------------------------------------------------------------
;; 7. rates
;; ---------------------------------------------------------------------------

(deftest a-chart-that-cannot-separate-the-rates-stops-here
  (testing "第四十五条第一項第一号・第二号 require 税率の異なるごとに区分した
            figures, and bookkeeping.shohizei's coverage rides along"
    (let [s (store-for :chart* {"仕入" {:type :expense} "買掛金" {:type :liability}})
          _ (purchase s)
          r (ret s)]
      (is (= :figures-not-aggregable (:shinkoku/status r)))
      (is (= :no-tax-accounts (:shohizei/coverage (:shinkoku/ledger-figures r))))
      (is (str/includes? (:shinkoku/why r) "no-tax-accounts"))))
  (testing "a tax account with no declared rate is the same verdict, other reason"
    (let [s (store-for :chart* (assoc chart "仮払消費税" {:type :asset :tax-role :input}))
          _ (purchase s)
          r (ret s)]
      (is (= :figures-not-aggregable (:shinkoku/status r)))
      (is (= :rates-not-declared (:shohizei/coverage (:shinkoku/ledger-figures r)))))))

;; ---------------------------------------------------------------------------
;; 8. 第三十条第七項 — the partition this namespace exists for
;; ---------------------------------------------------------------------------

(deftest input-tax-nobody-checked-is-reported-not-credited
  (testing "the governor's rules 5 and 6 fire ONLY on a proposal claiming
            :input-tax-credit. An entry that debits 仮払消費税 and claims
            nothing was never checked against 第三十条第七項, and its tax sits
            in the balance looking exactly like a verified one."
    (let [s (store-for)
          _ (purchase s :claim? false)
          r (ret s)
          basis (:shinkoku/input-tax-basis r)]
      (is (= :input-tax-unverified (:shinkoku/status r)) "not a pass")
      (is (false? (sk/computed? {:store s :client-id "c" :period year :regime :general})))
      (is (= 1 (:shinkoku/examined basis)))
      (is (empty? (:shinkoku/checked basis)))
      (is (= [:no-tax-claim] (mapv :basis (:shinkoku/not-checked basis))))
      (is (some? (:posting (first (:shinkoku/not-checked basis))))
          "named by posting")
      (is (= {[10 nil] 5000} (:shinkoku/not-checked-amount basis))
          "and by amount, so an operator can see the size of the exposure"))))

(deftest a-verified-qualified-invoice-is-the-one-pass
  (let [s (store-for)
        _ (purchase s)
        r (ret s)
        basis (:shinkoku/input-tax-basis r)]
    (is (= :computed (:shinkoku/status r)))
    (is (true? (sk/computed? {:store s :client-id "c" :period year :regime :general})))
    (is (= 1 (:shinkoku/examined basis)))
    (is (empty? (:shinkoku/not-checked basis)))
    (is (= {[10 nil] 5000} (:shinkoku/checked-amount basis)))
    (is (= "T1234567890123" (:registration-number (first (:shinkoku/checked basis)))))
    (is (= "消費税法 第三十条第七項" (:shinkoku/provision basis)))))

(deftest a-held-entry-contributes-nothing-to-the-return
  (testing "the whole failure mode: input tax credited on an entry the governor
            refused. A document with no 登録番号 is HELD by rule 6, so it never
            becomes a posting — and the return reports an empty period rather
            than a credit."
    (let [s (store-for :doc {:doc-id "d1" :client-id "c" :kind :receipt})
          run (purchase s)
          r (ret s)]
      (is (= :hold (get-in run [:state :disposition])))
      (is (some #(= :invalid-registration-number (:rule %))
                (get-in run [:state :verdict :violations])))
      (is (empty? (store/postings-of s "c")))
      (is (= :no-entries (:shinkoku/status r)))
      (is (zero? (:shinkoku/entry-count r)))
      (is (nil? (:shinkoku/input-tax-basis r))
          "there is nothing to partition, and no zeroes are emitted as though
           the partition had run"))))

(deftest the-governor-rules-are-now-reachable-through-the-actor
  (testing "measured 2026-08-18: `bookkeeping.advisor`'s mock dropped
            :tax-treatment, so rules 5 and 6 could only ever fire when the
            governor was called directly. A rule no path can reach is not
            enforced."
    (let [s (store-for :jurisdiction :atlantis)
          run (purchase s)]
      (is (= :hold (get-in run [:state :disposition])))
      (is (some #(= :unchecked-jurisdiction (:rule %))
                (get-in run [:state :verdict :violations])))))
  (testing "and the claim still reaches the committed record, so the return can
            see it"
    (let [s (store-for)
          _ (purchase s)]
      (is (= :input-tax-credit (:tax-treatment (:payload (first (store/records-of s "c")))))))))

(deftest a-posting-with-no-record-behind-it-is-not-creditable
  (testing "commit-posting! is a store call. A posting whose provenance is not
            in the records cannot have been checked against anything."
    (let [s (store-for)
          p (posting/project "orphan" [{:side :dr :account "仕入" :amount 1000}
                                       {:side :dr :account "仮払消費税" :amount 100}
                                       {:side :cr :account "買掛金" :amount 1100}]
                             :transaction-date "2026-05-05")]
      (store/commit-posting! s "c" p)
      (let [r (ret s)]
        (is (= :input-tax-unverified (:shinkoku/status r)))
        (is (= [:no-record] (mapv :basis (:shinkoku/not-checked (:shinkoku/input-tax-basis r)))))))))

(deftest a-claim-citing-an-unregistered-document-is-named-as-such
  (testing "reached by seeding the store directly — the actor's governor holds
            this one at :unknown-source-doc, so the bucket exists for stores
            whose records did not come through this actor."
    (let [s (store-for)
          lines [{:side :dr :account "仕入" :amount 1000}
                 {:side :dr :account "仮払消費税" :amount 100}
                 {:side :cr :account "買掛金" :amount 1100}]
          payload {:op :draft-entry :effect :propose :source-doc "ghost"
                   :tax-treatment :input-tax-credit :lines lines
                   :transaction-date "2026-05-05" :counterparty nil}
          id (posting/content-id "ghost" lines :transaction-date "2026-05-05" :counterparty nil)]
      (store/commit-record! s {:client-id "c" :op :draft-entry :source-doc "ghost" :payload payload})
      (store/commit-posting! s "c" (posting/project id lines :transaction-date "2026-05-05"))
      (let [row (first (:shinkoku/not-checked (:shinkoku/input-tax-basis (ret s))))]
        (is (= :source-doc-not-registered (:basis row)))
        (is (= "ghost" (:source-doc row)))))))

(deftest the-uncatalogued-jurisdiction-bucket-is-reachable-through-the-public-fn
  (testing "`input-tax-basis` takes the jurisdiction, so a caller can ask about
            one `return-for` would have refused earlier"
    (let [s (store-for)
          _ (purchase s)
          b (sk/input-tax-basis {:store s :client-id "c" :jurisdiction :atlantis
                                 :chart chart :postings (store/postings-of s "c")})]
      (is (= [:unchecked-jurisdiction] (mapv :basis (:shinkoku/not-checked b))))
      (is (empty? (:shinkoku/checked b))))))

(deftest checked-is-not-called-deductible
  (testing "第三十条第七項's basis is necessary and not sufficient — 第三十条第二項
            still applies and is not answered anywhere in this repository"
    (let [s (store-for) _ (purchase s) r (ret s)]
      (is (not-any? #(str/includes? (str %) "deductible")
                    (keys (:shinkoku/input-tax-basis r))))
      (is (contains? (set (map :item (:shinkoku/not-computed r))) :taxable-sales-ratio)))))

(deftest the-partition-reports-what-it-examined
  (testing "an evidence floor: zero not-checked out of zero examined and out of
            two are different answers"
    (let [s (store-for)
          ;; a sale carries no input tax at all, so nothing is examined
          g (actor/build-graph {:store s})
          _ (actor/run-request! g {:client-id "c" :op :draft-entry :stake :low
                                   :source-doc "d1" :transaction-date "2026-04-01"
                                   :counterparty "Y"
                                   :lines [{:side :dr :account "売掛金" :amount 110000}
                                           {:side :cr :account "売上高" :amount 100000}
                                           {:side :cr :account "仮受消費税" :amount 10000}]}
                                {} "sale")
          r (ret s)]
      (is (= :computed (:shinkoku/status r)))
      (is (zero? (:shinkoku/examined (:shinkoku/input-tax-basis r)))
          "and the caller can see that nothing was examined, rather than reading
           an empty :not-checked as a clean bill")
      (is (= 1 (:shinkoku/in-period-count r))))))

(deftest input-tax-of-reads-the-chart-and-the-sign
  (let [p (posting/project "x" [{:side :dr :account "仕入" :amount 1000}
                                {:side :dr :account "仮払消費税" :amount 100}
                                {:side :cr :account "買掛金" :amount 1100}])]
    (is (= {[10 nil] 100} (sk/input-tax-of chart p)))
    (is (= {} (sk/input-tax-of {"仕入" {:type :expense}} p))
        "an account the chart does not call :input contributes nothing"))
  (testing "a credit to the input account reduces it — a reversal is not a credit"
    (let [p (posting/project "y" [{:side :cr :account "仮払消費税" :amount 100}
                                  {:side :dr :account "買掛金" :amount 100}])]
      (is (= {[10 nil] -100} (sk/input-tax-of chart p))))))

;; ---------------------------------------------------------------------------
;; exactly one pass
;; ---------------------------------------------------------------------------

(deftest only-computed-is-a-pass
  (let [cases
        [[:unchecked-jurisdiction (let [s (store-for :jurisdiction [:us])] [s year :general])]
         [:regime-not-declared    (let [s (store-for)] (purchase s) [s year nil])]
         [:regime-not-general     (let [s (store-for)] (purchase s) [s year :exempt])]
         [:period-not-bounded     (let [s (store-for)] (purchase s) [s nil :general])]
         [:entries-not-placeable  (let [s (store-for)] (purchase s :date nil) [s year :general])]
         [:no-entries             (let [s (store-for)] [s year :general])]
         [:figures-not-aggregable (let [s (store-for :chart* {"仕入" {:type :expense}})]
                                    (purchase s) [s year :general])]
         [:input-tax-unverified   (let [s (store-for)] (purchase s :claim? false) [s year :general])]
         [:computed               (let [s (store-for)] (purchase s) [s year :general])]]]
    (is (= 9 (count cases)) "nine values, and the suite reaches every one")
    (doseq [[expected [s period regime]] cases]
      (let [opts {:store s :client-id "c" :period period :regime regime}]
        (is (= expected (:shinkoku/status (sk/return-for opts))))
        (is (= (= :computed expected) (sk/computed? opts))
            (str expected ": computed? must be true for exactly the one pass"))))
    (is (= 9 (count (set (map first cases)))) "and they are nine DIFFERENT values")))

(deftest every-status-explains-itself
  (doseq [[_ opts] [[:a {:store (store-for :jurisdiction [:us]) :client-id "c"
                         :period year :regime :general}]
                    [:b {:store (store-for) :client-id "c" :period year :regime nil}]
                    [:c {:store (store-for) :client-id "c" :period nil :regime :general}]
                    [:d {:store (store-for) :client-id "c" :period year :regime :general}]]]
    (let [r (sk/return-for opts)]
      (is (seq (:shinkoku/why r)) (str (:shinkoku/status r) " must say why"))
      (is (seq (:shinkoku/not-computed r))
          "and every answer carries the list of inputs still missing")
      (is (seq (:shinkoku/not-read r))
          "and the statutes that were not read"))))

;; ---------------------------------------------------------------------------
;; gaps the mutation table found — every one of these was written AFTER a
;; mutation survived, and each names the mutation that survived
;; ---------------------------------------------------------------------------

(deftest a-period-that-is-not-a-map-names-that-fault
  (testing "survivor :shinkoku-a-non-map-period-says-so — `(nil? period)`
            passed the whole suite, because a string period fell through to
            :bad-from / :bad-to and the status was :period-not-bounded either
            way. Same verdict, wrong sentence: an operator sent the wrong SHAPE
            and would be told its dates were wrong."
    (doseq [p ["2026" ["2026-01-01" "2026-12-31"] 2026 :year]]
      (is (= [:no-period] (mapv :problem (sk/period-problems p)))
          (str (pr-str p) " is not a period, and the problem is its shape")))))

(deftest a-registered-document-whose-登録番号-is-malformed-is-not-checked
  (testing "survivor :shinkoku-a-malformed-登録番号-is-not-checked — the
            :unsupported-document bucket was never reached, because through the
            actor the governor HOLDS this one at rule 6 and no posting is
            produced. It is reachable when a document was registered with a
            good number, the entry committed, and the registration later
            corrected — the posting stays, the basis does not."
    (let [s (store-for)
          lines [{:side :dr :account "仕入" :amount 1000}
                 {:side :dr :account "仮払消費税" :amount 100}
                 {:side :cr :account "買掛金" :amount 1100}]
          id (posting/content-id "d1" lines :transaction-date "2026-05-05" :counterparty nil)]
      (store/commit-record! s {:client-id "c" :op :draft-entry :source-doc "d1"
                               :payload {:op :draft-entry :effect :propose :source-doc "d1"
                                         :tax-treatment :input-tax-credit :lines lines
                                         :transaction-date "2026-05-05" :counterparty nil}})
      (store/commit-posting! s "c" (posting/project id lines :transaction-date "2026-05-05"))
      ;; the registry is corrected after the fact
      (store/register-source-doc! s {:doc-id "d1" :client-id "c" :kind :receipt
                                     :registration-number "1234567890123"})
      (let [r (ret s)
            row (first (:shinkoku/not-checked (:shinkoku/input-tax-basis r)))]
        (is (= :input-tax-unverified (:shinkoku/status r)))
        (is (= :unsupported-document (:basis row)))
        (is (= :malformed-registration-number (:taxlaw/reason row)))
        (is (= "1234567890123" (:registration-number row)))))))

(deftest amounts-at-one-rate-are-summed-across-postings
  (testing "survivor :shinkoku-the-amounts-sum-rather-than-overwrite — every
            fixture above had ONE posting per rate, so `merge` and `merge-with
            +` agreed. Two entries at 10% is the smallest case that separates
            them, and it is also the ordinary case."
    (let [s (store-for)
          _ (purchase s :date "2026-03-01" :claim? false)
          _ (purchase s :date "2026-04-01" :claim? false)
          basis (:shinkoku/input-tax-basis (ret s))]
      (is (= 2 (:shinkoku/examined basis)))
      (is (= {[10 nil] 10000} (:shinkoku/not-checked-amount basis))
          "5000 + 5000, not the last one"))))

(deftest a-reconcile-record-is-not-joined-to-a-posting
  (testing "survivor :shinkoku-only-draft-entries-ever-produced-a-posting —
            `bookkeeping.actor` projects only :draft-entry, so a record of any
            other op never named a posting. Recomputing a content-id for one
            would let a :reconcile note supply the tax treatment of an entry it
            is not."
    (let [s (store-for)
          lines [{:side :dr :account "仕入" :amount 1000}
                 {:side :dr :account "仮払消費税" :amount 100}
                 {:side :cr :account "買掛金" :amount 1100}]
          id (posting/content-id "d1" lines :transaction-date "2026-05-05" :counterparty nil)]
      ;; a reconcile record whose payload happens to carry the same fields
      (store/commit-record! s {:client-id "c" :op :reconcile :source-doc "d1"
                               :payload {:op :reconcile :effect :propose :source-doc "d1"
                                         :tax-treatment :input-tax-credit :lines lines
                                         :transaction-date "2026-05-05" :counterparty nil}})
      (store/commit-posting! s "c" (posting/project id lines :transaction-date "2026-05-05"))
      (let [row (first (:shinkoku/not-checked (:shinkoku/input-tax-basis (ret s))))]
        (is (= :no-record (:basis row))
            "the posting has no :draft-entry record behind it, and the reconcile
             note is not one")))))

(deftest a-bare-keyword-jurisdiction-is-the-same-jurisdiction
  (testing "kotoba.taxlaw normalizes `:jp` and `[:jp]`; an actor whose client
            records store the bare keyword must not be told its articles were
            never read. `bookkeeping.kensaku` names this exact hazard — a
            keyword map silently answers `unchecked`."
    (let [s (store-for :jurisdiction :jp)
          _ (purchase s)]
      (is (= :computed (:shinkoku/status (ret s))))
      (is (true? (sk/computed? {:store s :client-id "c" :period year :regime :general}))))))
