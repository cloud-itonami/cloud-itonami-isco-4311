(ns bookkeeping.houjinzei
  "法人税申告 — what this client's books supply toward a corporate return,
  and what they cannot.

  The shape is `bookkeeping.shinkoku`'s, deliberately. That namespace folds
  what a ledger can say about a 消費税申告 and then stops, because
  第四十五条第一項第四号 is a subtraction of two things that are not ledger
  balances. The same stop applies here for a stronger reason, and the reason
  is in 法人税法 第二十二条 itself.

  ## Why there is no 所得の金額 here, and no 法人税の額

  第二十二条第一項 makes the figure 益金 minus 損金. 第二項 and 第三項 define
  those two — and both say **「別段の定めがあるものを除き」**. 第四項 then
  sends what is left to 「一般に公正妥当と認められる会計処理の基準」.

  Read together: a ledger computes the accounting figure, and the tax figure
  is that same figure **minus every 別段の定め**. Those provisions are the
  別表四 adjustments — 減価償却超過額, 交際費等の損金不算入, 役員給与, 受取配当
  等の益金不算入, and the rest. None of them is a posting. A namespace that
  reported 当期純利益 as 所得の金額 would be arithmetically clean, wrong, and
  silent about which.

  So `return-for` carries no income and no tax. What it carries is the part
  that IS a fact about the books:

  ```text
  第七十四条第一項 の期限        ← computed (民法 第百四十条・第百四十三条)
  第七十四条第三項 の添付書類    ← bookkeeping.statements, and whether whole
  第七十四条第一項 一〜六 の各号 ← named as :houjinzei/not-computed
  ```

  ## Sources

  法人税法 (340AC0000000034) 第二十二条・第七十四条, read 2026-08-19 via
  e-Gov law API v2, revision 340AC0000000034_20260812_508AC0000000064.
  民法 (129AC0000000089) 第百四十条・第百四十三条, same date, revision
  129AC0000000089_20260624_508AC0000000045.

  **A hazard worth writing down**: 法人税法 has FOUR articles numbered 七十四
  — one in 本則 and three in 附則. Reading 「第七十四条」 without checking
  which one returns 罰則に関する経過措置, not 確定申告. `kotoba.taxlaw`
  records this as `:rule/reading-hazard` on the facet."
  (:require [bookkeeping.statements :as statements]
            [bookkeeping.store :as store]
            [bookkeeping.posting :as posting]
            [kotoba.taxlaw :as taxlaw]
            [clojure.string :as str]))

(def provisions
  "The articles this file decides on, quoted verbatim with the retrieval and
  the revision, so a reader can re-fetch rather than trust this file. Same
  shape as `bookkeeping.shinkoku/provisions`.

  Two laws, so two ids. `:law/id` is the one this actor's own reasoning turns
  on; 民法 rides in `:also` because it decides the arithmetic and nothing
  else."
  {:law/id "340AC0000000034"
   :law/title "法人税法"
   :law/revision "340AC0000000034_20260812_508AC0000000064"
   :retrieved-at "2026-08-19"
   :retrieved-via "e-Gov law API v2 GET https://laws.e-gov.go.jp/api/2/law_data/340AC0000000034"
   :articles
   [{:article "法人税法 第二十二条第一項"
     :quote "内国法人の各事業年度の所得の金額は、当該事業年度の益金の額から当該事業年度の損金の額を控除した金額とする。"}
    {:article "法人税法 第二十二条第二項"
     :quote (str "内国法人の各事業年度の所得の金額の計算上当該事業年度の益金の額に算入すべき金額は、"
                 "別段の定めがあるものを除き、資産の販売、有償又は無償による資産の譲渡又は役務の提供、"
                 "無償による資産の譲受けその他の取引で資本等取引以外のものに係る当該事業年度の収益の額とする。")}
    {:article "法人税法 第二十二条第四項"
     :quote (str "第二項に規定する当該事業年度の収益の額及び前項各号に掲げる額は、別段の定めがあるものを除き、"
                 "一般に公正妥当と認められる会計処理の基準に従つて計算されるものとする。")}
    {:article "法人税法 第七十四条第一項"
     :quote (str "内国法人は、各事業年度終了の日の翌日から二月以内に、税務署長に対し、確定した決算に基づき"
                 "次に掲げる事項を記載した申告書を提出しなければならない。")}
    {:article "法人税法 第七十四条第三項"
     :quote (str "第一項の規定による申告書には、当該事業年度の貸借対照表、損益計算書その他の財務省令で"
                 "定める書類を添付しなければならない。")}]
   :also
   {:law/id "129AC0000000089"
    :law/title "民法"
    :law/revision "129AC0000000089_20260624_508AC0000000045"
    :retrieved-at "2026-08-19"
    :articles
    [{:article "民法 第百四十条"
      :quote (str "日、週、月又は年によって期間を定めたときは、期間の初日は、算入しない。"
                  "ただし、その期間が午前零時から始まるときは、この限りでない。")}
     {:article "民法 第百四十三条第二項"
      :quote (str "週、月又は年の初めから期間を起算しないときは、その期間は、最後の週、月又は年において"
                  "その起算日に応当する日の前日に満了する。ただし、月又は年によって期間を定めた場合において、"
                  "最後の月に応当する日がないときは、その月の末日に満了する。")}]}})

(def read-for
  "The jurisdictions whose CORPORATE RETURN articles this file actually read.

  Not a copy of `kotoba.taxlaw`'s catalog, for the reason
  `bookkeeping.shinkoku/read-for` gives: gating on the catalog would mean
  that the day a second jurisdiction is catalogued, 法人税法 第七十四条 would
  silently start applying to it. Adding a jurisdiction must not widen a
  pass."
  #{[:jp]})

;; ---------------------------------------------------------------------------
;; 事業年度
;; ---------------------------------------------------------------------------

(defn fiscal-year-problems
  "Why `fy` does not bound a 事業年度, as a vector. Empty means usable.

  Supplied, never inferred. A 事業年度 is fixed by the company's 定款 or by
  its 届出 — it is a fact about the corporation, not about its postings, and
  a book of entries cannot distinguish a company with a March year end from
  one that closes in December. Defaulting to the calendar year would answer
  for both."
  [fy]
  (if-not (map? fy)
    [{:problem :no-fiscal-year
      :detail (str "事業年度 must be {:from \"YYYY-MM-DD\" :to \"YYYY-MM-DD\"}: "
                   (pr-str fy))}]
    (let [{:keys [from to]} fy]
      (cond-> []
        (not (posting/valid-transaction-date? from))
        (conj {:problem :bad-from :detail (str ":from must be an ISO-8601 date: " (pr-str from))})
        (not (posting/valid-transaction-date? to))
        (conj {:problem :bad-to :detail (str ":to must be an ISO-8601 date: " (pr-str to))})
        (and (posting/valid-transaction-date? from)
             (posting/valid-transaction-date? to)
             (pos? (compare from to)))
        (conj {:problem :reversed
               :detail (str ":from is after :to and can contain nothing: " (pr-str fy))})))))

;; ---------------------------------------------------------------------------
;; 第七十四条第一項 の期限 — 民法 第百四十条・第百四十三条
;; ---------------------------------------------------------------------------

(defn- leap? [y]
  (and (zero? (mod y 4)) (or (not (zero? (mod y 100))) (zero? (mod y 400)))))

(defn- days-in-month [y m]
  (case m (1 3 5 7 8 10 12) 31 (4 6 9 11) 30 2 (if (leap? y) 29 28)))

(defn- parse-date [s]
  (let [[y m d] (str/split s #"-")]
    #?(:clj  [(Integer/parseInt y) (Integer/parseInt m) (Integer/parseInt d)]
       :cljs [(js/parseInt y 10) (js/parseInt m 10) (js/parseInt d 10)])))

(defn- fmt [[y m d]]
  (str y "-" (when (< m 10) "0") m "-" (when (< d 10) "0") d))

(defn- next-day [[y m d]]
  (if (< d (days-in-month y m))
    [y m (inc d)]
    (if (= m 12) [(inc y) 1 1] [y (inc m) 1])))

(defn- prev-day [[y m d]]
  (if (> d 1)
    [y m (dec d)]
    (if (= m 1)
      [(dec y) 12 31]
      [y (dec m) (days-in-month y (dec m))])))

(defn due-date
  "The 第七十四条第一項 deadline for a fiscal year ending on `fiscal-year-end`,
  as `{:houjinzei/due \"YYYY-MM-DD\" :houjinzei/basis ...}`, or nil for an
  unusable date.

  「各事業年度終了の日の翌日から二月以内」. 民法 第百四十条 does not exclude
  the first day here — the statute starts the period at 翌日, so it begins at
  午前零時 and the proviso applies. 民法 第百四十三条第二項 then ends it on
  **the day before** the corresponding day two months on, and its 但書 ends it
  on the last day of that month when there is no corresponding day.

  Both branches are reachable and both are tested:

      2026-03-31 fiscal year end -> 起算日 2026-04-01 -> 応当日 2026-06-01
                                 -> 満了 2026-05-31
      2026-12-30 fiscal year end -> 起算日 2026-12-31 -> 2月31日 は無い
                                 -> 満了 2027-02-28 (但書)

  **This is 第一項 only.** 第七十四条第二項 shortens it to one month for a
  liquidating company whose 残余財産 is settled, or to the day before the
  final distribution if that is sooner, and neither of those facts is in a
  book of postings. `return-for` says so rather than letting the 第一項
  answer stand for a company the second paragraph covers."
  [fiscal-year-end]
  (when (posting/valid-transaction-date? fiscal-year-end)
    (let [start (next-day (parse-date fiscal-year-end))
          [y m d] start
          m2 (+ m 2)
          [y2 m2] (if (> m2 12) [(inc y) (- m2 12)] [y m2])
          corresponding? (<= d (days-in-month y2 m2))]
      {:houjinzei/due (if corresponding?
                        (fmt (prev-day [y2 m2 d]))
                        (fmt [y2 m2 (days-in-month y2 m2)]))
       :houjinzei/reckoned-from (fmt start)
       :houjinzei/basis (if corresponding?
                          :civil-code-143-2
                          :civil-code-143-2-proviso)
       :houjinzei/provision "法人税法 第七十四条第一項 / 民法 第百四十三条第二項"})))

;; ---------------------------------------------------------------------------
;; 第七十四条第一項 各号 — what a book of postings cannot supply
;; ---------------------------------------------------------------------------

(def ^:private return-items
  "第七十四条第一項 一〜六, and for each one whether a ledger can supply it.

  Held as data rather than prose so `not-computed` cannot drift from the
  article: a caller sees the same six items the return has, in order, each
  with the reason it is or is not a fact about the books."
  [{:item 1 :name "当該事業年度の課税標準である所得の金額又は欠損金額"
    :supplied? false
    :why (str "第二十二条第一項 makes it 益金 minus 損金, and 第二項・第三項 "
              "both begin 「別段の定めがあるものを除き」. The 別段の定め are "
              "the 別表四 adjustments and none of them is a posting.")}
   {:item 2 :name "前号の所得の金額につき計算した法人税の額"
    :supplied? false
    :why "a function of item 1, which is not supplied"}
   {:item 3 :name "第六十八条・第六十九条の控除で控除しきれなかった金額"
    :supplied? false
    :why (str "所得税額控除・外国税額控除 are computed against item 2 and "
              "depend on withholding and foreign-tax records this actor "
              "does not hold")}
   {:item 4 :name "中間納付額を控除した金額"
    :supplied? false
    :why (str "requires the 中間申告書 filed for the same fiscal year, which "
              "is not in this book")}
   {:item 5 :name "前号の中間納付額で控除しきれなかった金額"
    :supplied? false
    :why "a function of item 4, which is not supplied"}
   {:item 6 :name "前各号に掲げる金額の計算の基礎その他財務省令で定める事項"
    :supplied? false
    :why "財務省令 not read"}])

(defn not-computed
  "The 第七十四条第一項 items this actor does not supply, each with its reason.

  Every one of the six, today. That is the honest total and it is not a
  placeholder: a corporate return's figures are tax-law figures, and this
  actor keeps books. Returning the list rather than an empty map is the
  point — a caller that wanted a number learns exactly which six things it
  still needs and why, instead of receiving a map that merely lacks keys."
  []
  (filterv (complement :supplied?) return-items))

;; ---------------------------------------------------------------------------
;; 第七十四条第三項 — the attachments, which the books CAN supply
;; ---------------------------------------------------------------------------

(defn attachments
  "Can this client's books produce the documents 第七十四条第三項 requires?

      {:houjinzei/required   [\"貸借対照表\" \"損益計算書\"]
       :houjinzei/producible :yes | :no
       :houjinzei/whole?     true | false
       :houjinzei/why        <when not producible or not whole>}

  `:producible` and `:whole?` are two questions and both are asked, because
  `bookkeeping.statements` answers them separately for a reason its own
  docstring gives: `:ok` means it could answer, and `complete?` means the
  answer is a finished statement. A balance sheet short by an unclassified
  account still balances, and attaching it to a return would be attaching a
  document that looks right.

  The required list is read from `kotoba.taxlaw`, not hard-coded here, so a
  change to the article moves in one place."
  [store client-id jurisdiction]
  (let [required (taxlaw/filing-attachments jurisdiction :corporate)
        r (statements/for-client store client-id)]
    (cond
      (nil? required)
      {:houjinzei/required nil
       :houjinzei/producible :no
       :houjinzei/why (str "kotoba.taxlaw holds no corporate filing attachments for "
                           (pr-str jurisdiction) "; this actor does not invent them")}

      (not= :ok (:statements/coverage r))
      {:houjinzei/required required
       :houjinzei/producible :no
       :houjinzei/why (or (:statements/why r)
                          (str "statements coverage is " (:statements/coverage r)))
       :houjinzei/chart-problems (:statements/chart-problems r)}

      :else
      {:houjinzei/required required
       :houjinzei/producible :yes
       :houjinzei/whole? (boolean (:statements/complete? r))
       :houjinzei/statements r
       :houjinzei/why (when-not (:statements/complete? r)
                        (str "the statements are produced but not whole: "
                             "unclassified accounts or an accounting equation "
                             "that does not hold. See :shohyo/unclassified and "
                             ":equation in :houjinzei/statements"))})))

;; ---------------------------------------------------------------------------
;; the return
;; ---------------------------------------------------------------------------

(defn return-for
  "What this client's books supply toward a 法人税申告 for one 事業年度.

  Takes `{:store s :client-id c :fiscal-year {:from d :to d}}`. The
  jurisdiction is read from the CLIENT record, never from the options, for
  the reason `bookkeeping.governor` reads it there: a caller able to pick its
  own jurisdiction could pick one whose rules it satisfies.

  `:houjinzei/status` is one of SIX values and only the last is a pass:

    :unchecked-jurisdiction  this file did not read that jurisdiction's
                             corporate return articles (see `read-for`).
                             Where `kotoba.taxlaw` has a stated reason it
                             rides along.
    :not-obliged-here        the catalogue read the jurisdiction but places
                             no unconditional corporate duty. Not a pass and
                             not a failure -- a different filing.
    :fiscal-year-not-bounded no usable 事業年度.
    :attachments-unavailable no chart, or an unusable one. Named.
    :attachments-not-whole   the two statements are produced but short: an
                             unclassified account, or an equation that does
                             not hold.
    :attachments-ready       the pass, and it means EXACTLY that the
                             第七十四条第三項 attachments can be produced
                             whole. **It does not mean the return can be
                             filed** -- all six items of 第七十四条第一項 are
                             still unsupplied, and they ride along in
                             :houjinzei/not-computed on every status
                             including this one.

  There is no `:houjinzei/income`, no `:houjinzei/tax`, and no `filable?`.
  `bookkeeping.shohizei` has no `:tax-payable` and `bookkeeping.shinkoku` has
  no `filable?` for the same reason: the number would be clean and wrong with
  nothing in the output saying so."
  [{:keys [store client-id fiscal-year]}]
  (let [client (store/client store client-id)
        jurisdiction (:jurisdiction client)
        path (if (vector? jurisdiction) jurisdiction (when (some? jurisdiction) [jurisdiction]))
        obligation (when path (taxlaw/filing-obligation path :corporate))
        problems (fiscal-year-problems fiscal-year)
        att (when (and (contains? read-for path) (empty? problems))
              (attachments store client-id path))
        status (cond
                 (not (contains? read-for path)) :unchecked-jurisdiction
                 (not= :yes (taxlaw/must-file? path :corporate)) :not-obliged-here
                 (seq problems) :fiscal-year-not-bounded
                 (not= :yes (:houjinzei/producible att)) :attachments-unavailable
                 (not (:houjinzei/whole? att)) :attachments-not-whole
                 :else :attachments-ready)]
    (cond-> {:houjinzei/status status
             :houjinzei/jurisdiction path
             ;; Carried on EVERY status, pass included. A caller that reads
             ;; :attachments-ready as "ready to file" is reading past six
             ;; items the statute requires and nobody supplied.
             :houjinzei/not-computed (not-computed)
             :houjinzei/provision "法人税法 第七十四条"}
      (seq problems) (assoc :houjinzei/fiscal-year-problems problems)
      att (assoc :houjinzei/attachments att)
      obligation (assoc :houjinzei/due-rule (:rule/due obligation)
                        :houjinzei/liquidation-exception-unread
                        (str "第七十四条第二項 shortens the deadline for a "
                             "liquidating company whose 残余財産 is settled. "
                             "Whether this client is one is not in its "
                             "postings, so the computed date below assumes "
                             "第一項."))
      (and (empty? problems) (:to fiscal-year))
      (merge (due-date (:to fiscal-year)))
      (and (= :unchecked-jurisdiction status) path)
      (assoc :houjinzei/why (taxlaw/out-of-scope path :jurisdiction/return-filing)))))

(defn attachments-ready?
  "Deliberately narrow, and named for what it answers.

  There is no `filable?` in this namespace and this is not one wearing a
  different hat: it says the two documents 第七十四条第三項 requires can be
  produced whole, and nothing about the six figures 第七十四条第一項 requires."
  [r]
  (= :attachments-ready (:houjinzei/status r)))
