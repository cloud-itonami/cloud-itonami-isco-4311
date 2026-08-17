#!/usr/bin/env nbb
;; Re-fetch every URL in `bookkeeping.jurisdictions` and report the status.
;;
;; The catalog claims those URLs returned 200 on 2026-08-17. That claim
;; rots — instruments move, ministries reorganise their sites. This script
;; is how the claim stays checkable rather than becoming a date nobody
;; re-measures.
;;
;;   nbb tools/verify_citations.cljs
;;
;; Exit codes are three-valued on purpose (CLAUDE.md, ADR-2608136000):
;;
;;   0  every URL answered 2xx
;;   1  at least one URL did NOT answer 2xx        -> a citation went bad
;;   2  the run could not answer the question      -> no URLs parsed, or
;;      the network itself is unavailable. NOT the same as clean, and not
;;      the same as a bad citation. A checker that reports `all fine` when
;;      it could not reach anything is the failure mode this repo's
;;      governor exists to refuse.
;;
;; Reads the URLs out of the .cljc source by regex rather than requiring
;; the namespace: nbb would have to load `clojure.string` and the whole
;; governor graph to get at a list of strings.
;;
;; It matches `:source/url "..."` specifically, NOT every https:// in the
;; file. Measured 2026-08-17, the first version of this script matched
;; every URL and so re-probed the entry in `:catalog/rejected` — the one
;; the catalog exists to record as NOT a citation — and reported a FAIL
;; for it. A checker that fails on the thing you already declared absent
;; is a checker nobody will keep running.

(ns verify-citations
  (:require [clojure.string :as str]
            ["fs" :as fs]))

(def ^:private catalog "src/bookkeeping/jurisdictions.cljc")

(defn- urls []
  (if-not (fs/existsSync catalog)
    []
    (->> (fs/readFileSync catalog "utf8")
         (re-seq #":source/url\s+\"(https://[^\"]+)\"")
         (map second)
         distinct
         sort
         vec)))

(defn- probe [url]
  (-> (js/fetch url #js {:method "GET" :redirect "follow"})
      (.then (fn [r] {:url url :status (.-status r)}))
      (.catch (fn [e] {:url url :status 0 :error (str (.-message e))}))))

(defn -main []
  (let [us (urls)]
    (if (empty? us)
      ;; evidence floor: zero URLs scanned is not zero URLs broken.
      (do (println "SCANNED\t0")
          (println "Refusing to report a pass: no citations found in" catalog)
          (js/process.exit 2))
      (-> (js/Promise.all (clj->js (map probe us)))
          (.then
           (fn [rs]
             (let [rs   (js->clj rs :keywordize-keys true)
                   ok   (filter #(<= 200 (:status %) 299) rs)
                   bad  (remove #(<= 200 (:status %) 299) rs)
                   dead (filter #(zero? (:status %)) rs)]
               (println "SCANNED\t" (count rs))
               (doseq [{:keys [url status error]} (sort-by :url rs)]
                 (println (if (<= 200 status 299) "  ok  " "  FAIL")
                          status "\t" url (when error (str "  " error))))
               (println)
               (println (count ok) "/" (count rs) "reachable")
               (cond
                 ;; every probe failed to connect at all -> we learned nothing
                 ;; about the citations, only about this machine's network.
                 (= (count dead) (count rs))
                 (do (println "Refusing to report a verdict: every request failed to connect.")
                     (js/process.exit 2))

                 (seq bad) (js/process.exit 1)
                 :else     (js/process.exit 0)))))))))

(-main)
