(ns bookkeeping.batch-test
  "The carrier's route, and the two ways a batch endpoint lies.

  1. It collapses many outcomes into one status.
  2. It is silent about whether a retry wrote anything."
  (:require [clojure.test :refer [deftest is testing]]
            [bookkeeping.store :as store]
            [bookkeeping.edge.endpoints :as e]))

(def ^:private did "did:key:z6MkAAA")
(def ^:private allow (e/parse-allowlist (str did "=c-1")))

(defn- seeded []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "c-1" :name "Hanako's Bakery"})
    (doseq [d ["d1" "d2" "d3"]]
      (store/register-source-doc! st {:doc-id d :client-id "c-1" :kind :receipt}))
    st))

(defn- entry [doc amount account]
  {:source-doc doc
   :lines [{:side :dr :account account :amount amount :currency "JPY"}
           {:side :cr :account "cash" :amount amount :currency "JPY"}]})

(defn- post! [st es] (e/entries-core! st :ephemeral allow did (pr-str (vec es))))

;; ---------------------------------------------------------------------------
;; 1. it must not collapse many outcomes into one status
;; ---------------------------------------------------------------------------

(deftest a-mixed-batch-is-neither-a-success-nor-a-failure
  (testing "collapsing to 200 loses the refusals; collapsing to 4xx discards
            the entries that posted. The answer is the per-entry list."
    (let [r (post! (seeded)
                   [(entry "d1" 5000 "supplies")
                    (entry "no-such-doc" 5000 "supplies")
                    (entry "d2" 3000 "utilities")])]
      (is (= 207 (:status r)))
      (is (= [:posted :held :posted] (mapv :outcome (get-in r [:body :results]))))
      (is (= {:posted 2 :held 1} (get-in r [:body :summary]))))))

(deftest the-status-is-207-whatever-happens
  (testing "a caller that reads only the status learns nothing, which is the
            correct amount to learn from a status here"
    (doseq [es [[(entry "d1" 5000 "supplies")]
                [(entry "no-such-doc" 1 "a")]
                [(entry "d1" 1 "a") (entry "d2" 2 "b")]]]
      (is (= 207 (:status (post! (seeded) es)))))))

(deftest a-refusal-carries-what-was-refused
  (let [r (post! (seeded) [(entry "no-such-doc" 5000 "supplies")])
        one (first (get-in r [:body :results]))]
    (is (= :held (:outcome one)))
    (is (= "no-such-doc" (:source-doc one)))
    (is (some #(= :unknown-source-doc (:rule %)) (:violations one))
        "or the caller cannot act on it")))

(deftest results-are-in-the-order-submitted
  (testing "a carrier reconciles by position; reordering would misattribute
            every outcome"
    (let [r (post! (seeded) [(entry "d1" 1 "a") (entry "d2" 2 "b") (entry "d3" 3 "c")])]
      (is (= ["d1" "d2" "d3"] (mapv :source-doc (get-in r [:body :results])))))))

(deftest an-earlier-refusal-does-not-stop-a-later-entry
  (testing "all-or-nothing would let one malformed line discard a day of good
            entries, and there is no transaction to roll back into"
    (let [st (seeded)
          _ (post! st [(entry "no-such-doc" 1 "a") (entry "d1" 5000 "supplies")])]
      (is (= 1 (count (store/postings-of st "c-1")))))))

;; ---------------------------------------------------------------------------
;; 2. it must say whether a retry wrote anything
;; ---------------------------------------------------------------------------

(deftest re-sending-the-batch-reports-duplicate-not-posted
  (testing "idempotent and indistinguishable is only half of what a carrier
            needs — before :duplicate? a retry and a first post returned
            byte-identical 200s"
    (let [st (seeded)
          es [(entry "d1" 5000 "supplies") (entry "d2" 3000 "utilities")]
          first-run (post! st es)
          second-run (post! st es)]
      (is (= {:posted 2} (get-in first-run [:body :summary])))
      (is (= {:duplicate 2} (get-in second-run [:body :summary])))
      (is (= 2 (count (store/postings-of st "c-1")))
          "and re-sending wrote nothing"))))

(deftest the-single-endpoint-says-it-too
  (let [st (seeded)
        b (pr-str (entry "d1" 5000 "supplies"))]
    (is (false? (get-in (e/draft-entry-core! st :ephemeral allow did b) [:body :duplicate?])))
    (is (true? (get-in (e/draft-entry-core! st :ephemeral allow did b) [:body :duplicate?])))))

;; ---------------------------------------------------------------------------
;; refusals of the batch itself
;; ---------------------------------------------------------------------------

(deftest an-empty-batch-is-refused
  (testing "an empty batch reporting {:posted 0} is a clean run over nothing"
    (is (= 400 (:status (post! (seeded) []))))
    (is (= 400 (:status (e/entries-core! (seeded) :ephemeral allow did "not-edn"))))
    (is (= 400 (:status (e/entries-core! (seeded) :ephemeral allow did
                                         (pr-str {:source-doc "d1"})))) 
        "a single entry is not a batch")))

(deftest an-oversized-batch-is-refused-with-the-limit
  (let [big (vec (repeat (inc e/max-batch) (entry "d1" 1 "a")))
        r (e/entries-core! (seeded) :ephemeral allow did (pr-str big))]
    (is (= 400 (:status r)))
    (is (= e/max-batch (get-in r [:body :max])) "the caller learns the limit from the refusal")
    (is (= (inc e/max-batch) (get-in r [:body :submitted])))))

(deftest the-batch-route-has-the-same-two-gates
  (is (= 503 (:status (e/entries-core! (seeded) :ephemeral nil did "[]"))))
  (is (= 403 (:status (e/entries-core! (seeded) :ephemeral allow "did:key:zZZZ" "[]")))))

(deftest the-batch-cannot-name-a-client-either
  (testing "each entry goes through the same parser, which rejects a body
            naming a client rather than ignoring it"
    (let [r (post! (seeded) [(assoc (entry "d1" 1 "a") :client-id "c-2")])]
      (is (= :rejected (:outcome (first (get-in r [:body :results]))))))))
