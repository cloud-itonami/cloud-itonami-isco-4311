# cloud-itonami-isco-4311

**Community Bookkeeping Service** — the ISCO-08 4311 (Accounting and
Bookkeeping Clerks) actor, an ISCO **Wave 0 (cognitive substrate)**
occupation per ADR-2607121000: pure-cognitive work, the LLM-first
wave, no robotics gate.

**Maturity: `:implemented`** — BookkeepingAdvisor ⊣
BookkeepingClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt for escalations), modeled on cloud-itonami-isco-2411's
accounting actor. 42 tests / 138 assertions green.

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

Until `bookkeeping.posting` existed, this actor could refuse a bad journal
entry and approve a good one, and the good one went nowhere. `:lines` was a
private shape read by one function in the governor and by nothing else in
the fleet — an approved entry was a decision with no destination.

It now projects onto [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking),
the double-entry contract this workspace already had, and which
[`kotoba-lang/kakeibo`](https://github.com/kotoba-lang/kakeibo) was already
using for personal statement rows:

```text
kakeibo     statement rows  ─┐
                             ├─▶  kotoba.banking   double-entry postings
bookkeeping journal entries ─┘
```

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

Escalations (always human sign-off): `:issue-invoice` (external-send),
`:close-period` (hard to reverse), low confidence (< 0.6). The advisor
only ever proposes (`:effect :propose`); every `commit-record!` is
gated behind the governor's verdict.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
