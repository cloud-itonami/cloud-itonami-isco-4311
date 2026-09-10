(ns bookkeeping.edge.endpoints-test
  "The surface, and the three ways a surface undoes the actor behind it.

  1. It opens when nothing is configured.
  2. It lets the caller say who it is.
  3. It reports an empty answer as a good one.

  Every test here is one of those."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.edge.endpoints :as e]))

(def ^:private did-a "did:key:z6MkAAA")
(def ^:private did-b "did:key:z6MkBBB")
(def ^:private allow (e/parse-allowlist (str did-a "=c-1," did-b "=c-2")))

(defn- fresh []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "c-1" :name "Hanako's Bakery"})
    (store/register-client! st {:client-id "c-2" :name "Taro's Garage"})
    (store/register-source-doc! st {:doc-id "d1" :client-id "c-1" :kind :receipt})
    (store/register-source-doc! st {:doc-id "d2" :client-id "c-2" :kind :receipt})
    st))

(def ^:private balanced
  (pr-str {:source-doc "d1"
           :lines [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
                   {:side :cr :account "cash" :amount 5000 :currency "JPY"}]}))

;; ---------------------------------------------------------------------------
;; 1. it must not open when nothing is configured
;; ---------------------------------------------------------------------------

(deftest an-absent-allowlist-serves-503-not-an-open-endpoint
  (testing "'nobody is allowed' and 'nothing was configured' are different
            deployment states and must not share a status code"
    (is (= 503 (:status (e/draft-entry-core! (fresh) :ephemeral nil did-a balanced))))
    (is (= 503 (:status (e/trial-balance-core (fresh) nil did-a))))))

(deftest a-blank-or-malformed-allowlist-is-absent-not-empty
  (is (nil? (e/parse-allowlist nil)))
  (is (nil? (e/parse-allowlist "")))
  (is (nil? (e/parse-allowlist "   ")))
  (is (nil? (e/parse-allowlist ",,,")))
  (testing "one good pair among rubbish is still a configured allow-list"
    (is (= {did-a "c-1"} (e/parse-allowlist (str ",," did-a "=c-1,="))))))

(deftest a-verified-but-unlisted-caller-is-refused
  (is (= 403 (:status (e/draft-entry-core! (fresh) :ephemeral allow "did:key:z6MkZZZ" balanced))))
  (is (= 403 (:status (e/trial-balance-core (fresh) allow "did:key:z6MkZZZ")))))

(deftest an-unconfigured-store-blames-the-deployment-not-the-caller
  (let [r (e/store-unconfigured-response)]
    (is (= 503 (:status r)))
    (is (re-find #"BOOKKEEPING_STORE" (get-in r [:body :hint])))
    (testing "the hint is one line — a multi-line literal leaks source
              indentation into the JSON body"
      (is (not (re-find #"\n" (get-in r [:body :hint])))))))

(deftest a-typo-in-the-store-variable-selects-nothing
  (is (= :ephemeral (e/store-mode {"BOOKKEEPING_STORE" "ephemeral"})))
  (is (nil? (e/store-mode {"BOOKKEEPING_STORE" "ephemerl"})))
  (is (nil? (e/store-mode {}))))

;; ---------------------------------------------------------------------------
;; 2. the caller must not get to say who it is
;; ---------------------------------------------------------------------------

(deftest the-client-comes-from-the-allowlist-never-the-body
  (testing "a caller that could nominate its own client could nominate one
            whose ledger it may read"
    (is (nil? (e/parse-entry-body
               (pr-str {:client-id "c-2" :source-doc "d2"
                        :lines [{:side :dr :account "a" :amount 1 :currency "JPY"}
                                {:side :cr :account "b" :amount 1 :currency "JPY"}]})))
        "rejected outright, not silently ignored — a caller must not believe
         it wrote somewhere it did not")
    (let [r (e/draft-entry-core! (fresh) :ephemeral allow did-a balanced)]
      (is (= "c-1" (get-in r [:body :client]))))))

(deftest one-caller-cannot-read-anothers-ledger
  (let [st (fresh)]
    (e/draft-entry-core! st :ephemeral allow did-a balanced)
    (is (= 1 (get-in (e/trial-balance-core st allow did-a) [:body :posting-count])))
    (testing "the store scopes postings per client; the surface must not undo it"
      (is (= 0 (get-in (e/trial-balance-core st allow did-b) [:body :posting-count]))))))

(deftest a-malformed-line-is-refused-not-guessed-at
  (testing "a line whose side is unrecognised would drop out of the
            projection, and the remainder would balance by having lost it"
    (doseq [bad [(pr-str {:source-doc "d1" :lines [{:side :sideways :account "a" :amount 1}]})
                 (pr-str {:source-doc "d1" :lines [{:side :dr :account "a"}]})
                 (pr-str {:source-doc "d1" :lines [{:side :dr :account 42 :amount 1}]})
                 (pr-str {:source-doc "d1" :lines []})
                 (pr-str {:lines [{:side :dr :account "a" :amount 1}]})
                 "{:unbalanced-parens"]]
      (is (nil? (e/parse-entry-body bad)) (str "should refuse " (subs bad 0 (min 40 (count bad))))))
    (is (= 400 (:status (e/draft-entry-core! (fresh) :ephemeral allow did-a "nonsense"))))))

;; ---------------------------------------------------------------------------
;; 3. it must not report an empty answer as a good one
;; ---------------------------------------------------------------------------

(deftest an-empty-ledger-is-distinguishable-from-a-balanced-one
  (testing ":balanced? is false for both, correctly — :posting-count is what
            tells a JSON reader which one it is looking at"
    (let [r (e/trial-balance-core (fresh) allow did-a)]
      (is (= 200 (:status r)))
      (is (= 0 (get-in r [:body :posting-count])))
      (is (false? (get-in r [:body :balanced?]))))))

(deftest a-committed-entry-shows-in-the-read
  (let [st (fresh)
        w (e/draft-entry-core! st :ephemeral allow did-a balanced)
        r (e/trial-balance-core st allow did-a)]
    (is (= 200 (:status w)))
    (is (true? (get-in w [:body :ok])))
    (is (some? (get-in w [:body :posting])) "the posting id is reported")
    (is (= 1 (get-in r [:body :posting-count])))
    (is (true? (get-in r [:body :balanced?])))
    (is (= {:debit 5000 :credit 0 :balance 5000}
           (get-in r [:body :balances "supplies/JPY"])))))

(deftest a-held-entry-comes-back-409-with-its-violations
  (let [cross (pr-str {:source-doc "d1"
                       :lines [{:side :dr :account "supplies" :amount 5000 :currency "JPY"}
                               {:side :cr :account "cash" :amount 5000 :currency "USD"}]})
        r (e/draft-entry-core! (fresh) :ephemeral allow did-a cross)]
    (is (= 409 (:status r)))
    (is (some #(= :unbalanced-entry (:rule %)) (get-in r [:body :violations])))))

(deftest an-unregistered-source-document-is-held-not-accepted
  (let [r (e/draft-entry-core! (fresh) :ephemeral allow did-a
                               (pr-str {:source-doc "no-such-doc"
                                        :lines [{:side :dr :account "a" :amount 1 :currency "JPY"}
                                                {:side :cr :account "b" :amount 1 :currency "JPY"}]}))]
    (is (= 409 (:status r)))
    (is (some #(= :unknown-source-doc (:rule %)) (get-in r [:body :violations])))))
