(ns bookkeeping.trial-balance
  "試算表 — what the committed postings add up to.

  This is the first thing in the accounting plane that READS the ledger back.
  Everything before it decided: the governor refuses a bad entry, the actor
  commits a good one, `bookkeeping.posting` projects it onto
  `kotoba.banking`. None of that could answer *what is the balance of this
  account* — which is the question a ledger exists to answer.

  ## Per currency, always

  Balances are keyed by `[account currency]`, never by account alone. The
  bug this actor shipped until 2026-08-17 was a balance check that summed
  amounts across currencies, so 5000 JPY debit against 5000 USD credit
  balanced. Aggregating the same way here would reintroduce it one layer
  up, where it would be harder to see: a trial balance that nets to zero is
  exactly the output that makes people stop looking.

  ## Sign convention

  `:balance` is debits minus credits, in the account's minor units. Positive
  is a debit balance (assets, expenses), negative a credit balance
  (liabilities, equity, income). No account-type table is consulted, because
  this actor has no chart of accounts — an account is whatever string an
  entry named. Naming that limitation is the point: a trial balance here
  proves arithmetic, not classification.

  ## What it does NOT claim

  A trial balance that nets to zero says the postings are internally
  consistent. It does not say they are complete, correct, or that anything
  was posted at all. `totals` therefore reports `:posting-count`, and
  `balanced?` returns false for an empty set rather than true — zero equals
  zero, but an empty ledger has not been shown to balance, it has been shown
  to be empty. That distinction is the whole difference between a check and
  a formality."
  (:require [kotoba.banking :as banking]))

(defn- entries-of [postings]
  (mapcat :ledger/entries postings))

(defn balances
  "`{[account currency] {:debit n :credit n :balance n}}` over `postings`.

  Takes postings (as `bookkeeping.posting/project` produces and
  `store/postings-of` returns), not raw lines — the projection is where an
  entry becomes ledger-shaped, and skipping it would let an unprojectable
  entry into the balance through a side door."
  [postings]
  (reduce
   (fn [acc {:keys [ledger/account ledger/side ledger/amount ledger/currency]}]
     (let [k [account currency]
           cur (get acc k {:debit 0 :credit 0 :balance 0})
           cur (update cur (if (= :debit side) :debit :credit) + amount)]
       (assoc acc k (assoc cur :balance (- (:debit cur) (:credit cur))))))
   {}
   (entries-of postings)))

(defn totals
  "Debit and credit totals per currency, plus how many postings produced them.

  `:posting-count` is not decoration. A caller that only reads the totals
  cannot tell an empty ledger from a balanced one, and this is the value
  that tells it."
  [postings]
  (let [es (entries-of postings)]
    {:posting-count (count postings)
     :entry-count (count es)
     :by-currency
     (reduce (fn [acc {:keys [ledger/side ledger/amount ledger/currency]}]
               (update-in acc [currency (if (= :debit side) :debit :credit)]
                          (fnil + 0) amount))
             {}
             es)}))

(defn balanced?
  "Do debits equal credits, per currency, across every posting?

  **False for an empty posting set.** Zero does equal zero, but an empty
  ledger has not been shown to balance — it has been shown to be empty, and
  reporting those identically is how a check becomes a formality.

  Delegates the comparison itself to `banking/balanced?`, so the trial
  balance and the governor's per-entry check are the same arithmetic rather
  than two implementations that agree until they don't."
  [postings]
  (let [es (entries-of postings)]
    (boolean (and (seq es) (banking/balanced? es)))))

(defn out-of-balance
  "Currencies whose debits and credits disagree, as
  `{currency {:debit n :credit n :difference n}}`. Empty when balanced.

  A boolean is not enough for a caller who has to fix it: `balanced?`
  answers whether to worry, this answers where."
  [postings]
  (into {}
        (keep (fn [[currency {:keys [debit credit]}]]
                (let [d (- (or debit 0) (or credit 0))]
                  (when-not (zero? d)
                    [currency {:debit (or debit 0) :credit (or credit 0)
                               :difference d}]))))
        (:by-currency (totals postings))))

(defn report
  "The whole answer for one client, from that client's committed postings.

  `:balanced?` and `:out-of-balance` are both present on purpose: a reader
  that trusts only the boolean still gets the conservative answer for an
  empty ledger, and a reader that wants to act gets the currencies."
  [postings]
  {:trial-balance/balances (balances postings)
   :trial-balance/totals (totals postings)
   :trial-balance/balanced? (balanced? postings)
   :trial-balance/out-of-balance (out-of-balance postings)})
