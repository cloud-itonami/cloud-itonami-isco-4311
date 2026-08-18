# cloud-itonami-isco-4311

**Community Bookkeeping Service** — the ISCO-08 4311 (Accounting and
Bookkeeping Clerks) actor, an ISCO **Wave 0 (cognitive substrate)**
occupation per ADR-2607121000: pure-cognitive work, the LLM-first
wave, no robotics gate.

**Maturity: `:implemented`** — BookkeepingAdvisor ⊣
BookkeepingClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt for escalations), modeled on cloud-itonami-isco-2411's
accounting actor. 129 tests / 429 assertions green.

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

## 元帳 and 仕訳帳

The plane could say what an account's *balance* was and what the statements
looked like. Neither answers what a bookkeeper asks first: **what happened to
this account, in order.** A balance is a number you have to trust; a 元帳 is
the number with its working shown.

**Order is the whole point.** A 総勘定元帳 whose lines are not in committed
order is a set with a running total drawn on it, and the total is then
meaningless. `store/postings-of` returns commit order — both backends are
held to that by the contract test — and `bookkeeping.motochou` does not
re-sort. A test asserts it does not: given a different order it *reports*
that order rather than fixing it, because silently substituting an order for
the one that was enforced is the failure worth catching.

**Per currency, again.** One running total across currencies produces a
column that looks exactly like a ledger and means nothing — the same failure
this actor shipped once at the entry level.

**Two ways to be empty, and they differ.** An account the chart does not
name is `404`, not an empty `200`: a blank page cannot be told from a typo.
An account that is known and had no activity is `200` with nothing in it.
And the 404 lists `:accounts-with-activity`, because an account that received
a posting while absent from the chart is real activity and must not become
invisible just because the ledger refuses to open it.

The journal reports the `:balanced?` flag `kotoba.banking` already put on
each posting rather than recomputing it — a second opinion here could
disagree with the one the governor actually enforced.

Measured, all seven mutations red: sort the ledger by posting id (2),
run one balance across currencies (13), add credits instead of subtracting
(5), open an unknown account as an empty ledger (3), offset the running
balance by one line (4), recompute `:balanced?` (2), hide chart-less accounts
from the activity list (1).

## The carrier's route

Four actors (`keihi`, `isco-4313`, `tehai`, `shiharai-actor`) now emit
`:draft-entry` requests. `POST /api/entries` is where a carrier delivers
them.

### Always 207, never 200

A batch of fifty with three refusals is not a success and is not a failure.
Collapsing it to 200 loses the three; collapsing it to 4xx discards the
forty-seven. **The status is the same whatever happens, and the answer is the
per-entry list** — a caller that reads only the status learns nothing, which
is the right amount to learn from a status here.

`:summary` counts outcomes as `:posted` / `:duplicate` / `:held` /
`:rejected`, separately, so no single number can be read as "it worked".
Results are in submission order, because a carrier reconciles by position.

### Not atomic, deliberately

An earlier refusal does not stop a later entry. All-or-nothing would let one
malformed line discard a day of good entries, and there is no transaction to
roll back into — `commit-posting!` has already appended by the time the next
entry is read.

### Idempotent *and* distinguishable

Re-sending a batch is safe: posting ids are content-addressed and
`commit-posting!` is idempotent. But **idempotent and indistinguishable is
only half of what a carrier needs** — until `:duplicate?` existed, a retry
and a first post returned byte-identical 200s and the carrier could not tell
whether it had just written or merely re-sent. Both the single and the batch
route now say which.

Measured, all eight mutations red: collapse an all-posted batch to 200 (2),
report a duplicate as posted (1), hard-code `:duplicate?` false (2), filter
the failures out of the results (6), accept an empty batch (1), remove the
size cap (2), stop at the first refusal (2), reorder the results (1).

## 消費税 — the figures a 申告 starts from, and what they are not

`bookkeeping.shohizei` aggregates the ledger's 仮受消費税 / 仮払消費税, **by
rate**, and **refuses to emit a 納付税額**. 消費税法 第四十五条第一項 is
quoted in `provisions` so the refusal can be checked rather than believed
(revision `363AC0000000108_20260401_508AC0000000012`, retrieved 2026-08-18).

**Two things the article requires that a single balance cannot give:**

第一号 and 第二号 both say **税率の異なるごとに区分した**. One 仮受消費税
account covering 10% and 軽減 8% satisfies neither, and no arithmetic
recovers the split — so a tax account with no `:tax-rate` yields
`:rates-not-declared` rather than a total that looks like an answer.

第三号 makes the deduction **broader than 仮払消費税**: 仕入れに係る消費税額
*and* 対価の返還等 (第三十八条, 第三十八条の二) *and* 貸倒れ (第三十九条). A
ledger's input-tax account is one of four.

So there is **no `:tax-payable` key**. `:shohizei/not-computed` lists what
stands between these figures and 第四号 — 対価の返還等, 貸倒れ, 課税売上割合
and 個別対応/一括比例, 簡易課税, 端数処理, 中間納付 — each with the article
and the reason. Emitting a number labelled 納付税額 would be the same class
of error as a balance sheet that omits an account: arithmetically clean,
wrong, and silent about it.

`difference` is called a difference. It is 仮受 minus 仮払 per rate — the
working figure a bookkeeper recognises, and not 第四号's 残額.

`filing-deadline` gives 第四十五条第一項本文's 課税期間の末日の翌日から二月
以内, and reports `:clamped?` when adding two months overflowed a month end,
because that clamp is a convention and not the article's.

Measured, all seven mutations red: aggregate without declared rates (4),
collapse the rates into one bucket (7+2), stop negating the credit-normal
output account (5), empty the not-computed list (5), aggregate with no tax
accounts (1), make the deadline one month (4), clamp silently (1).

## A retry must not double the books

**Measured 2026-08-18, before this change:** submitting the same entry twice
produced **two postings, both called `d1`**, and the trial balance for
supplies went 5000 → 10000. The posting id was `(or entry-id source-doc)`,
and that is wrong from both sides — a retry of one entry collided with
itself, and two genuinely different entries citing one receipt collided with
each other.

A carrier that retries is normal. This had to be fixed before anything
carried these requests anywhere.

Posting ids are now **content-addressed**: a stable key over the source
document and the lines in canonical order, so identical content is idempotent
and different content is distinct. Line order does not change identity.

`commit-posting!` is idempotent on that id, **on both backends** — and the
second write is a **no-op, not an overwrite**, because a posting that changed
under a stable id would be an edit to an append-only ledger. Idempotency is
per client; two clients may legitimately hold a posting with the same id.

**The posting is deduplicated; the audit trail is not.** Three submissions
produce three ledger facts and one posting. A retry is a thing that happened,
and an append-only ledger that hid it would be an audit trail of the wrong
thing.

`(or (:entry-id proposal) …)` was **dead code** — `bookkeeping.advisor/infer`
builds a fixed map with no `:entry-id`, so nothing reaching the commit node
ever carried one. Removed rather than wired up: a caller-chosen id can name
two different entries the same and would defeat the idempotency this exists
for. Dead code that looks like a feature is worse than no feature.

Measured, mutations: remove MemStore's dedup — the original bug (4), remove
**only DatomicStore's** (3), overwrite instead of no-op (1), key on the
source document again (5), ignore the amount in the id (1), make the id
order-dependent (1).

> The DatomicStore mutation **survived the first run.** The idempotency tests
> construct `mem-store` only, so the durable backend was uncovered — a
> carrier retrying against it would have doubled that client's books and not
> the other's. Three assertions moved into the contract test, where both
> backends are held to them, and it reddens now.

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

Seven routes, and nothing else:

```
POST /api/entry           submit a journal entry draft
POST /api/entries         submit many, and get one outcome each
GET  /api/trial-balance   read what the committed postings add up to
GET  /api/journal         仕訳帳 — every posting, in commit order
GET  /api/ledger/:account 総勘定元帳 — one account, with a running balance
GET  /api/search          検索機能 — 規則第五条第五項第一号ハ
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

## 検索機能 — 電子帳簿保存法施行規則 第五条第五項第一号ハ

Retrieved 2026-08-18 from the e-Gov law API,
`GET https://laws.e-gov.go.jp/api/2/law_data/410M50000040043?response_format=json`:

> ハ　当該国税関係帳簿に係る電磁的記録の記録事項の検索をすることができる機能（次に掲げる要件を満たすものに限る。）を確保しておくこと。
> （１）取引年月日、取引金額及び取引先（（２）及び（３）において「記録項目」という。）を検索の条件として設定することができること。
> （２）日付又は金額に係る記録項目については、その範囲を指定して条件を設定することができること。
> （３）二以上の任意の記録項目を組み合わせて条件を設定することができること。

### Two of the three 記録項目 did not exist

Until 2026-08-18 a journal entry here recorded `:source-doc` and `:lines` and
nothing else — no 取引年月日 and no 取引先. A search over what was stored would
have **run, returned rows, and satisfied none of （１）** while looking exactly
like a search that did. So the fields came first
(`:bookkeeping/transaction-date`, `:bookkeeping/counterparty`, carried by
`bookkeeping.posting/project` through to `bookkeeping.motochou`'s 仕訳帳 and
元帳), and `bookkeeping.kensaku` searches over all three.

They are **optional**, and that was a decision rather than a convenience:

1. ハ binds only a 保存義務者 claiming **法第八条第四項**（優良帳簿, the
   過少申告加算税 reduction). Ordinary preservation under 法第四条第一項 needs no
   search function at all, so requiring the fields would impose a 優良帳簿
   obligation on every deployment — the software deciding a tax election on
   the client's behalf.
2. Some entries genuinely have no 取引先 — 減価償却費, 決算整理仕訳, a transfer
   between the client's own accounts. A required field whose honest answer is
   *there is not one* gets filled with an invention.

The price is paid at the claim, not at the door: `conformance` counts and
**names** the entries that lack a 記録項目. A blank 取引先 is a `400`, never a
counterparty made of spaces, and a `:transaction-date` must be an ISO-8601
calendar date (`2026-02-30` is refused) so that a ハ（２） range compares
chronologically.

### 取引金額 for an entry with many lines

**The sum of the debit side, per currency.** In a balanced entry it equals the
credit side, so it is the amount of the transaction rather than of one leg —
and it is never summed across currencies. This actor shipped that bug once
(5000 JPY debit against 5000 USD credit balanced), so an entry's 取引金額 is a
map `{currency total}`, an amount condition matches when at least one
currency's total satisfies it, and the result says which matched.

```
?date=2026-01-15                       取引年月日, exact
?date-from=…&date-to=…                 ハ（２）, inclusive, either side alone
?amount=5000  ?amount-from=…&amount-to=…
?counterparty=…                        取引先, exact
any two or more                        ハ（３）, AND
```

**A conditionless search is a `400`, not the whole book.** `GET /api/journal`
already hands back the whole book and says so; returning it here would make
*I applied no filter* and *nothing matched* the same answer. An unrecognised
parameter is a `400` too — `?conterparty=x` must not become "you asked
nothing" when the caller asked something and was not heard.

### What the deployment may claim, including "cannot tell"

`bookkeeping.kensaku/conformance` answers in **six** values and only one is a
pass:

| status | meaning |
|---|---|
| `:unchecked-jurisdiction` | the client's jurisdiction is not one `kotoba.taxlaw` has catalogued, or none was declared. **Not a pass**, and checked first. |
| `:not-declared` | nobody said whether this 保存義務者 claims 法第八条第四項. **Not a pass** — the software cannot observe a tax election. |
| `:not-applicable` | declared as NOT claiming it. ハ does not bite. Also not a pass: the requirement was found not to apply, nothing was found compliant. |
| `:no-entries` | claiming it, book empty. An empty ledger has not been shown searchable, only empty. |
| `:non-conformant` | claiming it, and either the search fails a probe or entries lack 記録項目 — counted and named. |
| `:conformant` | claiming it, all three probes pass, every entry carries all three. |

`conformant?` is the convenient boolean and returns **false** for the first
five, the same conservatism `kotoba.taxlaw/supported?` applies to an
uncatalogued jurisdiction.

Both the declaration (`:yuryo-chobo-declared?`) and the jurisdiction live on
the **client record**, set by the operator — never read from the request. A
caller that could declare its own 優良帳簿 election could declare compliance
into existence, and a caller that could name its own jurisdiction could name
the one whose rule it happens to satisfy.

#### The jurisdiction check was missing, and was measured missing

The first version of this asked whether the operator was claiming 優良帳簿 and
never asked **which country's** 優良帳簿. Measured 2026-08-18 on a USD book
with no jurisdiction at all:

```clojure
:conformance #:kensaku{:status :conformant
                       :provision 電子帳簿保存法施行規則 第五条第五項第一号ハ}
```

That is this repo's own recurring defect one level up — a check that could not
apply returning what a check that applied and passed returns. `:declared?` was
guarded; `:jurisdiction` was not. Eighteen existing tests were asserting
conformance verdicts for a book with no jurisdiction, which is how the fix
announced itself.

Whether a jurisdiction has this rule is asked of `kotoba.taxlaw`
(`requires-book-search?`), not of a set of keywords kept here — a local table
would be a second place to update, and it would already be wrong: taxlaw
normalizes `:jp` and `[:jp]`, and a keyword map silently answers *unchecked*
for the path form that `worklaw` and `taxlaw` both use. The provision is
stamped only where it reaches, because citing a Japanese ministerial ordinance
on a verdict about a book kept elsewhere is the same mistake in another key.

And the verdict **runs the search** rather than asserting it exists: eight
probes over two synthetic postings check （１）（２）（３） individually. A
build whose ranges silently degraded to equality reports ハ（２）`false` and
cannot report 適合. *The function is defined* is a claim any file makes by
containing a `defn`; *it returned exactly probe-b for a 5000–20000 range* is
a measurement.

Measured 2026-08-18: **53 mutations, 53 killed, 0 survived**
(`nbb tools/check-mutations.cljs && nbb tools/mutate.cljs`). The table in
`tools/mutations.edn` covers **only** the 記録項目 and the search — not the
governor, the store, the statements or the batch route, which have suites and
no mutations. Three of the 53 were added last precisely because they looked
unmeasured, and all three survived the first run: the echoed conditions, the
lines on a result row, and the `:why` sentence. Each is now tested.

## 消費税申告 — what the books supply, and the inputs they cannot

`bookkeeping.shinkoku` asks the return's question of this ledger:
**課税売上に係る消費税額 − 課税仕入れ等に係る消費税額 = 納付税額.** It answers
the parts a book of postings can answer, and **names every input it does not
have**, with which statute was read and which was not.

There is **no `:shinkoku/tax-payable`** — `bookkeeping.shohizei`'s refusal is
extended here, not overturned — and deliberately **no `filable?`**. The
convenience predicate is `computed?`, because `:computed` means *this ledger
supplied its part*, and a predicate named for filing would be read as an
answer about filing.

### What was read, verbatim

消費税法 revision `363AC0000000108_20260401_508AC0000000012`, retrieved
2026-08-18 from
`GET https://laws.e-gov.go.jp/api/2/law_data/363AC0000000108?response_format=json`
— nine articles quoted in `provisions`: 第九条第一項・第四項, 第十九条第一項,
第二十九条, 第三十条第一項・第二項・第七項・第九項, 第三十七条第一項.

**What was NOT read is data too** (`not-read`), and nothing in the file may
branch on it: 施行令 第六十二条 (売上税額の積上げ計算), 施行令 第四十六条
(仕入税額), 第二十八条 (課税標準), 第三十八条 / 第三十八条の二 / 第三十九条,
地方税法 第七十二条の八十三 (地方消費税), 国税通則法 第百十八条第一項. Every
`:read? false` entry of `not-computed` must name a law that appears in
`not-read`, and every `:read? true` entry must name an article some file
actually quoted — the suite holds all three lists to each other, so a statute
cannot quietly acquire the authority of one that was read.

### 課税標準額に対する消費税額 is NOT the 仮受消費税 balance

Measured while writing this, and it is the trap that would have made the whole
namespace a lie. 第二十九条 sets the rate at **百分の七・八** (軽減 六・二四).
消費税法施行令 第七十条の十 — which `kotoba.taxlaw` implements as
`consumption-tax-amount` — computes 消費税額等 at 10/100 and 8/100, and
消費税額等 is 消費税 **plus 地方消費税**. Handing the invoice figure back as
第四十五条第一項第二号's 消費税額 would overstate the national tax by 10/7.8,
on every return, forever.

So this namespace **does not call `consumption-tax-amount` at all** and reports
no output tax. Two reasons, both named: the ledger holds the combined figure,
and whether the tax is computed 割戻し or 積上げ is 施行令 第六十二条, which
was not read. 積上げ / 割戻し is a real election a taxpayer makes; refusing it
is the honest answer, not a limitation to work around.

### 第三十条第七項 — the partition this namespace exists for

Held entries never become postings, so a held claim cannot reach the trial
balance. **That much is structural. The live hazard is the other one:** the
governor's tax rules fire only on a proposal claiming
`:tax-treatment :input-tax-credit`, so an entry that debits 仮払消費税 and
claims nothing **was never checked against the article** — and its tax sits in
the balance looking exactly like a verified one.

`input-tax-basis` joins each posting back to the record that produced it, by
recomputing `bookkeeping.posting/content-id` — the actor's own identity
function, not a second one that agrees until it doesn't — and reports every
posting whose basis was not checked **by id, document and amount**, in one of
five buckets: `:no-record`, `:no-tax-claim`, `:source-doc-not-registered`,
`:unchecked-jurisdiction`, `:unsupported-document`. A count tells an operator
something is wrong and not which document to go and find.

`:checked` is **not** called `deductible`, because it is not: 第三十条第二項
(課税売上割合 and the 個別対応 / 一括比例 election) still applies and is
answered nowhere in this repository.

**Measured 2026-08-18: `bookkeeping.advisor`'s mock dropped `:tax-treatment`,
so the governor's rules 5 and 6 were structurally unreachable through
`bookkeeping.actor`** — they had only ever been exercised by calling the
governor directly. It is now read straight through like every other declared
field. A rule no path can reach is not enforced. The HTTP surface still does
not carry it (`parse-entry-body` has no `:tax-treatment`), so an edge-submitted
entry lands in `:no-tax-claim` and says so.

### 課税期間 is supplied, never inferred

第十九条第一項 makes the period a fact about the **taxpayer**: an 個人事業者
files on the calendar year, a 法人 on its 事業年度, and either can shorten to
three-month or one-month periods by filing a 届出書. None of those is in a book
of postings, so defaulting to the calendar year would answer for the 法人 too.

An entry with no usable 取引年月日 is `:unplaceable` and **never `:out`** — it
might belong to this period, and calling it outside files the return without it
and reports nothing. One undated entry taints **every** period, because there
is no date to exclude it by.

### 免税事業者 / 簡易課税 — read, and still an input

第九条第一項 and 第三十七条第一項 were read, and reading them is what shows the
refusal is structural rather than lazy: both hang on **基準期間における課税
売上高** — the turnover two years back — and on a 届出書 filed with the
税務署長. Neither is in this period's books. The regime is therefore declared
by the operator and read as strictly as `kensaku` reads `declared?`: anything
that is not literally `:general`, `:exempt` or `:simplified` is
`:regime-not-declared`.

第九条第一項 carries a carve-out that inverts the naive rule and is quoted for
it: **適格請求書発行事業者を除く** — a registered issuer under ¥10,000,000 is
not 免税.

### Nine values, and exactly one is a pass

| status | meaning |
|---|---|
| `:unchecked-jurisdiction` | this file did not read that jurisdiction's return articles. `read-for` is `#{[:jp]}` — a statement about this file, not a copy of the catalog, so a third VAT jurisdiction cannot silently acquire 消費税法 第四十五条. `kotoba.taxlaw`'s stated reason for `[:us]` / `[:eu]` rides along. |
| `:regime-not-declared` | 免税 / 簡易課税 / 一般 was not stated. **Not a pass.** |
| `:regime-not-general` | declared 免税事業者 or 簡易課税 — a different return, not a lenient version of this one. |
| `:period-not-bounded` | no usable 課税期間 (第十九条第一項), with each fault named. |
| `:entries-not-placeable` | an entry has no usable 取引年月日. Named. |
| `:no-entries` | the book is empty, **or** nothing falls in the period. The counts distinguish them. |
| `:figures-not-aggregable` | `bookkeeping.shohizei` could not separate by rate; its coverage rides along. |
| `:input-tax-unverified` | input tax whose 第三十条第七項 basis was never checked. Named. |
| `:computed` | **the pass** — and it does not mean a return can be filed, the same way `statements`'s `:ok` does not mean the statements are whole. |

Every non-pass still carries the figures it reached, `:shinkoku/not-computed`,
`:shinkoku/not-read` and an evidence floor (`:shinkoku/entry-count`,
`:shinkoku/in-period-count`, `:shinkoku/examined`) — zero unverified out of
zero examined and out of forty are different answers.

Measured 2026-08-18: **23 mutations added, 23 killed**; four survived the first
blind run and each was a real gap — a non-map period blamed the dates instead of
the shape, `:unsupported-document` was never reached, every fixture had one
posting per rate so `merge` and `merge-with +` agreed, and no fixture held a
non-`:draft-entry` record. Each has a test naming the mutation that found it.
The whole table then ran end to end: **87 mutations, 86 killed, 0 survived, 1
unmeasured** — the unmeasured one is the pre-existing `deps.edn` pin swap, whose
older taxlaw sha does not compile against the current source, so the suite never
ran and the harness refuses to score it as a kill.

The nine quoted articles are checked against the retrieval itself, not against
memory: **59 fragments, every one a verbatim substring** of
`363AC0000000108_20260401_508AC0000000012`.

Escalations (always human sign-off): `:issue-invoice` (external-send),
`:close-period` (hard to reverse), low confidence (< 0.6). The advisor
only ever proposes (`:effect :propose`); every `commit-record!` is
gated behind the governor's verdict.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
