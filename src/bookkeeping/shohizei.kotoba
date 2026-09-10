(ns bookkeeping.shohizei
  "消費税 — the figures a 確定申告 starts from, and a refusal to call them the
  answer.

  The ledger holds 仮受消費税 and 仮払消費税. It is tempting to subtract one
  from the other and call the result 納付税額. **消費税法 第四十五条第一項
  says that is not what a return reports**, and the article is quoted in
  `provisions` so the claim can be checked rather than believed.

  Retrieved 2026-08-18, revision `363AC0000000108_20260401_508AC0000000012`.

  ## Two things the article requires that a single balance cannot give

  第一号 and 第二号 both say **税率の異なるごとに区分した** — separated by
  rate. One 仮受消費税 balance covering 10% and 軽減 8% together does not
  satisfy either, and no amount of arithmetic on it recovers the split. So
  this namespace aggregates by rate where the chart declares one and reports
  `:rates-not-declared` where it does not, instead of producing a total that
  looks like an answer.

  第三号 makes the deduction **broader than 仮払消費税**: it is the sum of
  仕入れに係る消費税額 *and* 売上げに係る対価の返還等 (第三十八条) *and*
  特定課税仕入れに係る対価の返還等 (第三十八条の二) *and* 領収をすることが
  できなくなつた… 貸倒れ (第三十九条). A ledger's input-tax account is the
  first of four.

  ## So there is no `:tax-payable` key, deliberately

  `:shohizei/not-computed` lists what stands between these figures and 第四号
  — including 課税売上割合 and 個別対応/一括比例, 簡易課税, 端数処理 and
  中間納付, none of which this fleet has read. Emitting a number labelled
  納付税額 would be the same class of error as a balance sheet that omits an
  account: arithmetically clean and wrong, with nothing in the output saying
  so."
  (:require [bookkeeping.trial-balance :as tb]))

(def provisions
  {:law/id "363AC0000000108"
   :law/title "消費税法"
   :law/revision "363AC0000000108_20260401_508AC0000000012"
   :retrieved-at "2026-08-18"
   :articles
   [{:article "第四十五条第一項第一号" :on :taxable-base
     :quote (str "その課税期間中に国内において行つた課税資産の譲渡等…に係る"
                 "税率の異なるごとに区分した課税標準である金額の合計額…")}
    {:article "第四十五条第一項第二号" :on :output-tax
     :quote "税率の異なるごとに区分した課税標準額に対する消費税額"}
    {:article "第四十五条第一項第三号" :on :deductible
     :quote (str "前章の規定によりその課税期間において前号に掲げる消費税額から"
                 "控除をされるべき次に掲げる消費税額の合計額 イ…仕入れに係る"
                 "消費税額 ロ…売上げに係る対価の返還等の金額に係る消費税額 "
                 "ハ…特定課税仕入れに係る対価の返還等を受けた金額に係る消費税額 "
                 "ニ…領収をすることができなくなつた課税資産の譲渡等の税込価額に"
                 "係る消費税額")}
    {:article "第四十五条第一項第四号" :on :payable
     :quote "第二号に掲げる消費税額から前号に掲げる消費税額の合計額を控除した残額"}
    {:article "第四十五条第一項本文" :on :deadline
     :quote (str "事業者（第九条第一項本文の規定により消費税を納める義務が免除"
                 "される事業者を除く。）は、課税期間ごとに、当該課税期間の末日の"
                 "翌日から二月以内に、…申告書を税務署長に提出しなければならない。")}]})

(def not-computed
  "What stands between these figures and 第四十五条第一項第四号.

  Listed rather than silently skipped: a caller has to be able to see the
  distance between a working figure and a filing, and `nil` communicates
  none of it."
  [{:item :sales-returns :article "第三十八条"
    :why "売上げに係る対価の返還等 is part of the 第三号 deduction and is not in the ledger's tax accounts"}
   {:item :specified-purchase-returns :article "第三十八条の二"
    :why "特定課税仕入れに係る対価の返還等, likewise"}
   {:item :bad-debts :article "第三十九条"
    :why "貸倒れに係る消費税額, likewise"}
   {:item :taxable-sales-ratio :article "第三十条第二項"
    :why "課税売上割合 and the 個別対応 / 一括比例 election were not read; without them the deductible input tax is not simply the 仮払消費税 balance"}
   {:item :simplified-taxation :article "第三十七条"
    :why "簡易課税 replaces the actual input tax with a deemed figure; whether the business elected it is not in the ledger"}
   {:item :rounding :article nil
     :why "端数処理 rules were not read"}
   {:item :interim-payments :article nil
    :why "中間納付税額 is deducted from the final figure and is not a ledger balance"}])

(defn- rate-of [chart account] (get-in chart [account :tax-rate]))
(defn- role-of [chart account] (get-in chart [account :tax-role]))

(defn summary
  "Aggregate the ledger's consumption-tax accounts, by rate.

      {:shohizei/coverage :no-tax-accounts}     the chart declares none
      {:shohizei/coverage :rates-not-declared}  accounts but no :tax-rate
      {:shohizei/coverage :aggregated  …}       with :by-rate and :difference

  **There is no `:tax-payable`.** `:shohizei/not-computed` says why.

  `:difference` is named that and nothing else. It is 仮受 minus 仮払 per
  rate — a working figure a bookkeeper recognises, and not 第四号's 残額."
  [chart postings]
  (let [balances (tb/balances postings)
        tax-accounts (into {} (filter (fn [[_ v]] (#{:output :input} (:tax-role v)))) chart)]
    (cond
      (empty? tax-accounts)
      {:shohizei/coverage :no-tax-accounts
       :shohizei/why "the chart declares no account with :tax-role :output or :input"}

      (some #(nil? (:tax-rate %)) (vals tax-accounts))
      {:shohizei/coverage :rates-not-declared
       :shohizei/accounts-without-rate
       (vec (sort (keep (fn [[a v]] (when (nil? (:tax-rate v)) a)) tax-accounts)))
       :shohizei/why (str "第四十五条第一項第一号・第二号 require 税率の異なる"
                          "ごとに区分した figures; one balance covering two rates "
                          "does not satisfy them and no arithmetic recovers the split")}

      :else
      {:shohizei/coverage :aggregated
       :shohizei/by-rate
       (reduce
        (fn [acc [[account currency] {:keys [balance]}]]
          (if-let [role (role-of chart account)]
            (if-not (#{:output :input} role)
              acc
              (let [rate (rate-of chart account)
                    ;; 仮受消費税 is credit-normal, 仮払消費税 debit-normal.
                    ;; Both are reported as positive magnitudes, because the
                    ;; article asks for 消費税額, not a signed balance.
                    amount (if (= :output role) (- balance) balance)]
                (update-in acc [[rate currency] role] (fnil + 0) amount)))
            acc))
        {}
        balances)
       :shohizei/not-computed not-computed
       :shohizei/provisions (mapv :article (:articles provisions))})))

(defn difference
  "`{[rate currency] n}` — 仮受 minus 仮払, per rate and currency.

  Empty for any coverage other than `:aggregated`, because there is nothing
  to subtract and returning zeros would look like a computed nil liability."
  [s]
  (if-not (= :aggregated (:shohizei/coverage s))
    {}
    (into {}
          (map (fn [[k {:keys [output input]}]]
                 [k (- (or output 0) (or input 0))]))
          (:shohizei/by-rate s))))

(defn filing-deadline
  "第四十五条第一項本文: 課税期間の末日の翌日から二月以内.

  Takes and returns `\"YYYY-MM-DD\"`. Nil for an unparseable date rather than
  a guess. The clamp convention when adding two months overflows a month end
  is the same one `kotoba-lang/taxlaw` documents for 起算日, and is reported
  the same way."
  [period-end]
  (when-let [[_ y m d] (some->> period-end (re-matches #"(\d{4})-(\d{2})-(\d{2})"))]
    (let [y (#?(:clj Long/parseLong :cljs js/parseInt) y)
          m (#?(:clj Long/parseLong :cljs js/parseInt) m)
          d (#?(:clj Long/parseLong :cljs js/parseInt) d)
          leap? (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400))))
          dim #(case (long %2) 1 31 2 (if %1 29 28) 3 31 4 30 5 31 6 30
                     7 31 8 31 9 30 10 31 11 30 12 31)
          t (+ (dec m) 2)
          y' (+ y (quot t 12))
          m' (inc (mod t 12))
          leap'? (and (zero? (mod y' 4)) (or (pos? (mod y' 100)) (zero? (mod y' 400))))
          d' (min d (dim leap'? m'))]
      {:deadline (str y' "-" (when (< m' 10) "0") m' "-" (when (< d' 10) "0") d')
       :article "第四十五条第一項本文"
       :clamped? (not= d d')})))
