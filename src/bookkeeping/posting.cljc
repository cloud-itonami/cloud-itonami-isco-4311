(ns bookkeeping.posting
  "Where an approved journal entry GOES.

  Until this namespace existed, this actor could refuse a bad entry and
  approve a good one, and then the good one went nowhere. `:lines` was a
  private shape — `{:side :dr :account \"supplies\" :amount 5000}` — read by
  one function in the governor and by nothing else in the fleet. An approved
  entry was a decision with no destination.

  The destination is not invented here either. `kotoba-lang/banking` already
  owns the double-entry contract for this workspace — `entry`, `posting`,
  and balanced-posting validation in minor units — and
  `kotoba-lang/kakeibo` already projects onto it, saying in its own docstring
  that *the ledger contract is not reinvented here*. This namespace is the
  bookkeeping half of the same sentence.

  ```text
  kakeibo     statement rows  ─┐
                               ├─▶  kotoba.banking  double-entry postings
  bookkeeping journal entries ─┘
  ```

  ## What projecting bought that de-duplication alone would not

  `banking/balanced?` groups by currency before comparing debits to credits.
  The check it replaces did not:

  ```clojure
  ;; the check that was here
  (= (line-total lines :dr) (line-total lines :cr))
  ```

  It summed `:amount` across every line regardless of `:currency`, so an
  entry with **5000 debit in JPY and 5000 credit in USD balanced.** The
  actor's second HARD invariant is that a human cannot approve their way
  past bad arithmetic; that arithmetic was wrong in the one direction the
  invariant exists to catch. Delegating fixes it, and the fix arrives as a
  consequence of using the shared contract rather than as a patch to a
  private one.

  Lines that carry no `:currency` all group under `nil` together, which is
  exactly the old behaviour — so a single-currency ledger sees no change.

  ## 取引年月日 and 取引先 — the two 記録項目 that were not being recorded

  電子帳簿保存法施行規則 第五条第五項第一号ハ（１） names three 記録項目 a
  国税関係帳簿 must be searchable by: **取引年月日、取引金額及び取引先**. This
  actor recorded only `:source-doc` and `:lines`, so two of the three did not
  exist anywhere in the plane. A search over what was stored would have run,
  returned rows, and satisfied none of （１） — the shape this fleet keeps
  finding, where a check that could not look returns the same value as one
  that looked and found nothing.

  So `project` carries them, under this actor's OWN namespace
  (`:bookkeeping/transaction-date`, `:bookkeeping/counterparty`) rather than
  `:ledger/*`: `kotoba.banking` does not define them and stamping its
  namespace on them would claim a contract that library never made.

  Both keys are ALWAYS present on the posting, `nil` when the entry carried
  none. Omitting the key instead would make `this entry has no 取引先` and
  `nobody thought about 取引先 here` the same read.

  A blank or whitespace-only 取引先 is normalised to `nil` — recorded as
  absent, never as a counterparty named \"   \". The edge refuses one outright
  (400) so a caller learns; this is the floor under callers that are not the
  edge."
  (:require [clojure.string :as str]
            [kotoba.banking :as banking]))

;; ---------------------------------------------------------------------------
;; 記録項目 — what a searchable entry must carry
;; ---------------------------------------------------------------------------

(def ^:private month-days [31 28 31 30 31 30 31 31 30 31 30 31])

(defn- leap-year? [y]
  (and (zero? (mod y 4))
       (or (not (zero? (mod y 100))) (zero? (mod y 400)))))

(defn- digits->int [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn valid-transaction-date?
  "Is `s` an ISO-8601 calendar date, `YYYY-MM-DD`?

  ISO-8601 and not a platform date object, for one reason that decides it:
  規則第五条第五項第一号ハ（２）requires a RANGE condition on 日付, and in
  this form lexicographic order is chronological order, on both `:clj` and
  `:cljs`, with no date library and no timezone. A `2026/1/5` or a
  `Jan 5 2026` would sort as text and compare wrongly while looking fine.

  The calendar is checked, not just the shape: `2026-02-30` and `2026-13-01`
  are refused, and 2024-02-29 is accepted while 2026-02-29 is not. A date
  that cannot exist is a client system to fix, and it would sit in a range
  query forever answering nothing."
  [s]
  (boolean
   (when (string? s)
     (when-let [m (re-matches #"(\d{4})-(\d{2})-(\d{2})" s)]
       (let [y (digits->int (nth m 1))
             mo (digits->int (nth m 2))
             d (digits->int (nth m 3))]
         (and (<= 1 mo 12)
              (<= 1 d (if (and (= 2 mo) (leap-year? y))
                        29
                        (nth month-days (dec mo))))))))))

(defn normalize-counterparty
  "A 取引先 as it should be recorded, or `nil`.

  Blank and whitespace-only collapse to `nil`. A counterparty recorded as
  `\"\"` is worse than one recorded as absent: it is indistinguishable from a
  real name in a `=` search, so a 取引先 condition would match entries that
  never had one."
  [s]
  (when (and (string? s) (not (str/blank? s))) s))

(def side->banking
  "This actor writes `:dr`/`:cr`; banking writes `:debit`/`:credit`. The map
  is data rather than a `case` so an unknown side falls out as nil and is
  caught by `entries`, instead of throwing somewhere further in."
  {:dr :debit :cr :credit})

(defn entries
  "Project `lines` onto `kotoba.banking` ledger entries.

  Returns nil when ANY line fails to project — an unknown `:side`, or a
  line banking itself refuses. A partial projection is the dangerous
  outcome: it would balance, because the line that could not be represented
  is the one missing from both sides."
  [lines & {:keys [ref]}]
  (let [es (map (fn [{:keys [side account amount currency]}]
                  (when-let [s (side->banking side)]
                    (banking/entry account s amount currency :ref ref)))
                lines)]
    (when (and (seq es) (every? some? es))
      (vec es))))

(defn balanced?
  "Do these lines balance, per currency?

  False when the lines cannot be projected at all — a line this actor
  cannot represent is not a line it should call balanced."
  [lines]
  (boolean (some-> (entries lines) banking/balanced?)))

(defn- fnv1a
  "A deterministic 32-bit content hash, implemented here rather than pulled
  in. It is NOT cryptographic and is not pretending to be — it exists to give
  identical content the same key on JVM and JS, which `clojure.core/hash`
  does not promise across platforms or versions. A collision here would merge
  two different entries, so if this ever needs to resist an adversary it must
  be replaced with a real digest, not widened."
  [^String s]
  (reduce (fn [h c]
            (let [h (bit-xor h (bit-and (int c) 0xff))]
              (bit-and (* h 16777619) 0xffffffff)))
          2166136261
          (seq s)))

(defn content-id
  "A stable id for an entry, derived from what the entry SAYS.

  The actor used to key a posting on `(or entry-id source-doc)`, and that is
  wrong twice over:

    a retried submission of the SAME entry produced a SECOND posting under
    the same id, and the trial balance doubled. Measured 2026-08-18: two
    identical submissions, two postings both called `d1`, supplies 5000 ->
    10000. A carrier that retries is normal, and this made retrying corrupt
    the books.

    two GENUINELY DIFFERENT entries citing the same receipt collided under
    one id, which is the same defect from the other side.

  Content-addressing fixes both: identical content is idempotent, different
  content is distinct. The id is a function of the source document and the
  lines in a canonical order, so line ordering does not change identity.

  **取引年月日 and 取引先 are part of the content.** Two entries citing one
  monthly statement, on different days, to different suppliers, with the same
  accounts and the same amount are DIFFERENT entries — and before these two
  fields existed there was nothing to tell them apart, so they collided under
  one id and the second was silently dropped as a duplicate. That is the same
  defect this function was written to fix, arriving through the fields that
  were missing."
  [source-doc lines & {:keys [transaction-date counterparty]}]
  (let [canon (->> lines
                   (map (fn [{:keys [side account amount currency]}]
                          (str (name (or side :?)) "|" account "|" amount "|" currency)))
                   sort
                   (str/join ";"))]
    (str "je-" (fnv1a (str source-doc "\u0000"
                          transaction-date "\u0000"
                          (normalize-counterparty counterparty) "\u0000"
                          canon)))))

(defn project
  "An approved journal entry as a `kotoba.banking` posting, or nil if the
  lines do not project.

  `banking/posting` sets `:ledger/balanced?` and, when unbalanced, also
  `:ledger/unbalanced` — deliberately, so a governor can reject a posting
  before it reaches a ledger. This function does NOT filter unbalanced
  postings out: the governor upstream is what refuses them, and silently
  dropping one here would hide a refusal that is supposed to be visible.

  `:bookkeeping/transaction-date` and `:bookkeeping/counterparty` are always
  assoc'd, `nil` when the entry carried none — see the namespace docstring for
  why the key is present rather than omitted, and why a blank 取引先 becomes
  `nil` here rather than a name made of spaces."
  [entry-id lines & {:keys [memo transaction-date counterparty]}]
  (when-let [es (entries lines :ref entry-id)]
    (assoc (banking/posting entry-id es :memo memo)
           :bookkeeping/transaction-date transaction-date
           :bookkeeping/counterparty (normalize-counterparty counterparty))))
