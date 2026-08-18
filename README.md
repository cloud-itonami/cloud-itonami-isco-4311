# cloud-itonami-isco-4311

**Community Bookkeeping Service** — the ISCO-08 4311 (Accounting and
Bookkeeping Clerks) actor, an ISCO **Wave 0 (cognitive substrate)**
occupation per ADR-2607121000: pure-cognitive work, the LLM-first
wave, no robotics gate.

**Maturity: `:implemented`** — BookkeepingAdvisor ⊣
BookkeepingClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt for escalations), modeled on cloud-itonami-isco-2411's
accounting actor. 88 tests / 287 assertions green.

Five bookkeeping-specific HARD invariants (never approvable past):

1. **Source-document basis** — a journal-entry draft must cite a
   REGISTERED source document belonging to the same client. A journal
   entry without a source document is an invented transaction (the
   fleet's fabricated-spec-basis rule, bookkeeping edition).
2. **Double-entry balance** — debit total must equal credit total.
   A human approver cannot approve their way past bad arithmetic.
3. **Checked jurisdiction** — a proposal claiming 仕入税額控除
   (`:tax-treatment :input-tax-credit`) whose client's jurisdiction is
   not in `bookkeeping.jurisdictions` is HELD. **An unchecked
   jurisdiction is a hold, not a pass** — including an undeclared one,
   which is unchecked rather than domestic-by-default.
4. **Qualified invoice** — where the jurisdiction conditions that credit
   on a 適格請求書, the cited source document must carry a registration
   number in that jurisdiction's format (JP: `T` + 13 digits). A receipt
   silent about its registration number does not become creditable by
   being silent.

5. **電磁的記録の保存** — a source document from an 電子取引 kept only on
   paper (or whose preservation was never recorded) is HELD, citing
   電子帳簿保存法 第七条. **Unlike 3 and 4, this one is not scoped to a tax
   claim** — the article binds the 保存義務者 whenever an 電子取引 happened,
   not only when a credit is being claimed for it. The rule was read from
   the statute before being enforced; see `kotoba-lang/taxlaw`.

Invariants 3 and 4 fire **only** on a proposal that claims the credit;
an entry with no tax claim is unaffected. The actor does not invent a
tax position in order to have one to check.

## Where an approved entry goes

An approved journal entry now LANDS. On commit of a `:draft-entry` the actor
projects it onto [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking)
— the double-entry contract
[`kotoba-lang/kakeibo`](https://github.com/kotoba-lang/kakeibo) was already
using for personal statement rows — and stores the posting:

```text
kakeibo     statement rows  ─┐
                             ├─▶  kotoba.banking   double-entry postings
bookkeeping journal entries ─┘         │
                                       └─▶ bookkeeping.trial-balance  試算表
```

**The previous commit shipped the projection and did not call it.**
`bookkeeping.posting` was written, tested, and reachable only from its own
tests — the same "checkable but nobody invokes it" shape this fleet keeps
finding elsewhere, introduced here while claiming the gap was closed. The
test that would have caught it is `posting-is-reachable-from-the-actor`, and
it is phrased about the ACTOR rather than the projection on purpose.

Only `:draft-entry` projects. `:reconcile`, `:issue-invoice` and
`:close-period` are not journal entries and must not manufacture a posting
to look complete. That guard needed its own discriminating test: `:reconcile`
carries no `:lines`, so the projection already refuses and removing the guard
changed nothing observable — **measured, that mutation survived** until a
case existed for an op that is not a journal entry but does carry lines.

The ledger fact records whether a posting was produced, so an entry that
produced none is auditable rather than invisible.

## 試算表 — reading the ledger back

`bookkeeping.trial-balance` is the first thing in this plane that reads
postings back rather than deciding. `balances` is keyed by
`[account currency]`, **never by account alone** — aggregating by account
would reintroduce the currency bug one layer up, where a total that nets to
zero is exactly the output that stops people looking.

`balanced?` is **false for an empty posting set**. Zero does equal zero, but
an empty ledger has not been shown to balance; it has been shown to be
empty, and reporting those identically is how a check becomes a formality.
`out-of-balance` names the currencies, because a boolean is not enough for
whoever has to fix it.

It proves arithmetic, not classification: this actor has no chart of
accounts, so an account is whatever string an entry named.

### What that exposed

`banking/balanced?` groups by currency before comparing debits to credits.
The check it replaced did not — it summed `:amount` across every line
regardless of `:currency`, so **an entry with 5000 debit in JPY and 5000
credit in USD balanced.** Invariant 2 says a human cannot approve their way
past bad arithmetic; that arithmetic was wrong in the one direction the
invariant exists to catch. It is now held. The fix arrived as a consequence
of using the shared contract rather than as a patch to a private one.

Lines carrying no `:currency` all group under `nil` together, so a
single-currency ledger sees no change — measured: the 34 pre-existing tests
are green before and after.

## The jurisdiction catalog moved out

It lives in [`kotoba-lang/taxlaw`](https://github.com/kotoba-lang/taxlaw)
now — lifted here when a second actor needed the same law. This repo checks
the **receiving** side of インボイス制度 (a journal entry claiming 仕入税額控除
must cite a document carrying a valid registration number); `tehai` checks
the **issuing** side.

What that bought, beyond not having two copies: taxlaw verifies its
citations against the e-Gov corpus (`kotoba-lang/jp.go.e-gov.elaws`, 9,536
laws) rather than by fetching URLs. **A repealed statute serves its page
with HTTP 200 like any other**, so the check that lived here could not have
seen one.

Invariants 3 and 4 read `taxlaw/credit-support`; invariant 5 reads
`taxlaw/record-preservation`. Both answer in three values — `:none` (nobody
catalogued this jurisdiction), `:not-declared` (the record asserted nothing),
`:checked` — and this actor holds only on a checked refusal or an asserted
jurisdiction it cannot evaluate.

**What was not checked is on the verdict, not swallowed.** A document that
declares no `:origin` is not held; it asserted nothing. But `:tax` carries
`{:preservation {:taxlaw/coverage :not-declared}}`, so a console shows *this
was not checked* rather than an unqualified approval — the same device as
kintai's `:unevaluated` and tehai's `:tax`. Measured: dropping that key
reddens three tests.

## 貸借対照表 / 損益計算書

The chain is now whole and reachable:

```text
仕訳 ─▶ governor ─▶ posting ─▶ trial balance ─▶ statements
       (refuses)   (lands)     (arithmetic)     (classification)
```

Classification comes from [`kotoba-lang/shohyo`](https://github.com/kotoba-lang/shohyo),
including the 会社計算規則 区分 and the 段階利益 ladder (売上総 → 営業 →
経常 → 税引前).

**There is no default chart of accounts, and that is the design.** shohyo
refuses to guess what an account is, because a balance sheet that omits an
account still balances. A default here would answer that question before
anyone asked it and make the refusal cosmetic. A client with no chart gets
**409, not an empty 200** — an empty statement is exactly what an
inferred-and-wrong one would look like.

Three distinctions the surface keeps:

- **`200` means the request was answered, not that the books are finished.**
  `:complete?` is a separate field, and unclassified accounts are always
  named — that is the one thing a reader of a balance sheet cannot see for
  themselves.
- **Declared-and-empty is not undeclared.** A chart naming 営業外収益 has
  said the section exists and is empty this period; a chart not naming it has
  said nothing, and the ladder refuses the second. Measured: seeding only
  sections that had balances made every sparse period report
  `:not-declared`, and the test that caught it is
  `declared-and-empty-is-not-undeclared`.
- **第八十九条第二項 survives the JSON boundary.** A caller never receives a
  negative 売上総利益 — it arrives as a positive 売上総損失金額 with its
  article attached.

Measured, all seven mutations red: serve statements without a chart (2+1),
let a chart contradict 会社計算規則 (2), drop the zero-seed for declared
sections (9), stop negating credit-normal sections (5), omit the
unclassified list (1), answer 200 instead of 409 for a missing chart (1),
ignore the caller's client on read (1).

## Two store backends, and the contract test that is the real deliverable

`MemStore` (deterministic, zero-dep) and `DatomicStore` (the same protocol
over `langchain.db`, via `kotoba-lang/langchain-store` — the codec is not
hand-rolled here).

`test/bookkeeping/store_contract_test.clj` runs **every assertion against
both**, because `actor-test`, `governor-test`, `tax-rules-test`,
`ledger-test` and the edge tests all construct `mem-store` and only
`mem-store`. A `DatomicStore` that answered differently would not redden one
of them.

**This matters more here than as hygiene.** `bookkeeping.trial-balance`
reads *through* `postings-of`. A backend returning postings out of order,
unscoped, or de-duplicated raises no error — it produces a **different
balance sheet**, silently and authoritatively. So order and scoping are
asserted directly, and one test compares the whole trial-balance report
across backends rather than trusting counts.

Measured, all five mutations red:

| mutation | reddened |
|---|---|
| **DatomicStore only** — `next-seq` always 0 | 5 contract tests; nothing else |
| **DatomicStore only** — postings ignore the client | 3 contract tests |
| **DatomicStore only** — store the posting id without its entries | 5 contract tests |
| MemStore only — ledger prepends | 1 contract test |
| shrink `backends` to one entry | 3 — the evidence floor |

`datomic-store` does not claim durability it cannot observe: with the default
in-process DataScript it survives no longer than `MemStore`. What it buys
unconditionally is that the swap is a swap.

## The HTTP surface

Two routes, and nothing else:

```
POST /api/entry           submit a journal entry draft
GET  /api/trial-balance   read what the committed postings add up to
GET  /api/statements      read 貸借対照表 / 損益計算書
```

Of the four ops only `:draft-entry` both auto-commits and needs a network
path. `:issue-invoice` and `:close-period` always escalate, so they end in a
human's judgement and are not reachable from a socket at all; `:reconcile`
auto-commits but nothing pushes it.

The read is the other half, and the reason the surface is worth having:
until `bookkeeping.trial-balance` there was nothing to **ask** this actor.
An actor that only accepts is a write-only hole.

### Three ways a surface undoes the actor behind it

Every test in `test/bookkeeping/edge/endpoints_test.clj` is one of these.

**It opens when nothing is configured.** An absent allow-list serves `503`,
never an open endpoint — "nobody is allowed" and "nothing was configured"
are different deployment states. An unconfigured store also serves `503`
rather than an empty in-process one, because an empty store makes every
request fail the registration check and the caller gets blamed
(`:no-client`) for a deployment fault.

**It lets the caller say who it is.** The client id comes from the
allow-list, keyed by verified DID. A body naming `:client-id` is **rejected
outright**, not ignored — silently dropping it would let a caller believe it
had written somewhere it had not. This is the same rule the governor applies
to jurisdiction: a caller that could nominate its own client could nominate
one whose ledger it may read, undoing the store's per-client scoping one
layer up.

**It reports an empty answer as a good one.** `:balanced?` is false for both
an empty ledger and an unbalanced one — correctly — so the read always
carries `:posting-count`, which is what tells a JSON reader which it is
looking at.

Measured: all five mutations red — opening on an absent allow-list, honouring
a body-supplied client on write, on read, dropping `:posting-count`, and
accepting an unrecognised `:side` (which would drop out of the projection and
let the remainder balance by having lost a line).

Escalations (always human sign-off): `:issue-invoice` (external-send),
`:close-period` (hard to reverse), low confidence (< 0.6). The advisor
only ever proposes (`:effect :propose`); every `commit-record!` is
gated behind the governor's verdict.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
