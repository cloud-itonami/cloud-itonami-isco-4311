(ns bookkeeping.store
  "SSoT for the ISCO-08 4311 community bookkeeping actor. Store is a
  protocol injected into the `bookkeeping.actor` StateGraph — `MemStore`
  is the default, deterministic, zero-dep backend; a Datomic/
  kotoba-server-backed implementation can be swapped in without touching
  the actor or governor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-2411's
  accounting.store, with one bookkeeping-specific addition: a SOURCE
  DOCUMENT registry.

  Domain:

    client      — a registered bookkeeping client (:client-id, :name)
    source-doc  — a registered source document (:doc-id, :client-id,
                  :kind e.g. :receipt/:bank-statement/:invoice-received).
                  Every journal-entry draft MUST cite one — a journal
                  entry without a source document is an invented
                  transaction, and the governor HARD-holds it (the
                  fleet's no-fabricated-spec-basis discipline, transposed
                  to bookkeeping).
    record      — a committed operating record (journal-entry draft,
                  reconciliation note, issued invoice, period close) —
                  written ONLY via commit-record!, never mutated.
    chart       — this client's chart of accounts, `{account {:type kw
                  :section kw :concept str}}`. Supplied, never inferred:
                  `kotoba.shohyo` refuses to guess what an account is, and
                  a default here would answer the question before it was
                  asked.
    posting     — the double-entry posting a committed journal entry
                  projected to (`kotoba.banking`, via bookkeeping.posting).
                  This is where an approved entry LANDS; before it existed,
                  an approved entry was a decision with no destination.
    ledger      — an append-only audit trail of every proposal/verdict/
                  disposition, regardless of outcome (commit or hold).

  ## Two backends, and why the contract test is the real deliverable

  `MemStore` is the deterministic zero-dep default; `DatomicStore` is the
  same protocol over `langchain.db`. Swapping them must be a swap — the
  actor, the governor, the trial balance and the edge unchanged.

  `postings-of` is the one that makes this more than hygiene here.
  `bookkeeping.trial-balance` reads THROUGH it, so a backend that returned
  postings out of order, unscoped, or de-duplicated would not produce an
  error: it would produce a different balance sheet. That failure is silent
  and authoritative-looking, which is exactly the kind the contract test
  exists for."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (client [s client-id])
  (source-doc [s doc-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-source-doc! [s doc])
  (commit-record! [s record])
  ;; The postings a committed journal entry produced, in commit order.
  ;; Separate from `records-of` because a record is what the actor decided
  ;; and a posting is what the ledger holds — an entry that could not be
  ;; projected produces a record and no posting, and conflating the two
  ;; would make that look like a posting of zero.
  (postings-of [s client-id])
  (commit-posting! [s client-id posting])
  ;; A chart of accounts is PER CLIENT and supplied, never inferred. There is
  ;; no default: `kotoba.shohyo` refuses to guess what an account is, and a
  ;; store that shipped one would make that refusal cosmetic by answering the
  ;; question before it was asked.
  (chart-of [s client-id])
  (register-chart! [s client-id chart])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (source-doc [_ doc-id] (get-in @a [:source-docs doc-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-source-doc! [s doc]
    (swap! a assoc-in [:source-docs (:doc-id doc)] doc) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (postings-of [_ client-id]
    (vec (get-in @a [:postings client-id] [])))
  (commit-posting! [s client-id posting]
    ;; Idempotent on the posting id. A carrier that retries is normal, and
    ;; before this a retry appended a SECOND posting under the same id and
    ;; doubled the trial balance. Appending is still the only mutation --
    ;; nothing already committed is replaced, because a posting that changed
    ;; under a stable id would be an edit to the ledger.
    (swap! a update-in [:postings client-id]
           (fn [ps]
             (let [ps (or ps [])]
               (if (some #(= (:ledger/posting posting) (:ledger/posting %)) ps)
                 ps
                 (conj ps posting)))))
    s)
  (chart-of [_ client-id] (get-in @a [:charts client-id]))
  (register-chart! [s client-id chart]
    (swap! a assoc-in [:charts client-id] chart) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(def ^:private schema
  (ls/identity-schema [:client/id :doc/id :chart/client :record/seq :posting/seq :ledger/seq]))

(defn- next-seq [conn seq-attr]
  (count (d/q [:find '?e :where ['?e seq-attr '_]] (d/db conn))))

(defrecord DatomicStore [conn]
  Store
  (client [_ client-id] (ls/blob-lookup conn :client/id :client/edn client-id))
  (source-doc [_ doc-id] (ls/blob-lookup conn :doc/id :doc/edn doc-id))
  (records-of [_ client-id]
    (filterv #(= client-id (:client-id %))
             (ls/read-stream conn :record/seq :record/edn)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (register-client! [s c]
    (ls/put-blob! conn :client/id :client/edn (:client-id c) c) s)
  (register-source-doc! [s doc]
    (ls/put-blob! conn :doc/id :doc/edn (:doc-id doc) doc) s)
  (commit-record! [s record]
    (ls/append-blob! conn :record/seq :record/edn
                     (next-seq conn :record/seq) record) s)
  ;; Postings are stored as one seq-keyed stream carrying the client on each
  ;; entry, and filtered on read — the same shape as records. Keying the
  ;; stream per client instead would make `next-seq` per client too, and two
  ;; clients committing at the same seq would collide on a
  ;; `:db.unique/identity` attribute and UPSERT one over the other.
  (chart-of [_ client-id]
    (ls/blob-lookup conn :chart/client :chart/edn client-id))
  (register-chart! [s client-id chart]
    (ls/put-blob! conn :chart/client :chart/edn client-id chart) s)
  (postings-of [_ client-id]
    (mapv :posting
          (filterv #(= client-id (:client-id %))
                   (ls/read-stream conn :posting/seq :posting/edn))))
  (commit-posting! [s client-id posting]
    ;; Same idempotency as MemStore, and the contract test holds them to it
    ;; together -- a backend that double-posted on retry would double one
    ;; client's books and not the other's.
    (when-not (some #(= (:ledger/posting posting) (:ledger/posting %))
                    (postings-of s client-id))
      (ls/append-blob! conn :posting/seq :posting/edn
                       (next-seq conn :posting/seq)
                       {:client-id client-id :posting posting}))
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact
                     (next-seq conn :ledger/seq) fact) s))

(defn datomic-store
  "A DatomicStore over a fresh in-process `langchain.db` connection.

  In-process is the DEFAULT, not the guarantee. Durability is whatever
  `langchain.db`'s `:db-api` is bound to; with the default in-process
  DataScript it survives no longer than `MemStore` does. What it buys
  unconditionally is that the swap is a swap, and the contract test proves
  the two answer identically."
  []
  (->DatomicStore (d/create-conn schema)))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :source-docs {}
                                    :records [] :postings {} :charts {} :ledger []}
                                   seed)))))
