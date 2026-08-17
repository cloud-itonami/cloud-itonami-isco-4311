(ns bookkeeping.jurisdictions
  "What a jurisdiction actually requires of a bookkeeping record, and the
  primary sources that say so.

  The governor already refuses a journal entry that cites no source
  document (`:no-source-doc`) — but until this namespace existed, NOTHING
  IN THIS REPO SAID WHAT MAKES A SOURCE DOCUMENT LEGALLY SUFFICIENT. The
  invariant was arithmetic and provenance only: a receipt this actor has
  never seen the law about counted exactly as much as one that satisfies
  it. That is the bookkeeping edition of scoring a test suite that cannot
  fail — the check passed because it never asked the question.

  This catalog is the question. It is data, not prose: `bookkeeping.governor`
  reads it, so a jurisdiction that is not in here cannot silently be treated
  as satisfied.

  ## The rule this namespace exists for

  AN UNCHECKED JURISDICTION IS A HOLD, NOT A PASS.

  Same shape as `kintai`'s `:unchecked-law`, and the same reason as
  `cloud.itonami.app.funding`'s three-valued freshness: an unknown figure
  is neither zero nor unlimited. A proposal that claims a tax consequence
  in a jurisdiction this catalog does not cover is held — not approved
  under a default, not rejected as invalid. Held, because nobody checked.

  ## What was verified, and what was not

  `:catalog/verification` records this exactly, because the two are
  different claims and conflating them is how a citation list becomes
  decoration:

    reachability — every URL below returned HTTP 200 on 2026-08-17, by
                   GET with redirects followed. Re-checkable at any time
                   with `tools/verify_citations.cljs`.
    content      — verified for ONE claim only: the qualified-invoice
                   registration-number format, read off the NTA's own
                   publication site, which states 「\"T\"を除く13桁の半角数字」.
                   Everything else here cites the instrument WITHOUT
                   quoting article-level text, and is marked
                   `:rule/review :reachable-not-read`.

  Do not promote `:reachable-not-read` to a legal opinion. This actor does
  not render one (`toritate` states the same ceiling), and neither does
  this file. What the catalog buys is that the governor can now distinguish
  `checked and satisfied` from `nobody looked`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; sources — every entry fetched 2026-08-17, HTTP 200
;; ---------------------------------------------------------------------------

(def sources
  "Primary sources, keyed by id. `:source/authority` is the issuing body,
  not the mirror we happened to read: e-Gov is the government's own
  法令検索, and 国税庁 pages are the operative administrative guidance."
  {:jp/dencho-ho
   {:source/title "電子計算機を使用して作成する国税関係帳簿書類の保存方法等の特例に関する法律（電子帳簿保存法）"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/410AC0000000025"}

   :jp/dencho-kisoku
   {:source/title "電子帳簿保存法施行規則"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/410M50000040043"}

   :jp/shohizei-ho
   {:source/title "消費税法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/363AC0000000108"}

   :jp/hojinzei-ho
   {:source/title "法人税法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/340AC0000000034"}

   :jp/shotokuzei-ho
   {:source/title "所得税法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/340AC0000000033"}

   :jp/kaisha-ho
   {:source/title "会社法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/417AC0000000086"}

   :jp/kaisha-keisan-kisoku
   {:source/title "会社計算規則"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/418M60000010013"}

   :jp/shoho
   {:source/title "商法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/url "https://laws.e-gov.go.jp/law/132AC0000000048"}

   :jp/nta-invoice
   {:source/title "インボイス制度（適格請求書等保存方式）"
    :source/authority "国税庁"
    :source/url "https://www.nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/invoice.htm"}

   :jp/nta-invoice-kohyo
   {:source/title "適格請求書発行事業者公表サイト"
    :source/authority "国税庁"
    :source/url "https://www.invoice-kohyo.nta.go.jp/"}

   :jp/nta-6496
   {:source/title "タックスアンサー No.6496 仕入税額控除"
    :source/authority "国税庁"
    :source/url "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shohi/6496.htm"}

   :jp/nta-5930
   {:source/title "タックスアンサー No.5930 帳簿書類等の保存期間"
    :source/authority "国税庁"
    :source/url "https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5930.htm"}

   :jp/nta-jirei
   {:source/title "法令解釈通達・質疑応答事例"
    :source/authority "国税庁"
    :source/url "https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/index.htm"}

   :jp/e-tax
   {:source/title "e-Tax 国税電子申告・納税システム"
    :source/authority "国税庁"
    :source/url "https://www.e-tax.nta.go.jp/"}

   :jp/jicpa
   {:source/title "日本公認会計士協会"
    :source/authority "日本公認会計士協会"
    :source/url "https://jicpa.or.jp/"}})

(def catalog-verification
  "Reachability was measured; content was not, except where noted.
  Keeping these apart is the whole point — `#{200}` proves a URL resolves,
  which is a far weaker claim than `this page says what I said it says`."
  {:catalog/verified-at "2026-08-17"
   :catalog/method :http-get-follow-redirects
   :catalog/status 200
   :catalog/reachable-count (count sources)
   :catalog/content-verified
   [{:claim :qualified-invoice-registration-format
     :source :jp/nta-invoice-kohyo
     :quote "「T」を除く13桁の半角数字"}]
   :catalog/not-verified
   "article-level text of the statutes above was not read; cited as instruments only"
   :catalog/rejected
   [{:url "https://www.asb.or.jp/"
     :why "connection timed out on two attempts (25s, 40s) — an unfetchable citation is not a citation"}
    {:url "https://www.chusho.meti.go.jp/zaimu/youryou/"
     :why (str "403 to a plain client; 200 only when curl sends a browser "
               "User-Agent. Sending one to get past that would make the "
               "citation unverifiable by this repo's own gate, so 中小企業の"
               "会計に関する基本要領 is recorded as uncited rather than cited "
               "on a header we spoofed.")}]})

;; ---------------------------------------------------------------------------
;; jurisdictions
;; ---------------------------------------------------------------------------

(def ^:private jp-registration-number
  ;; NTA publication site: 登録番号 = "T" + 13 digits. `re-matches` anchors the
  ;; whole string on both JVM and JS, so no \A/\z (JS has neither).
  #"T\d{13}")

(def jurisdictions
  {:jp
   {:jurisdiction/id :jp
    :jurisdiction/label "日本"
    :jurisdiction/as-of "2026-08-17"

    ;; 仕入税額控除 requires a 適格請求書 carrying the issuer's registration
    ;; number. This is the one rule whose FORMAT was read off the source.
    :jurisdiction/input-tax-credit
    {:rule/requires-qualified-invoice? true
     :rule/registration-number-pattern jp-registration-number
     :rule/registration-number-example "T1234567890123"
     :rule/review :reachable-not-read
     :rule/format-review :read-from-source
     :rule/sources [:jp/shohizei-ho :jp/nta-invoice :jp/nta-6496 :jp/nta-invoice-kohyo]}

    :jurisdiction/retention
    {:rule/years 7
     :rule/review :reachable-not-read
     :rule/sources [:jp/hojinzei-ho :jp/nta-5930]}

    :jurisdiction/electronic-transaction
    {:rule/must-store-electronically? true
     :rule/review :reachable-not-read
     :rule/sources [:jp/dencho-ho :jp/dencho-kisoku]}

    :jurisdiction/sources (vec (sort (keys sources)))}})

(defn covered?
  "Is `jurisdiction-id` in the catalog? `nil` is NOT covered — an
  undeclared jurisdiction is the unchecked case, not a default one."
  [jurisdiction-id]
  (contains? jurisdictions jurisdiction-id))

(defn jurisdiction [jurisdiction-id] (get jurisdictions jurisdiction-id))

(defn source [source-id] (get sources source-id))

(defn source-urls
  "Every URL in the catalog. `tools/verify_citations.cljs` re-fetches these."
  []
  (vec (sort (map :source/url (vals sources)))))

(defn registration-number-valid?
  "Does `n` satisfy `jurisdiction-id`'s qualified-invoice registration
  format? False for an uncovered jurisdiction and for `nil` — this
  function never answers `yes` on absence of information."
  [jurisdiction-id n]
  (boolean
   (when-let [pat (get-in jurisdictions
                          [jurisdiction-id :jurisdiction/input-tax-credit
                           :rule/registration-number-pattern])]
     (and (string? n)
          (not (str/blank? n))
          (some? (re-matches pat n))))))

(defn requires-qualified-invoice?
  "Does `jurisdiction-id` condition input-tax credit on a qualified
  invoice? Nil for an uncovered jurisdiction — deliberately NOT false,
  so a caller cannot read `unknown` as `no requirement`."
  [jurisdiction-id]
  (get-in jurisdictions
          [jurisdiction-id :jurisdiction/input-tax-credit
           :rule/requires-qualified-invoice?]))
