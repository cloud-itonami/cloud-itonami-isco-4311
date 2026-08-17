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
  (:require [kotoba.banking :as banking]))

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
