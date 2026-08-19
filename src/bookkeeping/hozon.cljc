(ns bookkeeping.hozon
  "電子取引の保存 — 真実性の確保, and what this actor can and cannot evidence.

  `bookkeeping.kensaku` answers 規則第五条第五項第一号ハ, the SEARCH
  requirement for 国税関係帳簿. This namespace answers the other half of a
  different article: 規則第四条第一項, which governs the electromagnetic
  records of an 電子取引 and requires **one of four measures** for 真実性
  alongside the search conditions.

  ## The four, and why a list rather than a verdict

  第一項 一〜四, read 2026-08-19:

  ```text
  一  タイムスタンプが付された後に授受する
  二  授受後にタイムスタンプを付す（イ 速やかに / ロ 規程があれば通常期間後）
  三  訂正削除の事実と内容を確認できる、または訂正削除ができないシステム
  四  正当な理由がない訂正削除の防止に関する事務処理規程
  ```

  Any ONE satisfies the article. A business with no special system at all and
  a written 事務処理規程 is conformant under 第四号, and a store that cannot
  alter a record is conformant under 第三号ロ. `kotoba.taxlaw` holds the list
  and this namespace reads it from there, so a caller cannot conclude that the
  measure it happens to implement is the only one that counts.

  ## What this actor does NOT provide, stated first

  **The store behind this actor evidences none of the four.** Its protocol
  (`bookkeeping.store/Store`) has `commit-record!` and `records-of` and no
  notion of correction history, deletion, immutability or timestamping —
  measured, not assumed. So `:tamper-evident-system` cannot be evidenced by
  asking this store, and a declaration that says otherwise is refused rather
  than believed.

  That is the honest shape of the gap: the measure is the operator's to
  satisfy, in the system that actually holds the records, and this namespace
  says which one was claimed, whether anything shows it, and refuses to call
  the arrangement conformant when nothing does.

  ## Declared, never inferred

  The measure is read from the CLIENT record, the way `bookkeeping.governor`
  reads jurisdiction from there: a caller able to pass its own measure could
  pass the one it satisfies."
  (:require [kotoba.taxlaw :as taxlaw]
            [bookkeeping.store :as store]
            [clojure.string :as str]))

(def read-for
  "The jurisdictions whose 電子取引 preservation article this file read.

  Not a copy of the catalog — the same statement `bookkeeping.shinkoku` and
  `bookkeeping.houjinzei` make about themselves, so that cataloguing a second
  jurisdiction cannot silently make 規則第四条第一項 apply to it."
  #{[:jp]})

(def record-item-keys
  "The three 記録項目 an 電子取引 record must carry.

  Read from `bookkeeping.kensaku`'s reading of the same three items, which
  規則第四条第一項 reaches by reference to 第二条第六項第五号. Held here as
  keys on a record rather than on a posting, because an 電子取引 record is not
  a journal entry: it is the transaction information itself."
  {:transaction-date :transaction-date
   :amount :amount
   :counterparty :counterparty})

;; ---------------------------------------------------------------------------
;; the declared measure
;; ---------------------------------------------------------------------------

(defn declared-measure
  "The 第四条第一項 measure this client declares, or nil.

  nil is `nobody said`, and it is NOT `no measure needed` — the two are
  distinguished all the way to the status, for the reason
  `bookkeeping.kensaku` distinguishes `:not-declared` from `:non-conformant`."
  [client]
  (:preservation-measure client))

(defn recognised?
  "Is `m` one of the four the article lists here? nil for an uncatalogued
  jurisdiction — never false, because *unknown* and *no* are different."
  [jurisdiction m]
  (taxlaw/preservation-measure? jurisdiction m))

;; ---------------------------------------------------------------------------
;; evidence
;; ---------------------------------------------------------------------------

(def evidence-required
  "What each measure needs before this namespace will treat it as held.

  Not a formality. A declaration with nothing behind it is the failure this
  whole file exists to prevent: an operator who has written
  `:preservation-measure :tamper-evident-system` in a client record has
  satisfied a keyword, not 第三号."
  {:timestamp-before-exchange
   {:needs :timestamp
    :why "第一号 requires the record to have carried a timestamp BEFORE the exchange; the evidence is the timestamp token"}
   :timestamp-after-exchange
   {:needs :timestamp
    :why "第二号 requires a timestamp applied after the exchange; ロ additionally requires a written 規程 when it is not applied promptly"}
   :tamper-evident-system
   {:needs :system-attestation
    :why "第三号 is a property of the SYSTEM holding the records, not of a record; the evidence is an attestation naming that system and which of イ (history visible) or ロ (alteration impossible) it provides"}
   :written-procedure
   {:needs :procedure-document
    :why "第四号 is a written 事務処理規程 and the evidence is the document reference"}})

(defn evidence-problems
  "Why `evidence` does not support `measure`, as a vector. Empty means it does.

  The check is deliberately shallow — it asks whether the operator supplied
  the KIND of thing the measure needs, not whether that thing is true. This
  namespace cannot verify a timestamp token or audit a system, and pretending
  otherwise would be worse than saying so."
  [measure evidence]
  (let [{:keys [needs why]} (get evidence-required measure)]
    (cond
      (nil? needs) [{:problem :unknown-measure :detail (str (pr-str measure) " is not one of the four")}]
      (not (map? evidence)) [{:problem :no-evidence :detail why}]
      (not (contains? evidence needs)) [{:problem :wrong-evidence
                                         :detail (str "expected " needs " — " why)}]
      (str/blank? (str (get evidence needs))) [{:problem :empty-evidence :detail why}]
      :else [])))

;; ---------------------------------------------------------------------------
;; the records
;; ---------------------------------------------------------------------------

(defn record-item-problems
  "Records missing one of the three 記録項目, each named with what is absent.

  Returned as data rather than a count, because the caller has to fix them
  one at a time and a number is not something anyone can act on."
  [records]
  (vec (keep (fn [r]
               (let [missing (vec (sort (keep (fn [[item k]]
                                                (when (str/blank? (str (get r k))) item))
                                              record-item-keys)))]
                 (when (seq missing)
                   {:record-id (:record-id r) :missing missing})))
             records)))

;; ---------------------------------------------------------------------------
;; conformance
;; ---------------------------------------------------------------------------

(defn conformance
  "Does this client's 電子取引 arrangement meet 規則第四条第一項?

  Takes `{:store s :client-id c :records [...] :evidence {...}}`. The
  jurisdiction and the measure are read from the CLIENT record; only the
  evidence comes from the caller, because evidence is the one thing the
  caller genuinely holds.

  `:hozon/status` is one of SIX values and only the last is a pass:

    :unchecked-jurisdiction  the client's jurisdiction is not one this file
                             read. Answering 適合 against a Japanese
                             ministerial ordinance for records kept elsewhere
                             is not a lenient answer, it is an answer to a
                             different question. `kotoba.taxlaw`'s recorded
                             reason rides along where there is one.
    :measure-not-declared    nobody said which of the four. **Not a failure
                             and not a pass** — the article is satisfied by
                             any one of them and this client may well satisfy
                             one, but nothing here says so.
    :measure-not-recognised  declared something the article does not list.
    :measure-unevidenced     declared one of the four with nothing behind it.
                             The measure and what it would need ride along.
    :records-missing-items   some record lacks 取引年月日, 取引金額 or 取引先.
                             Named, per record.
    :conformant              the pass.

  Every non-pass still carries what it reached, so a caller can act.

  **The pass is narrow and this docstring is the place to say how.** It means:
  the client declared one of the four measures, supplied the kind of evidence
  that measure needs, and every record carries the three 記録項目. It does NOT
  mean the timestamp is valid, the system is what the attestation claims, or
  the 規程 is followed. Those are audits, not lookups."
  [{:keys [store client-id records evidence]}]
  (let [client (store/client store client-id)
        j (:jurisdiction client)
        path (if (vector? j) j (when (some? j) [j]))
        measure (declared-measure client)
        recs (vec (or records []))
        item-problems (record-item-problems recs)
        ev-problems (when (and measure (contains? evidence-required measure))
                      (evidence-problems measure evidence))
        status (cond
                 (not (contains? read-for path)) :unchecked-jurisdiction
                 (nil? measure) :measure-not-declared
                 (not (true? (recognised? path measure))) :measure-not-recognised
                 (seq ev-problems) :measure-unevidenced
                 (seq item-problems) :records-missing-items
                 :else :conformant)]
    (cond-> {:hozon/status status
             :hozon/jurisdiction path
             :hozon/measure measure
             :hozon/provision "電子帳簿保存法施行規則 第四条第一項"
             :hozon/records (count recs)
             ;; Carried on every status: the operator has to be able to see
             ;; that three other measures would also satisfy the article.
             :hozon/measures-available (when path (mapv :measure (taxlaw/preservation-measures path)))
             ;; And carried on every status including the pass, because it is
             ;; the standing limit of this actor rather than a fault of any
             ;; one client.
             :hozon/store-evidences-nothing
             (str "bookkeeping.store/Store has no correction history, no "
                  "immutability and no timestamping, so none of the four "
                  "measures can be evidenced by asking this store")}
      (seq item-problems) (assoc :hozon/record-problems item-problems)
      (seq ev-problems) (assoc :hozon/evidence-problems ev-problems
                               :hozon/evidence-required (get evidence-required measure))
      (= :unchecked-jurisdiction status)
      (assoc :hozon/why (when path
                          (taxlaw/out-of-scope path :jurisdiction/preservation-integrity))))))

(defn conformant?
  "Only `:conformant`, and only for the narrow claim the docstring above
  describes. Named as the conservative boolean the way
  `bookkeeping.kensaku/conformant?` is."
  [r]
  (= :conformant (:hozon/status r)))
