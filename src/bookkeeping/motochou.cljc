(ns bookkeeping.motochou
  "元帳 and 仕訳帳 — the two books, read back out of the committed postings.

  The plane could already say what an account's *balance* was
  (`bookkeeping.trial-balance`) and what the statements looked like
  (`bookkeeping.statements`). Neither answers the question a bookkeeper
  actually asks first: **what happened to this account, in order.** A balance
  is a number you have to trust; a 元帳 is the number with its working shown.

  ## Order is the whole point

  A 総勘定元帳 whose lines are not in the order they were committed is not a
  ledger — it is a set with a running total drawn on it, and the running
  total is then meaningless. `store/postings-of` returns commit order and
  both backends are held to that by the store contract test; this namespace
  does not re-sort, and a test asserts it does not.

  ## Per currency, again

  The running balance is keyed by `[account currency]`. Carrying one running
  total across currencies would produce a column of numbers that looks exactly
  like a ledger and means nothing — the same failure this actor shipped once
  at the entry level, where 5000 JPY debit and 5000 USD credit balanced.

  ## Two ways to be empty, and they are different

  An account the chart does not name and an account with no entries this
  period are not the same answer. `:unknown-account` and an empty `:lines`
  with `:entry-count 0` say which — a caller shown a blank page otherwise
  cannot tell a typo from a quiet month."
  (:require [clojure.string :as str]))

(defn- posting-lines
  "Flatten postings into ledger lines, keeping commit order and remembering
  which posting each came from."
  [postings]
  (for [{:keys [ledger/posting ledger/entries ledger/memo
                bookkeeping/transaction-date bookkeeping/counterparty]} postings
        e entries]
    {:posting posting
     :memo memo
     ;; 取引年月日 / 取引先 ride down onto every line of the entry they came
     ;; from. A 元帳 column that cannot say WHEN or WITH WHOM is the working
     ;; with the working left out.
     :transaction-date transaction-date
     :counterparty counterparty
     :account (:ledger/account e)
     :side (:ledger/side e)
     :amount (:ledger/amount e)
     :currency (:ledger/currency e)}))

(defn journal
  "仕訳帳 — every committed posting, in commit order, with its lines.

  Includes `:balanced?` per posting rather than recomputing it: the value
  `kotoba.banking/posting` already put there is what the governor let
  through, and a second opinion computed here could disagree with the one
  that was actually enforced."
  [postings]
  {:motochou/entry-count (count postings)
   :motochou/entries
   (mapv (fn [{:keys [ledger/posting ledger/entries ledger/memo ledger/balanced?
                      bookkeeping/transaction-date bookkeeping/counterparty]}]
           {:posting posting
            :memo memo
            :balanced? balanced?
            ;; Always present, nil when the entry carried none — see
            ;; `bookkeeping.posting`. A 仕訳帳 that omitted the key would make
            ;; "this entry has no 取引先" read the same as "nobody recorded
            ;; one", and 規則第五条第五項第一号ハ turns on exactly that
            ;; difference.
            :transaction-date transaction-date
            :counterparty counterparty
            :lines (mapv (fn [e]
                           {:account (:ledger/account e)
                            :side (:ledger/side e)
                            :amount (:ledger/amount e)
                            :currency (:ledger/currency e)})
                         entries)})
         postings)})

(defn account
  "総勘定元帳 for one account, per currency, with a running balance.

      {:motochou/coverage :unknown-account}   the chart does not name it
      {:motochou/coverage :ok
       :motochou/by-currency {currency {:lines [...] :closing n :entry-count n}}}

  `:unknown-account` is separate from an account with no entries. A caller
  shown a blank page otherwise cannot tell a typo from a quiet month.

  Each line carries `:balance`, the running total **after** that line, in
  debit-positive terms. Debit-positive rather than presented-normal because
  this is the working, not the statement: `kotoba.shohyo` is what turns a
  balance into the sign an accountant reads, and doing it twice in two places
  is how the two drift."
  [chart postings account-name]
  (if-not (contains? chart account-name)
    {:motochou/coverage :unknown-account
     :motochou/account account-name
     :motochou/why "the chart of accounts does not name it; this is not the
                    same as an account with no entries this period"}
    (let [mine (filter #(= account-name (:account %)) (posting-lines postings))]
      {:motochou/coverage :ok
       :motochou/account account-name
       :motochou/by-currency
       (into {}
             (map (fn [[currency ls]]
                    ;; reductions over commit order — the running balance is
                    ;; only meaningful because the order is the committed one
                    (let [running (reductions
                                   (fn [acc l]
                                     (+ acc (if (= :debit (:side l))
                                              (:amount l)
                                              (- (:amount l)))))
                                   0 ls)]
                      [currency
                       {:entry-count (count ls)
                        :closing (last running)
                        :lines (mapv (fn [l b] (assoc l :balance b))
                                     ls (rest running))}])))
             ;; group-by preserves the order of values within each group
             (group-by :currency mine))})))

(defn accounts-with-activity
  "Every `[account currency]` that has at least one line.

  Returned so a caller can enumerate the ledger without asking the chart —
  an account that received a posting while absent from the chart is exactly
  the case `bookkeeping.statements` reports as unclassified, and it must not
  become invisible here just because `account` refuses to open it."
  [postings]
  (vec (sort (distinct (map (juxt :account :currency) (posting-lines postings))))))
