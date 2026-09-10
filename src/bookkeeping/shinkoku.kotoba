(ns bookkeeping.shinkoku
  "消費税申告 — what this client's books supply toward a 消費税 return, and
  the inputs they cannot supply, each one named.

  `bookkeeping.shohizei` folds the tax accounts and stops, deliberately:
  there is no `:tax-payable` key there, because 第四十五条第一項第四号 is
  第二号 minus 第三号 and neither of those is a ledger balance. This
  namespace does not overturn that refusal — it **extends** it. There is no
  `:shinkoku/tax-payable` either, and no `filable?`, for the same reason the
  other file has no `payable`: the number would be arithmetically clean and
  wrong, with nothing in the output saying so.

  What is here instead is the part a ledger CAN answer:

  ```text
  課税期間 の境界        ← supplied (第十九条第一項: it is not in the books)
  期間に置けない仕訳      ← measured and named
  税率ごとの仮受/仮払     ← bookkeeping.shohizei
  第三十条第七項 の基礎   ← measured PER ENTRY and partitioned
  残りの入力             ← :shinkoku/not-computed, named
  ```

  ## The failure this namespace exists to prevent

  A return that credits input tax on an entry the governor HELD. Held
  entries never become postings, so the trial balance cannot contain them —
  that much is structural. The live hazard is the other one: the governor's
  第三十条第七項 rules **fire only on a proposal that claims
  `:tax-treatment :input-tax-credit`** (see `bookkeeping.governor`, rules 5
  and 6). An entry that debits 仮払消費税 and claims nothing was never
  checked against the article, and its input tax sits in the 仮払消費税
  balance indistinguishable from an entry whose 適格請求書 was verified.

  So `input-tax-basis` joins each posting back to the record that produced
  it — by recomputing `bookkeeping.posting/content-id`, which is exactly how
  `bookkeeping.actor` named the posting in the first place — and reports
  every posting whose basis was NOT checked **by id**, with which of the
  five reasons applies. A count would tell an operator that something is
  wrong and not which document to go and find.

  ## 課税標準額に対する消費税額 is NOT the 仮受消費税 balance

  Measured while writing this, and it is the trap that would have made the
  whole namespace a lie. 消費税法 第二十九条 sets the rate at **百分の七・八**
  (軽減 六・二四). 消費税法施行令 第七十条の十 — which `kotoba.taxlaw`
  implements as `consumption-tax-amount` — computes 消費税額等 at 10/100 and
  8/100, and 消費税額等 is 消費税 **plus 地方消費税**. Handing the invoice
  figure back as 第四十五条第一項第二号's 消費税額 would overstate the
  national tax by 10/7.8, on every return, forever.

  This namespace therefore does not call `consumption-tax-amount` at all,
  and reports no output tax. Two separate reasons, both in
  `:shinkoku/not-computed`: the ledger holds the combined figure rather than
  the national one, and whether the tax is computed 割戻し or 積上げ is
  消費税法施行令 第六十二条, **which was not read**.

  ## 積上げ / 割戻し, 免税事業者, 簡易課税 — refused, and why differently

  第九条第一項 and 第三十七条第一項 WERE read (see `provisions`), and reading
  them is what shows the refusal is structural rather than lazy: both hang on
  **基準期間における課税売上高** — the turnover of the period two years back —
  and on a 届出書 filed with the 税務署長. Neither is in this period's books.
  So the regime is an INPUT, read as strictly as `bookkeeping.kensaku` reads
  `declared?`, and anything that is not literally `:general`, `:exempt` or
  `:simplified` is `:regime-not-declared`.

  第九条第一項 also carries a carve-out worth stating, because it inverts the
  naive rule: 適格請求書発行事業者 are **excluded from the exemption**, so a
  registered issuer under ¥10,000,000 is not 免税.

  消費税法施行令 第六十二条（売上税額の積上げ計算）and 第四十六条（仕入税額）
  were NOT read. They govern an election a taxpayer really makes, and nothing
  here assumes either side of it. See `not-read`.

  ## The result is nine-valued and exactly one is a pass

  `:computed` — and `:computed` does NOT mean a return can be filed, the same
  way `bookkeeping.statements`'s `:ok` does not mean the statements are whole.
  It means this ledger supplied its part and the rest is named. The
  convenience predicate is called `computed?` and not `filable?`, because
  nothing in this namespace can answer the second one."
  (:require [bookkeeping.posting :as posting]
            [bookkeeping.shohizei :as sz]
            [bookkeeping.store :as store]
            [kotoba.taxlaw :as taxlaw]))

;; ---------------------------------------------------------------------------
;; What was read, and what was not
;; ---------------------------------------------------------------------------

(def provisions
  "消費税法, read verbatim from the e-Gov law API on the date below — not
  cited from memory and not paraphrased from a guidance page.

  The revision string is the same one `bookkeeping.shohizei` records, because
  both files read the same retrieval."
  {:law/id "363AC0000000108"
   :law/title "消費税法"
   :law/revision "363AC0000000108_20260401_508AC0000000012"
   :retrieved-at "2026-08-18"
   :retrieved-via (str "GET https://laws.e-gov.go.jp/api/2/law_data/"
                       "363AC0000000108?response_format=json")
   :articles
   [{:article "第九条第一項" :on :small-enterprise-exemption
     :quote (str "事業者のうち、その課税期間に係る基準期間における課税売上高が"
                 "千万円以下である者（適格請求書発行事業者を除く。）については、"
                 "第五条第一項の規定にかかわらず、その課税期間中に国内において"
                 "行つた課税資産の譲渡等及び特定課税仕入れにつき、消費税を納める"
                 "義務を免除する。ただし、この法律に別段の定めがある場合は、"
                 "この限りでない。")}
    {:article "第九条第四項" :on :election-to-be-taxable
     :quote (str "第一項本文の規定により消費税を納める義務が免除されることと"
                 "なる事業者が、…第一項本文の規定の適用を受けない旨を記載した"
                 "届出書をその納税地を所轄する税務署長に提出した場合には、…"
                 "同項本文の規定は、適用しない。")
     :quote-is-partial? true}
    {:article "第十九条第一項" :on :taxable-period
     :quote (str "この法律において「課税期間」とは、次の各号に掲げる事業者の"
                 "区分に応じ当該各号に定める期間とする。一 個人事業者…"
                 "一月一日から十二月三十一日までの期間 二 法人…事業年度 "
                 "三 …三月ごとの期間に短縮すること…についてその納税地を所轄する"
                 "税務署長に届出書を提出した個人事業者…")
     :quote-is-partial? true
     :quote-omits "第三号の二・第四号・第四号の二（一月ごと／法人の短縮）"}
    {:article "第二十九条" :on :rates
     :quote (str "消費税の税率は、次の各号に掲げる区分に応じ当該各号に定める率と"
                 "する。一 課税資産の譲渡等（軽減対象課税資産の譲渡等を除く。）、"
                 "特定課税仕入れ及び保税地域から引き取られる課税貨物（軽減対象"
                 "課税貨物を除く。）百分の七・八 二 軽減対象課税資産の譲渡等及び"
                 "保税地域から引き取られる軽減対象課税貨物 百分の六・二四")}
    {:article "第三十条第一項" :on :input-tax-credit
     :quote (str "事業者（第九条第一項本文の規定により消費税を納める義務が免除"
                 "される事業者を除く。）が、国内において行う課税仕入れ…に"
                 "ついては、…当該各号に定める日の属する課税期間の第四十五条第一項"
                 "第二号に掲げる消費税額…から、当該課税期間中に国内において行つた"
                 "課税仕入れに係る消費税額（当該課税仕入れに係る適格請求書…の"
                 "記載事項を基礎として計算した金額その他の政令で定めるところに"
                 "より計算した金額をいう。）…の合計額を控除する。"
                 "一 国内において課税仕入れを行つた場合 当該課税仕入れを行つた日")
     :quote-is-partial? true
     :quote-omits "特定課税仕入れ・課税貨物に係る部分と第二号から第四号"}
    {:article "第三十条第二項" :on :taxable-sales-ratio
     :quote (str "前項の場合において、同項に規定する課税期間における課税売上高が"
                 "五億円を超えるとき、又は当該課税期間における課税売上割合が"
                 "百分の九十五に満たないときは、…課税仕入れ等の税額…の合計額は、"
                 "同項の規定にかかわらず、次の各号に掲げる場合の区分に応じ当該"
                 "各号に定める方法により計算した金額とする。")
     :quote-is-partial? true
     :quote-omits "第一号（個別対応方式）・第二号（一括比例配分方式）の本文"}
    {:article "第三十条第七項" :on :credit-requires-preserved-records
     :quote (str "第一項の規定は、事業者が当該課税期間の課税仕入れ等の税額の控除"
                 "に係る帳簿及び請求書等（請求書等の交付を受けることが困難である"
                 "場合、特定課税仕入れに係るものである場合その他の政令で定める"
                 "場合における当該課税仕入れ等の税額については、帳簿）を保存しない"
                 "場合には、当該保存がない課税仕入れ、特定課税仕入れ又は課税貨物に"
                 "係る課税仕入れ等の税額については、適用しない。ただし、災害その他"
                 "やむを得ない事情により、当該保存をすることができなかつたことを"
                 "当該事業者において証明した場合は、この限りでない。")}
    {:article "第三十条第九項" :on :what-a-請求書等-is
     :quote (str "第七項に規定する請求書等とは、次に掲げる書類及び電磁的記録…を"
                 "いう。一 事業者に対し課税資産の譲渡等…を行う他の事業者（適格"
                 "請求書発行事業者に限る。…）が、当該課税資産の譲渡等につき当該"
                 "事業者に交付する適格請求書又は適格簡易請求書")
     :quote-is-partial? true
     :quote-omits "第二号から第四号"}
    {:article "第三十七条第一項" :on :simplified-taxation
     :quote (str "事業者（第九条第一項本文の規定により消費税を納める義務が免除"
                 "される事業者…を除く。）が、その納税地を所轄する税務署長にその"
                 "基準期間における課税売上高…が五千万円以下である課税期間…に"
                 "ついてこの項の規定の適用を受ける旨を記載した届出書を提出した"
                 "場合には、…第三十条から前条までの規定により課税標準額に対する"
                 "消費税額から控除することができる課税仕入れ等の税額の合計額は、"
                 "これらの規定にかかわらず、次に掲げる金額の合計額とする。")
     :quote-is-partial? true
     :quote-omits "第一号（百分の六十・みなし仕入率）・第二号（特定課税仕入れ）の本文"}]})

(def not-read
  "Statutes a 消費税申告 needs that were **not** read here.

  Present as data so nothing in this namespace can quietly depend on one. A
  rule enforced only in prose is not enforced, and a rule cited from memory
  is not read — every check in this file is backed by an article in
  `provisions` or by a fact about this file (see `read-for`)."
  [{:law "消費税法施行令 第六十二条" :on :output-tax-accumulation
    :why (str "売上税額の積上げ計算 — whether 課税標準額に対する消費税額 is "
              "computed 割戻し or 積上げ is an election, and this file has not "
              "read the article that grants it")}
   {:law "消費税法施行令 第四十六条" :on :input-tax-accumulation
    :why "仕入税額の積上げ／割戻し, the same election on the deduction side"}
   {:law "消費税法 第二十八条" :on :taxable-base
    :why "課税標準 = 対価の額; what a revenue balance must be reduced to is not read"}
   {:law "消費税法 第三十八条・第三十八条の二・第三十九条" :on :deduction-adjustments
    :why (str "売上げに係る対価の返還等・特定課税仕入れに係る返還等・貸倒れ — "
              "第四十五条第一項第三号 counts all three and this file read only "
              "the paragraph that names them")}
   {:law "地方税法 第七十二条の八十三" :on :local-consumption-tax
    :why (str "地方消費税 is what makes 施行令第七十条の十's 10/100 differ from "
              "第二十九条's 7.8/100; the ledger holds the combined figure and "
              "the split was not read")}
   {:law "国税通則法 第百十八条第一項" :on :rounding-of-the-base
    :why "課税標準額の千円未満切捨て was not read, so no base is rounded here"}
   {:law "消費税法施行令 第七十条の十" :on :qualified-invoice-tax-amount
    :why (str "read by kotoba.taxlaw and exposed as consumption-tax-amount, "
              "NOT re-read here — and deliberately not called: it answers the "
              "issuer's 消費税額等, which is not 第四十五条第一項第二号's figure")}])

(def read-for
  "The jurisdictions whose RETURN articles this file actually read.

  Not a copy of `kotoba.taxlaw`'s catalog — a statement about this file,
  which only this file can make. Gating on the catalog instead would mean
  that the day a third VAT jurisdiction is catalogued, 消費税法 第四十五条
  would silently start applying to it. Adding a jurisdiction must not widen
  a pass, and this is the shape that would have."
  #{[:jp]})

(def regimes
  "第九条第一項 / 第三十七条第一項 / 第三十条. The three a caller may declare,
  and only the first is the one this namespace computes for."
  #{:general :exempt :simplified})

;; ---------------------------------------------------------------------------
;; 課税期間
;; ---------------------------------------------------------------------------

(defn period-problems
  "Why `period` does not bound a 課税期間, as a vector. Empty means usable.

  A period is `{:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}`, inclusive at both
  ends, and it is SUPPLIED rather than inferred. 第十九条第一項 makes it a
  fact about the taxpayer: an 個人事業者 files on the calendar year, a 法人 on
  its 事業年度, and either can shorten to three-month or one-month periods by
  filing a 届出書. None of those three facts is in a book of postings, and
  defaulting to the calendar year would answer for the 法人 too."
  [period]
  (cond
    (not (map? period))
    [{:problem :no-period
      :detail (str "課税期間 must be {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}: "
                   (pr-str period))}]

    :else
    (let [{:keys [from to]} period]
      (cond-> []
        (not (posting/valid-transaction-date? from))
        (conj {:problem :bad-from :detail (str ":from must be an ISO-8601 date: " (pr-str from))})

        (not (posting/valid-transaction-date? to))
        (conj {:problem :bad-to :detail (str ":to must be an ISO-8601 date: " (pr-str to))})

        ;; A reversed period can never contain anything. Reported rather than
        ;; answered with zero entries — `impossible period` and `nothing
        ;; happened` are different findings, the same distinction
        ;; `bookkeeping.kensaku` draws for a reversed range.
        (and (posting/valid-transaction-date? from)
             (posting/valid-transaction-date? to)
             (pos? (compare from to)))
        (conj {:problem :reversed
               :detail (str ":from is after :to and can contain nothing: " (pr-str period))})))))

(defn placement
  "Where this posting falls relative to `period`: `:in`, `:out`, or
  `:unplaceable`.

  `:unplaceable` for a posting whose 取引年月日 is missing or is not an
  ISO-8601 date. **It is never `:out`.** An entry with no date might belong
  to this period; calling it `:out` would file the return without it and
  report nothing, which is the silent half of the same mistake as filing it
  in. `bookkeeping.posting` made 取引年月日 optional on purpose and
  `bookkeeping.kensaku/conformance` already counts the entries missing it —
  this is the same fact, asked from the return's side.

  The date used is the 記録項目 of 電子帳簿保存法施行規則 第五条第五項第一号ハ
  （１）, taken as 第三十条第一項第一号's 課税仕入れを行つた日. That
  identification is made here and is not verified — see
  `:shinkoku/not-computed`."
  [posting period]
  (let [d (:bookkeeping/transaction-date posting)]
    (cond
      (not (posting/valid-transaction-date? d)) :unplaceable
      (neg? (compare d (:from period))) :out
      (pos? (compare d (:to period))) :out
      :else :in)))

(defn unplaceable
  "Postings that cannot be placed in any 課税期間, named — `[{:posting id
  :transaction-date d}]`. Empty when every entry carries a usable 取引年月日."
  [postings]
  (into []
        (keep (fn [p]
                (when-not (posting/valid-transaction-date?
                           (:bookkeeping/transaction-date p))
                  {:posting (:ledger/posting p)
                   :transaction-date (:bookkeeping/transaction-date p)})))
        postings))

(defn in-period
  "The postings of `postings` that fall inside `period`."
  [postings period]
  (into [] (filter #(= :in (placement % period))) postings))

;; ---------------------------------------------------------------------------
;; 第三十条第七項 — was this entry's basis actually checked?
;; ---------------------------------------------------------------------------

(defn input-tax-of
  "`{[rate currency] amount}` — the input tax this ONE posting carries,
  according to `chart`.

  Debit-normal, like `bookkeeping.shohizei`: 仮払消費税 is an asset, so the
  amount is debits minus credits. A posting that touches no `:tax-role
  :input` account returns `{}`."
  [chart posting]
  (reduce (fn [acc {:keys [ledger/account ledger/side ledger/amount ledger/currency]}]
            (let [a (get chart account)]
              (if-not (= :input (:tax-role a))
                acc
                (update acc [(:tax-rate a) currency] (fnil + 0)
                        (if (= :debit side) amount (- amount))))))
          {}
          (:ledger/entries posting)))

(defn- record-posting-id
  "The posting id a committed record would have produced.

  Recomputed exactly as `bookkeeping.actor`'s `:commit` node does, so the
  join is the actor's own identity function rather than a second one that
  agrees until it doesn't. Only `:draft-entry` records produce postings; the
  others return nil and cannot match."
  [record]
  (let [{:keys [op source-doc lines transaction-date counterparty]} (:payload record)]
    (when (= :draft-entry op)
      (posting/content-id source-doc lines
                          :transaction-date transaction-date
                          :counterparty counterparty))))

(defn- basis-of
  "The one posting's 第三十条第七項 verdict: `[bucket detail]`."
  [record jurisdiction doc]
  (cond
    (nil? record)
    [:no-record
     {:why (str "この posting を生んだ record が store に無い。証憑も税務処理の"
                "主張も辿れないので、第三十条第七項の基礎は検査されていない。")}]

    (not= :input-tax-credit (:tax-treatment (:payload record)))
    [:no-tax-claim
     {:source-doc (:source-doc (:payload record))
      :tax-treatment (:tax-treatment (:payload record))
      :why (str "仮払消費税 を計上しているが :input-tax-credit を主張していない。"
                "governor の第三十条第七項の検査は主張したものにしか働かないので、"
                "この仕訳の基礎は一度も見られていない。")}]

    (nil? doc)
    [:source-doc-not-registered
     {:source-doc (:source-doc (:payload record))
      :why (str "引用された原始証憑が store に登録されていない。"
                "第三十条第九項の請求書等が在るかを判定できない。")}]

    :else
    (let [support (taxlaw/credit-support jurisdiction doc)]
      (cond
        (= :none (:taxlaw/coverage support))
        [:unchecked-jurisdiction
         {:source-doc (:doc-id doc)
          :taxlaw/out-of-scope (:taxlaw/out-of-scope support)
          :why (or (:taxlaw/why support)
                   "kotoba.taxlaw が この法域の仕入税額控除を扱っていない。")}]

        (false? (:taxlaw/supported? support))
        [:unsupported-document
         {:source-doc (:doc-id doc)
          :taxlaw/reason (:taxlaw/reason support)
          :registration-number (:registration-number doc)
          :why (str "適格請求書発行事業者の登録番号が第三十条第九項第一号を"
                    "満たさない。")}]

        :else
        [:checked
         {:source-doc (:doc-id doc)
          :registration-number (:registration-number doc)}]))))

(defn input-tax-basis
  "Partition the input tax of `postings` by whether 第三十条第七項's basis was
  actually checked for the entry that produced it.

      {:shinkoku/examined n            postings carrying input tax
       :shinkoku/checked [...]         basis verified, named
       :shinkoku/not-checked [...]     basis NOT verified, named
       :shinkoku/checked-amount {...}  per [rate currency]
       :shinkoku/not-checked-amount {...}}

  `:shinkoku/examined` is an evidence floor. An empty `:not-checked` out of
  zero postings examined and out of forty are different answers, and a
  partition that could not look must not report what one that looked and
  found nothing reports.

  **`:checked` is not `deductible`, and is not called that.** It means the
  entry cites a registered document whose 登録番号 satisfies
  第三十条第九項第一号 as far as `kotoba.taxlaw` can check a format. Whether
  the amount is finally deductible still turns on 第三十条第二項 — 課税売上割合
  and the 個別対応 / 一括比例 election — which is in `:shinkoku/not-computed`
  and is not answered anywhere in this repository."
  [{:keys [store client-id jurisdiction chart postings]}]
  (let [records (vec (store/records-of store client-id))
        by-id (into {} (keep (fn [r] (when-let [id (record-posting-id r)] [id r]))) records)
        rows (into []
                   (keep (fn [p]
                           (let [tax (input-tax-of chart p)]
                             (when (seq tax)
                               (let [record (get by-id (:ledger/posting p))
                                     doc (some->> (:source-doc (:payload record))
                                                  (store/source-doc store))
                                     [bucket detail] (basis-of record jurisdiction doc)]
                                 (merge {:posting (:ledger/posting p)
                                         :basis bucket
                                         :input-tax tax}
                                        detail))))))
                   postings)
        {checked true not-checked false} (group-by #(= :checked (:basis %)) rows)
        total (fn [rs] (reduce (fn [acc r] (merge-with + acc (:input-tax r))) {} rs))]
    {:shinkoku/examined (count rows)
     :shinkoku/provision "消費税法 第三十条第七項"
     :shinkoku/checked (vec (or checked []))
     :shinkoku/not-checked (vec (or not-checked []))
     :shinkoku/checked-amount (total checked)
     :shinkoku/not-checked-amount (total not-checked)}))

;; ---------------------------------------------------------------------------
;; What still cannot be computed
;; ---------------------------------------------------------------------------

(def not-computed
  "What stands between these figures and a filed 消費税申告書.

  Distinct from `bookkeeping.shohizei/not-computed`, which is about the
  FIGURES; this is about the RETURN. Both travel in the result — shohizei's
  under `:shinkoku/ledger-figures` — so a reader gets each at its own scope
  rather than one merged list that belongs to neither.

  Every entry says WHERE the article was read, in `:read-by`:

      :bookkeeping.shinkoku   read here, verbatim, see `provisions`
      :bookkeeping.shohizei   read there, same retrieval and revision
      nil                     NOT read — and then it must appear in
                              `not-read`, and nothing in this file may
                              branch on it

  The suite holds all three of those to each other, so a statute cannot
  quietly acquire the authority of one that was read."
  [{:item :output-tax :article "第四十五条第一項第二号"
    :read? true :read-by :bookkeeping.shohizei
    :why (str "課税標準額に対する消費税額 は 第二十九条 の 7.8/100（軽減 "
              "6.24/100）による国税額であり、帳簿の 仮受消費税 は 施行令"
              "第七十条の十 の 10/100・8/100 による消費税額等（地方消費税を"
              "含む）である。両者を同じものとして扱うと国税額を 10/7.8 倍に"
              "膨らませる。")}
   {:item :output-tax-method :article "消費税法施行令 第六十二条"
    :read? false :read-by nil
    :why "割戻し計算 か 積上げ計算 かは事業者の選択であり、その条文を読んでいない"}
   {:item :input-tax-method :article "消費税法施行令 第四十六条"
    :read? false :read-by nil
    :why "仕入税額の積上げ／割戻しも同じ選択であり、その条文を読んでいない"}
   {:item :taxable-base :article "第四十五条第一項第一号"
    :read? true :read-by :bookkeeping.shohizei
    :why (str "課税標準である金額 は 第二十八条 の 対価の額 であって収益勘定の"
              "残高ではない。chart は 収益勘定に 課税／非課税／免税／不課税 の"
              "区分を宣言していないので、課税売上と非課税売上を分けられない。"
              "第二十八条 自体も読んでいない。")}
   {:item :taxable-sales-ratio :article "第三十条第二項"
    :read? true :read-by :bookkeeping.shinkoku
    :why (str "課税売上高が五億円超 または 課税売上割合が95%未満 のとき控除額は"
              "個別対応方式／一括比例配分方式で計算する。課税売上割合は課税売上と"
              "非課税売上の区分を要し、chart はそれを宣言していない。選択の"
              "届出も帳簿には無い。")}
   {:item :timing-of-supply :article "第三十条第一項"
    :read? true :read-by :bookkeeping.shinkoku
    :why (str "条文が定める 課税仕入れを行つた日 として、電子帳簿保存法施行規則"
              "第五条第五項第一号ハ（１）の 取引年月日 を用いている。この同一視は"
              "ここで行ったものであって検証していない。")}
   {:item :deduction-adjustments :article "第四十五条第一項第三号"
    :read? true :read-by :bookkeeping.shohizei
    :why (str "第三号 は 仕入れに係る消費税額 に 売上げに係る対価の返還等（第三十"
              "八条）・特定課税仕入れに係る返還等（第三十八条の二）・貸倒れ（第三"
              "十九条）を加える。この三条は読んでいない。")}
   {:item :local-consumption-tax :article "地方税法 第七十二条の八十三"
    :read? false :read-by nil
    :why "地方消費税の税率と申告は読んでいない。帳簿の税額は両者の合計である。"}
   {:item :base-rounding :article "国税通則法 第百十八条第一項"
    :read? false :read-by nil
    :why "課税標準額の千円未満切捨てを読んでいないので、いかなる端数処理も行わない"}
   {:item :interim-payments :article nil :read? false :read-by nil
    :why "中間納付税額は最終税額から控除されるが、帳簿残高ではない"}])

;; ---------------------------------------------------------------------------
;; the return
;; ---------------------------------------------------------------------------

(defn- why-for [status ctx]
  (case status
    :unchecked-jurisdiction
    (str "法域 " (pr-str (:jurisdiction ctx)) " の申告条文をこのファイルは"
         "読んでいない（読んだのは " (pr-str (vec (sort read-for))) " のみ）。"
         (when-let [w (:out-of-scope-why ctx)] (str "kotoba.taxlaw: " w "。"))
         "未検査は合格ではない。")
    :regime-not-declared
    (str "免税事業者（第九条第一項）か 簡易課税（第三十七条第一項）か 一般課税かが"
         "宣言されていない。どちらも 基準期間における課税売上高 と 届出書 に"
         "かかっており、当期の帳簿には無い。未宣言は合格ではない。")
    :regime-not-general
    (str "宣言された課税方式は " (pr-str (:regime ctx)) " であり、"
         (if (= :exempt (:regime ctx))
           "第九条第一項により納税義務が免除される（第三十条第一項も仕入税額控除から除外する）。"
           "第三十七条第一項により控除額は みなし仕入率 で計算され、第三十条 は適用されない。")
         " これは別の申告であって、この計算の合格ではない。")
    :period-not-bounded
    (str "課税期間が定まっていない。第十九条第一項 により課税期間は 個人事業者なら"
         "暦年、法人なら事業年度、届出があれば三月／一月ごとであって、帳簿から"
         "導けない。")
    :entries-not-placeable
    (str (:count ctx) " 件の仕訳が 取引年月日 を欠くか ISO-8601 でないため、"
         "いかなる課税期間にも置けない。黙って期間内に落とせば申告に混入し、"
         "期間外に落とせば黙って落ちる。")
    :no-entries
    (if (zero? (:entry-count ctx))
      "帳簿が空である。空の帳簿は申告できると示されたのではなく、空であると示されただけである。"
      (str "帳簿に " (:entry-count ctx) " 件あるが、この課税期間に置かれるものは無い。"))
    :figures-not-aggregable
    (str "税率ごとの集計ができない（bookkeeping.shohizei: "
         (pr-str (:coverage ctx)) "）。第四十五条第一項第一号・第二号 は"
         "税率の異なるごとに区分した金額を要求する。")
    :input-tax-unverified
    (str (:count ctx) " 件の仕訳が 仮払消費税 を計上しているが、第三十条第七項 の"
         "基礎が検査されていない。governor の検査は :input-tax-credit を主張した"
         "提案にしか働かないので、主張しなかった仕訳の控除可否は誰も見ていない。")
    :computed
    (str "この課税期間の仕訳はすべて置かれ、税率ごとに区分され、仮払消費税を計上する"
         " " (:examined ctx) " 件すべてについて第三十条第七項の基礎が検査された。"
         "納付税額は算出していない — :shinkoku/not-computed を参照。")))

(defn return-for
  "What this client's books supply toward a 消費税申告 for one 課税期間.

  Takes `{:store s :client-id c :period {:from d :to d} :regime kw}`. The
  jurisdiction is read from the CLIENT record, never from the options —
  `bookkeeping.governor` takes it from there for the same reason, that a
  caller able to pick its own jurisdiction could pick one whose rules it
  satisfies.

  `:shinkoku/status` is one of NINE values and only the last is a pass:

    :unchecked-jurisdiction  this file did not read that jurisdiction's
                             return articles (see `read-for`). Where
                             `kotoba.taxlaw` has a stated reason — the United
                             States has no federal consumption tax, the VAT
                             Directive fixes no rounding — it rides along.
    :regime-not-declared     免税 / 簡易課税 / 一般 was not stated. Read
                             strictly: anything that is not literally one of
                             `regimes` lands here.
    :regime-not-general      declared 免税事業者 or 簡易課税. A different
                             return, not a lenient version of this one.
    :period-not-bounded      no usable 課税期間 (第十九条第一項).
    :entries-not-placeable   some entry has no usable 取引年月日. Named.
    :no-entries              the book is empty, or nothing falls in the
                             period. The counts distinguish them.
    :figures-not-aggregable  `bookkeeping.shohizei` could not separate by
                             rate. Its coverage and reason ride along.
    :input-tax-unverified    input tax whose 第三十条第七項 basis was never
                             checked. **Named**, by posting and document.
    :computed                the pass.

  Every non-pass still carries the figures it did reach, the same way
  `bookkeeping.kensaku/conformance` reports the whole picture with a
  `:non-conformant` verdict. A caller must be able to act, and a bare status
  is not something anyone can act on."
  [{:keys [store client-id period regime]}]
  (let [client (store/client store client-id)
        jurisdiction (:jurisdiction client)
        chart (store/chart-of store client-id)
        postings (vec (store/postings-of store client-id))
        problems (period-problems period)
        stranded (when (empty? problems) (unplaceable postings))
        scoped (when (empty? problems) (in-period postings period))
        figures (when (seq scoped) (sz/summary chart scoped))
        basis (when (= :aggregated (:shohizei/coverage figures))
                (input-tax-basis {:store store :client-id client-id
                                  :jurisdiction jurisdiction :chart chart
                                  :postings scoped}))
        status (cond
                 (not (contains? read-for (if (vector? jurisdiction)
                                            jurisdiction
                                            (when (some? jurisdiction) [jurisdiction]))))
                 :unchecked-jurisdiction

                 (not (contains? regimes regime)) :regime-not-declared
                 (not= :general regime) :regime-not-general
                 (seq problems) :period-not-bounded
                 (seq stranded) :entries-not-placeable
                 (empty? scoped) :no-entries
                 (not= :aggregated (:shohizei/coverage figures)) :figures-not-aggregable
                 (seq (:shinkoku/not-checked basis)) :input-tax-unverified
                 :else :computed)]
    {;; the provisions are stamped only where they reach — citing 消費税法 on
     ;; a verdict about books kept elsewhere would put the same mistake in a
     ;; different key, which is how `bookkeeping.kensaku` stamps its ordinance.
     :shinkoku/provisions (when (not= :unchecked-jurisdiction status)
                            (mapv :article (:articles provisions)))
     :shinkoku/law-source (when (not= :unchecked-jurisdiction status)
                            (select-keys provisions [:law/id :law/title :law/revision
                                                     :retrieved-at :retrieved-via]))
     :shinkoku/not-read not-read
     :shinkoku/jurisdiction jurisdiction
     :shinkoku/regime (when (contains? regimes regime) regime)
     :shinkoku/period period
     :shinkoku/period-problems problems
     :shinkoku/status status
     :shinkoku/entry-count (count postings)
     :shinkoku/in-period-count (count scoped)
     :shinkoku/unplaceable (vec stranded)
     :shinkoku/ledger-figures figures
     :shinkoku/input-tax-basis basis
     :shinkoku/not-computed not-computed
     :shinkoku/why (why-for status
                            {:jurisdiction jurisdiction
                             :out-of-scope-why
                             (taxlaw/out-of-scope jurisdiction
                                                  :jurisdiction/qualified-invoice-tax-amount)
                             :regime regime
                             :count (if (= :entries-not-placeable status)
                                      (count stranded)
                                      (count (:shinkoku/not-checked basis)))
                             :entry-count (count postings)
                             :coverage (:shohizei/coverage figures)
                             :examined (:shinkoku/examined basis)})}))

(defn computed?
  "Convenience boolean over `return-for`, conservative in the same way
  `bookkeeping.kensaku/conformant?` is: everything that is not `:computed` —
  the undeclared, the 免税, the unplaceable and the unverified alike — comes
  back false.

  **It is not called `filable?`.** Nothing here can answer that: `:computed`
  means the ledger supplied its part, and `:shinkoku/not-computed` lists the
  inputs that are still missing. A predicate named for filing would be read
  as an answer about filing."
  [opts]
  (= :computed (:shinkoku/status (return-for opts))))
