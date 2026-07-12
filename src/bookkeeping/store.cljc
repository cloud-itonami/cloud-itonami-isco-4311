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
    ledger      — an append-only audit trail of every proposal/verdict/
                  disposition, regardless of outcome (commit or hold)."
  )

(defprotocol Store
  (client [s client-id])
  (source-doc [s doc-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-source-doc! [s doc])
  (commit-record! [s record])
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
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :source-docs {}
                                    :records [] :ledger []}
                                   seed)))))
