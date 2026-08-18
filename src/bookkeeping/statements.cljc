(ns bookkeeping.statements
  "貸借対照表 / 損益計算書 for one client, from the postings the actor
  committed.

  The last link of the chain this actor has been growing:

  ```text
  仕訳 ─▶ governor ─▶ posting ─▶ trial balance ─▶ statements
         (refuses)   (lands)     (arithmetic)     (classification)
  ```

  Everything before `statements` is jurisdiction- and chart-neutral. This
  namespace is where a chart enters, and it enters as **the client's own**,
  read from the store — never inferred and never defaulted.

  ## Why there is no default chart

  `kotoba.shohyo` refuses to guess what an account is, because a balance
  sheet that omits an account still balances. Shipping a default chart here
  would answer that question before anyone asked it: accounts would classify
  by whatever the default happened to call them, and the refusal would become
  cosmetic. A client with no chart registered gets `:no-chart`, which is a
  different answer from `:incomplete` — one means nobody has said what these
  accounts are, the other means somebody said it for some of them."
  (:require [bookkeeping.store :as store]
            [bookkeeping.trial-balance :as tb]
            [kotoba.shohyo :as shohyo]
            [kotoba.shohyo.jp :as jp]))

(defn- section-totals
  "Sum the presented magnitudes per 会社計算規則 section, for the ladder.

  **Every section the CHART declares is seeded at zero**, whether or not any
  account in it has a balance. Declared-and-empty and undeclared are
  different claims: a client whose chart names 営業外収益 has said it has
  that section and nothing in it this period, while a client whose chart
  does not name it has said nothing at all. `jp/stage-profits` refuses the
  second — an absent 売上原価 is an unstated cost, not a zero one — and
  seeding only the sections with balances would collapse the two and make
  every sparse period look unstated."
  [chart balances currency]
  (reduce (fn [acc [[account cur] {:keys [balance]}]]
            (if-not (= cur currency)
              acc
              (if-let [section (get-in chart [account :section])]
                (let [type (:type (jp/section-of section))
                      presented (if (#{:revenue :liability :equity} type)
                                  (- balance) balance)]
                  (update acc section (fnil + 0) presented))
                acc)))
          (into {} (map (fn [s] [s 0]))
                (keep :section (vals chart)))
          balances))

(defn for-client
  "The statements for `client-id`, or the reason there are none.

      {:statements/coverage :no-chart}      nobody registered a chart
      {:statements/coverage :chart-invalid} the chart is unusable, with why
      {:statements/coverage :ok ...}        with :shohyo and, per currency
                                            that has one, the JP ladder

  `:ok` does NOT mean the statements are whole — that is
  `(shohyo/complete? (:statements/shohyo r))`, which also requires the
  accounting equation. Two words for two questions: whether this function
  could answer, and whether the answer is a finished statement."
  [store client-id]
  (let [chart (store/chart-of store client-id)]
    (cond
      (empty? chart)
      {:statements/coverage :no-chart
       :statements/why (str "no chart of accounts registered for " (pr-str client-id)
                            "; this actor does not infer one")}

      (seq (jp/section-problems chart))
      {:statements/coverage :chart-invalid
       :statements/chart-problems (jp/section-problems chart)}

      :else
      (let [balances (tb/balances (store/postings-of store client-id))
            r (shohyo/statements chart balances)]
        (if (= :unusable-chart (:shohyo/coverage r))
          {:statements/coverage :chart-invalid
           :statements/chart-problems (:shohyo/chart-problems r)}
          {:statements/coverage :ok
           :statements/shohyo r
           :statements/complete? (shohyo/complete? r)
           ;; The 段階利益 ladder, per currency. Absent for a currency whose
           ;; chart does not declare every section the ladder needs — the
           ;; ladder says which, and this passes that through rather than
           ;; flattening it to nil.
           :statements/jp
           (into {}
                 (map (fn [currency]
                        [currency (jp/stage-profits
                                   (section-totals chart balances currency))]))
                 (keys (:shohyo/by-currency r)))})))))
