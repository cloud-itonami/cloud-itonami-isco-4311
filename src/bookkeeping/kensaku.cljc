(ns bookkeeping.kensaku
  "検索機能 — 電子帳簿保存法施行規則 第五条第五項第一号ハ.

  Retrieved 2026-08-18 from the e-Gov law API,
  `GET https://laws.e-gov.go.jp/api/2/law_data/410M50000040043?response_format=json`
  (平成十年大蔵省令第四十三号):

      ハ　当該国税関係帳簿に係る電磁的記録の記録事項の検索をすることができる
      　　機能（次に掲げる要件を満たすものに限る。）を確保しておくこと。
      （１）取引年月日、取引金額及び取引先（（２）及び（３）において「記録項目」
      　　　という。）を検索の条件として設定することができること。
      （２）日付又は金額に係る記録項目については、その範囲を指定して条件を
      　　　設定することができること。
      （３）二以上の任意の記録項目を組み合わせて条件を設定することができること。

  ## Why the search came second

  Until 2026-08-18 a journal entry in this actor recorded `:source-doc` and
  `:lines` and nothing else. **Two of the three 記録項目 did not exist.** A
  search written over what was stored would have run, returned rows, and
  satisfied none of （１） while looking exactly like a search that did —
  the shape this fleet keeps finding, where the check that could not look
  returns the same value as the check that looked and found nothing. So
  `bookkeeping.posting` learned to carry 取引年月日 and 取引先 first, and this
  namespace searches over all three.

  ## 取引金額 for an entry with many lines

  **The sum of the DEBIT side, per currency.** Stated rather than left to be
  inferred, because a reader can reasonably guess three other things (the
  credit side, the largest line, the sum of everything).

  Debits because in a balanced entry the debit total equals the credit total,
  so it is the amount of the transaction rather than of one leg — and where
  the entry is NOT balanced the debit side is the one the entry claims to
  have spent. The credit side would answer the same for every entry that
  qualifies and differently for every entry that does not, which is the worse
  half of the deal.

  **Per currency, and never summed across them.** This actor shipped exactly
  that bug once — 5000 JPY debit against 5000 USD credit balanced — and
  `bookkeeping.trial-balance` and `bookkeeping.motochou` both key on
  `[account currency]` because of it. An entry's 取引金額 is therefore a map
  `{currency total}`, an amount condition matches when AT LEAST ONE currency's
  total satisfies it, and the result says which currencies matched. Adding
  ¥5000 to $50 to get 5050 would produce a number that matches range queries
  and means nothing.

  ## What a no-condition search returns

  **Not everything.** `{:kensaku/coverage :no-conditions}` carries no
  `:kensaku/results` key at all, and the route turns it into a 400. Returning
  the whole book would make `I applied no filter` and `nothing matched`
  the same answer to a caller counting rows — and this actor already has a
  route that hands back the whole book on purpose (`GET /api/journal`).

  ## What this namespace does NOT decide

  Whether the 保存義務者 is claiming 法第八条第四項（優良帳簿, the
  過少申告加算税 reduction) at all. Ordinary preservation under 法第四条第一項
  requires no search function whatsoever, so ハ only bites on a claim the
  software cannot observe. `conformance` therefore has values meaning
  `not declared` and `not applicable`, and NEITHER of them is a pass — the
  same discipline `kotoba-lang/taxlaw` uses for an uncatalogued jurisdiction
  and `kotoba-lang/worklaw` for an unexamined one."
  (:require [clojure.string :as str]
            [bookkeeping.posting :as posting]
            [kotoba.taxlaw :as taxlaw]))

(def provision
  "The provision this namespace implements, cited where it is answered rather
  than only in a comment."
  "電子帳簿保存法施行規則 第五条第五項第一号ハ")

(def law-source
  "Where the text above came from and when, so a reader can re-retrieve it
  rather than trust this file."
  {:law-id "410M50000040043"
   :law-name "電子帳簿保存法施行規則（平成十年大蔵省令第四十三号）"
   :url "https://laws.e-gov.go.jp/api/2/law_data/410M50000040043?response_format=json"
   :retrieved "2026-08-18"})

(def record-item-keys
  "The three 記録項目 of ハ（１）, and no others. Data rather than a literal
  in three places, so `parse-query`, `validate` and `conformance` cannot
  drift into disagreeing about what the statute names."
  [:transaction-date :amount :counterparty])

(def rangeable
  "ハ（２）: `日付又は金額に係る記録項目`. 取引先 is deliberately absent — a
  range on a counterparty name is not a thing the provision grants, and
  accepting one silently as an equality would answer a question the caller
  did not ask."
  #{:transaction-date :amount})

;; ---------------------------------------------------------------------------
;; An entry's 記録項目
;; ---------------------------------------------------------------------------

(defn debit-totals
  "`{currency debit-total}` for one posting — the entry's 取引金額, per
  currency. See the namespace docstring for why the debit side and why never
  summed across currencies."
  [posting]
  (reduce (fn [acc {:keys [ledger/side ledger/amount ledger/currency]}]
            (if (= :debit side)
              (update acc currency (fnil + 0) amount)
              acc))
          {}
          (:ledger/entries posting)))

(defn record-items
  "The three 記録項目 of one committed posting, plus its id and lines.

  `:transaction-date` and `:counterparty` are `nil` when the entry carried
  none, and the key is present either way — an entry with no 取引先 and an
  entry nobody recorded a 取引先 for are the same thing here and a caller
  must be able to see it, which is why `conformance` can count them."
  [posting]
  {:posting (:ledger/posting posting)
   :transaction-date (:bookkeeping/transaction-date posting)
   :counterparty (:bookkeeping/counterparty posting)
   :amount (debit-totals posting)
   :lines (mapv (fn [e] {:account (:ledger/account e)
                         :side (:ledger/side e)
                         :amount (:ledger/amount e)
                         :currency (:ledger/currency e)})
                (:ledger/entries posting))})

(defn missing-record-items
  "Which of the three 記録項目 this entry cannot be searched by.

  An empty `:amount` map counts: an entry with no debit line has no 取引金額
  under the reading above, and reporting it as searchable would be the
  flattering answer."
  [posting]
  (let [r (record-items posting)]
    (cond-> []
      (nil? (:transaction-date r)) (conj :transaction-date)
      (empty? (:amount r)) (conj :amount)
      (nil? (:counterparty r)) (conj :counterparty))))

;; ---------------------------------------------------------------------------
;; Conditions
;; ---------------------------------------------------------------------------

(defn- range-condition? [c] (map? c))

(defn- problem [item kind detail]
  {:condition item :problem kind :detail detail})

(defn- validate-range
  "A `{:from x :to x}` condition on a rangeable 記録項目."
  [item c valid? type-name]
  (let [extra (remove #{:from :to} (keys c))]
    (cond
      (seq extra)
      [(problem item :unknown-range-key
                (str "a range takes :from and :to only; got " (pr-str (vec extra))))]

      (not (or (contains? c :from) (contains? c :to)))
      [(problem item :empty-range
                "a range must set at least one of :from / :to — neither is not a condition")]

      (seq (remove (fn [[_ v]] (valid? v)) c))
      [(problem item :not-a-value
                (str "range bounds must be " type-name ": " (pr-str c)))]

      ;; A reversed range can never match. Reported rather than answered with
      ;; zero rows: "impossible query" and "nothing matched" are different
      ;; findings and only one of them is about the books.
      (and (contains? c :from) (contains? c :to)
           (pos? (compare (:from c) (:to c))))
      [(problem item :reversed-range
                (str ":from is after :to and can never match: " (pr-str c)))]

      :else [])))

(defn validate
  "Problems with a typed conditions map, as a vector. Empty means usable.

  Note this does NOT report the empty map as a problem — an empty conditions
  map is `:no-conditions`, which `search` answers separately, because
  `you asked nothing` is not the same finding as `you asked wrongly`."
  [conditions]
  (if-not (map? conditions)
    [(problem nil :not-a-map (str "conditions must be a map: " (pr-str conditions)))]
    (into []
          (mapcat
           (fn [[item c]]
             (cond
               (not (some #{item} record-item-keys))
               [(problem item :unknown-condition
                         (str "規則第五条第五項第一号ハ（１） names "
                              (pr-str record-item-keys) " and nothing else"))]

               (and (range-condition? c) (not (rangeable item)))
               [(problem item :range-not-permitted
                         (str "ハ（２） grants ranges on 日付 and 金額 only; "
                              "a range on " (name item)
                              " is not a condition this provision defines"))]

               (= :transaction-date item)
               (if (range-condition? c)
                 (validate-range item c posting/valid-transaction-date? "ISO-8601 YYYY-MM-DD dates")
                 (when-not (posting/valid-transaction-date? c)
                   [(problem item :not-a-date
                             (str "取引年月日 must be ISO-8601 YYYY-MM-DD: " (pr-str c)))]))

               (= :amount item)
               (if (range-condition? c)
                 (validate-range item c number? "numbers")
                 (when-not (number? c)
                   [(problem item :not-a-number
                             (str "取引金額 must be a number: " (pr-str c)))]))

               (= :counterparty item)
               (when-not (and (string? c) (not (str/blank? c)))
                 [(problem item :not-a-counterparty
                           (str "取引先 must be a non-blank string: " (pr-str c)))])

               :else [])))
          conditions)))

;; ---------------------------------------------------------------------------
;; Matching
;; ---------------------------------------------------------------------------

(defn- in-range? [v {:keys [from to]}]
  (and (some? v)
       (or (nil? from) (not (neg? (compare v from))))
       (or (nil? to) (not (pos? (compare v to))))))

(defn- matches-scalar? [item v c]
  (cond
    (nil? v) false
    (range-condition? c) (in-range? v c)
    (= :counterparty item) (= v c)
    :else (= v c)))

(defn- matching-currencies
  "Which currencies of this entry satisfy the 取引金額 condition. Empty means
  the entry does not match."
  [amounts c]
  (->> amounts
       ;; filter-then-key, not `keep` — a line with no `:currency` groups under
       ;; nil (the behaviour `bookkeeping.posting` preserves for a
       ;; single-currency ledger), and `keep` would drop that entry from every
       ;; amount search as though it had not matched.
       (filter (fn [[_ total]] (if (range-condition? c) (in-range? total c) (= total c))))
       (map key)
       sort
       vec))

(defn- match
  "nil when the entry does not satisfy every condition; the result row when it
  does. AND, per ハ（３） — `二以上の任意の記録項目を組み合わせて`."
  [items conditions]
  (let [currencies (when (contains? conditions :amount)
                     (matching-currencies (:amount items) (:amount conditions)))]
    (when (and (or (not (contains? conditions :transaction-date))
                   (matches-scalar? :transaction-date (:transaction-date items)
                                    (:transaction-date conditions)))
               (or (not (contains? conditions :counterparty))
                   (matches-scalar? :counterparty (:counterparty items)
                                    (:counterparty conditions)))
               (or (nil? currencies) (seq currencies)))
      (cond-> items
        currencies (assoc :matched-currencies currencies)))))

(defn search
  "The 検索機能 of 規則第五条第五項第一号ハ over committed postings.

      {:kensaku/coverage :no-conditions}     nothing was asked
      {:kensaku/coverage :invalid-condition  :kensaku/problems [...]}
      {:kensaku/coverage :searched ...}      here is what matched

  `:kensaku/results` exists ONLY in the `:searched` case. A caller cannot
  read an empty result list out of the other two, which is the whole reason
  they are separate values rather than an empty list with a flag.

  `:kensaku/searched-count` is always reported alongside `:kensaku/match-count`
  — an evidence floor. Zero matches out of zero entries examined and zero
  matches out of four hundred are different answers, and a search that could
  not see the books must not report the same thing as one that saw them all."
  [postings conditions]
  (let [conditions (or conditions {})
        problems (validate conditions)]
    (cond
      (seq problems)
      {:kensaku/coverage :invalid-condition
       :kensaku/provision provision
       :kensaku/problems problems}

      (empty? conditions)
      {:kensaku/coverage :no-conditions
       :kensaku/provision provision
       :kensaku/why (str "a search with no 記録項目 set is not a search. "
                         "GET /api/journal is how this actor hands back the "
                         "whole book, and it says so.")}

      :else
      (let [rows (into [] (keep #(match (record-items %) conditions)) postings)]
        {:kensaku/coverage :searched
         :kensaku/provision provision
         :kensaku/conditions conditions
         :kensaku/searched-count (count postings)
         :kensaku/match-count (count rows)
         :kensaku/results rows}))))

;; ---------------------------------------------------------------------------
;; Query parameters
;; ---------------------------------------------------------------------------

(def ^:private query-keys
  "URL parameter -> the typed condition it contributes to. Data, so an
  unknown parameter is detectable rather than ignored."
  {"date" [:transaction-date :exact]
   "date-from" [:transaction-date :from]
   "date-to" [:transaction-date :to]
   "amount" [:amount :exact]
   "amount-from" [:amount :from]
   "amount-to" [:amount :to]
   "counterparty" [:counterparty :exact]})

(defn- parse-amount [s]
  (when (and (string? s) (re-matches #"-?\d+" s))
    #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))))

(defn parse-query
  "A `{param value}` map of URL query parameters -> typed conditions.

      {:kensaku/conditions {...}}      usable (possibly empty)
      {:kensaku/problems [...]}        say why, do not guess

  An unrecognised parameter is a PROBLEM, not something to skip. `?conterparty=x`
  would otherwise become a search with no conditions, and the caller would be
  told it asked nothing when in fact it asked something and was not heard."
  [query]
  (let [acc
        (reduce
         (fn [{:keys [conditions] :as acc} [k v]]
           (if-let [[item slot] (get query-keys k)]
             (let [parsed (if (= :amount item) (parse-amount v) v)
                   already (get conditions item ::absent)]
               (cond
                 (and (= :amount item) (nil? parsed))
                 (update acc :problems conj
                         (problem item :not-a-number
                                  (str "?" k "= must be an integer: " (pr-str v))))

                 ;; An exact condition and a range on the same 記録項目 are two
                 ;; different questions. Answering one of them silently would
                 ;; give the caller a result set it cannot account for.
                 (or (and (= :exact slot) (not= ::absent already))
                     (and (not= :exact slot) (not= ::absent already) (not (map? already))))
                 (update acc :problems conj
                         (problem item :conflicting-condition
                                  (str "?" k "= and an existing " (name item)
                                       " condition are two different questions; pick one")))

                 (= :exact slot) (assoc-in acc [:conditions item] parsed)
                 :else (update-in acc [:conditions item] merge {slot parsed})))
             (update acc :problems conj
                     (problem nil :unknown-parameter
                              (str "?" k "= is not a search parameter; ハ（１） names "
                                   (pr-str (vec (sort (keys query-keys)))))))))
         {:problems [] :conditions {}}
         (or query {}))]
    (if (seq (:problems acc))
      {:kensaku/problems (:problems acc)}
      {:kensaku/conditions (:conditions acc)})))

;; ---------------------------------------------------------------------------
;; Can this deployment claim 規則第五条第五項第一号ハ?
;; ---------------------------------------------------------------------------

(def probe-postings
  "Two synthetic postings `conformance` runs the search over to find out
  whether the search actually answers ハ（１）（２）（３）.

  Synthetic on purpose: asking the question against the client's real books
  would answer `no` whenever the books happened to be empty or uniform, which
  is a fact about the books and not about the software."
  [{:ledger/posting "kensaku-probe-a"
    :bookkeeping/transaction-date "2026-01-15"
    :bookkeeping/counterparty "probe-alpha"
    :ledger/entries [{:ledger/account "probe" :ledger/side :debit
                      :ledger/amount 1000 :ledger/currency "JPY"}
                     {:ledger/account "probe" :ledger/side :credit
                      :ledger/amount 1000 :ledger/currency "JPY"}]}
   {:ledger/posting "kensaku-probe-b"
    :bookkeeping/transaction-date "2026-06-30"
    :bookkeeping/counterparty "probe-beta"
    :ledger/entries [{:ledger/account "probe" :ledger/side :debit
                      :ledger/amount 9000 :ledger/currency "JPY"}
                     {:ledger/account "probe" :ledger/side :credit
                      :ledger/amount 9000 :ledger/currency "JPY"}]}])

(def probes
  "One probe per sub-requirement, each with the EXACT set of probe postings it
  must return. `:expect #{}` is a real expectation — ハ（３） is only satisfied
  if combining conditions narrows, and a search that ORed them would return
  both and pass every other probe here."
  [{:id :ha-1-date :requirement :kensaku/ha-1
    :conditions {:transaction-date "2026-01-15"} :expect #{"kensaku-probe-a"}}
   {:id :ha-1-amount :requirement :kensaku/ha-1
    :conditions {:amount 9000} :expect #{"kensaku-probe-b"}}
   {:id :ha-1-counterparty :requirement :kensaku/ha-1
    :conditions {:counterparty "probe-beta"} :expect #{"kensaku-probe-b"}}
   {:id :ha-2-date-range :requirement :kensaku/ha-2
    :conditions {:transaction-date {:from "2026-01-01" :to "2026-03-31"}}
    :expect #{"kensaku-probe-a"}}
   {:id :ha-2-date-range-open :requirement :kensaku/ha-2
    :conditions {:transaction-date {:from "2026-04-01"}} :expect #{"kensaku-probe-b"}}
   {:id :ha-2-amount-range :requirement :kensaku/ha-2
    :conditions {:amount {:from 5000 :to 20000}} :expect #{"kensaku-probe-b"}}
   {:id :ha-3-combined :requirement :kensaku/ha-3
    :conditions {:transaction-date {:from "2026-01-01" :to "2026-12-31"}
                 :counterparty "probe-alpha"}
    :expect #{"kensaku-probe-a"}}
   {:id :ha-3-combined-narrows :requirement :kensaku/ha-3
    :conditions {:counterparty "probe-alpha" :amount 9000} :expect #{}}])

(defn probe-search-function
  "Run every probe through `search-fn` and report which sub-requirements it
  actually answers.

  This RUNS the search rather than asserting that it exists. `the function is
  defined` is a claim any file can make about itself by containing a `defn`;
  `it returned exactly probe-b for a 5000–20000 range` is a measurement. A
  build where ranges silently degraded to equality reports ハ（２）false here
  and cannot report 適合."
  [search-fn]
  (let [results (mapv (fn [{:keys [conditions expect] :as p}]
                        (let [r (when (ifn? search-fn) (search-fn probe-postings conditions))
                              got (when (= :searched (:kensaku/coverage r))
                                    (set (map :posting (:kensaku/results r))))]
                          (assoc (select-keys p [:id :requirement])
                                 :expected (vec (sort expect))
                                 :got (vec (sort (or got [])))
                                 :pass? (= expect got))))
                      probes)]
    (into {:kensaku/probes results
           :kensaku/probe-count (count results)}
          (map (fn [req]
                 [req (every? :pass? (filter #(= req (:requirement %)) results))]))
          [:kensaku/ha-1 :kensaku/ha-2 :kensaku/ha-3])))

(defn conformance
  "Does this ledger meet 規則第五条第五項第一号ハ?

  Takes `{:postings [...] :jurisdiction j :declared? true|false|nil
  :search-fn f}`.

  `:kensaku/status` is one of SIX values, and only one of them is a pass:

    :unchecked-jurisdiction
                     the client's jurisdiction is not one `kotoba.taxlaw`
                     has catalogued, or none was declared. **Not a pass**,
                     and the reason it comes first: 規則第五条第五項第一号ハ
                     is a Japanese ministerial ordinance, and answering
                     適合 for a book kept somewhere else is not a lenient
                     answer, it is an answer to a different question. The
                     first version of this function asked whether the
                     operator was claiming 優良帳簿 and never asked which
                     country's 優良帳簿 — so a ledger with no jurisdiction
                     at all reported `:conformant` against a provision that
                     could not reach it.
    :not-declared    nobody said whether this 保存義務者 is claiming
                     法第八条第四項（優良帳簿）. **Not a pass.** The software
                     cannot observe a tax election, and answering 適合 to a
                     question nobody asked is how a compliance claim becomes
                     a decoration.
    :not-applicable  the operator declared they are NOT claiming it. ハ does
                     not bite — ordinary preservation under 法第四条第一項
                     requires no search function at all. **Also not a pass**:
                     nothing was found compliant, the requirement was found
                     not to apply, and a caller must be able to tell those
                     apart.
    :no-entries      claiming it, but the book is empty. An empty ledger has
                     not been shown to be searchable, only to be empty — the
                     same reason `bookkeeping.trial-balance/balanced?` is
                     false for an empty set.
    :non-conformant  claiming it, and either the search does not answer
                     ハ（１）（２）（３） or entries lack 記録項目. The count
                     and the offending posting ids are reported, because
                     `some entries` is not something an operator can act on.
    :conformant      claiming it, the search answers all three probes, and
                     every entry carries all three 記録項目.

  `declared?` is read strictly: anything that is not literally `true` or
  `false` — a string \"yes\", a missing key — is `:not-declared`. A loose
  truthiness test here would let `\"no\"` mean yes. The jurisdiction is read
  just as strictly, and from `kotoba.taxlaw` rather than from a set of
  keywords kept here: whether a jurisdiction has this rule is the catalog's
  answer to give, and a copy of it here would be a second place to update."
  [{:keys [postings jurisdiction declared? search-fn] :or {search-fn search}}]
  (let [postings (vec postings)
        ;; nil for an uncatalogued or undeclared jurisdiction — never false,
        ;; which is what lets `there is no such rule here` stay distinct from
        ;; `nobody has looked`.
        rule-applies (taxlaw/requires-book-search? jurisdiction)
        fn-report (probe-search-function search-fn)
        searchable? (and (:kensaku/ha-1 fn-report)
                         (:kensaku/ha-2 fn-report)
                         (:kensaku/ha-3 fn-report))
        missing (into [] (keep (fn [p]
                                 (when-let [m (seq (missing-record-items p))]
                                   {:posting (:ledger/posting p) :missing (vec m)})))
                      postings)
        status (cond
                 (nil? rule-applies) :unchecked-jurisdiction
                 (false? declared?) :not-applicable
                 (not (true? declared?)) :not-declared
                 (not searchable?) :non-conformant
                 (zero? (count postings)) :no-entries
                 (seq missing) :non-conformant
                 :else :conformant)]
    {;; the provision is stamped only where it reaches. Citing a Japanese
     ;; ordinance on a verdict about a book kept elsewhere would put the
     ;; same mistake in a different key.
     :kensaku/provision (when (some? rule-applies) provision)
     :kensaku/law-source (when (some? rule-applies) law-source)
     :kensaku/jurisdiction jurisdiction
     :kensaku/status status
     :kensaku/declared (cond (true? declared?) true (false? declared?) false :else nil)
     :kensaku/search-function fn-report
     :kensaku/entry-count (count postings)
     :kensaku/entries-missing-record-items (count missing)
     :kensaku/missing missing
     :kensaku/why
     (case status
       :unchecked-jurisdiction
       (str "法域 " (pr-str jurisdiction)
            " は kotoba.taxlaw に無い。規則第五条第五項第一号ハ は日本の省令であり、"
            "他所で備え付けられた帳簿について 適合 と答えるのは寛大な答えではなく、"
            "別の問いへの答えである。")
       :not-applicable "法第八条第四項（優良帳簿）を主張していないと宣言されている。ハ は適用されない — 適合とは別の答えである。"
       :not-declared "法第八条第四項（優良帳簿）を主張しているかが宣言されていない。未宣言は適合ではない。"
       :no-entries "帳簿が空である。空の帳簿は検索可能であると示されたのではなく、空であると示されただけである。"
       :non-conformant (if searchable?
                         (str (count missing) " 件の仕訳が記録項目を欠いており、検索の条件に設定できない。")
                         "検索機能が ハ（１）（２）（３） を満たしていない。")
       :conformant "検索機能は ハ（１）（２）（３） を満たし、全仕訳が三つの記録項目を備えている。")}))

(defn conformant?
  "Convenience boolean over `conformance`, conservative in the same way
  `kotoba.taxlaw/supported?` is: everything that is not `:conformant` — the
  undeclared, the inapplicable and the empty alike — comes back false. A
  caller reaching for the short answer gets the careful one."
  [opts]
  (= :conformant (:kensaku/status (conformance opts))))
