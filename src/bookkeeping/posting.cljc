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
  exactly the old behaviour — so a single-currency ledger sees no change."
  (:require [clojure.string]
            [kotoba.banking :as banking]))

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
  lines in a canonical order, so line ordering does not change identity."
  [source-doc lines]
  (let [canon (->> lines
                   (map (fn [{:keys [side account amount currency]}]
                          (str (name (or side :?)) "|" account "|" amount "|" currency)))
                   sort
                   (clojure.string/join ";"))]
    (str "je-" (fnv1a (str source-doc "\u0000" canon)))))

(defn project
  "An approved journal entry as a `kotoba.banking` posting, or nil if the
  lines do not project.

  `banking/posting` sets `:ledger/balanced?` and, when unbalanced, also
  `:ledger/unbalanced` — deliberately, so a governor can reject a posting
  before it reaches a ledger. This function does NOT filter unbalanced
  postings out: the governor upstream is what refuses them, and silently
  dropping one here would hide a refusal that is supposed to be visible."
  [entry-id lines & {:keys [memo]}]
  (when-let [es (entries lines :ref entry-id)]
    (banking/posting entry-id es :memo memo)))
