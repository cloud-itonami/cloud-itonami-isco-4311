# cloud-itonami-isco-4311

**Community Bookkeeping Service** — the ISCO-08 4311 (Accounting and
Bookkeeping Clerks) actor, an ISCO **Wave 0 (cognitive substrate)**
occupation per ADR-2607121000: pure-cognitive work, the LLM-first
wave, no robotics gate.

**Maturity: `:implemented`** — BookkeepingAdvisor ⊣
BookkeepingClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt for escalations), modeled on cloud-itonami-isco-2411's
accounting actor. 25 tests / 96 assertions green.

Four bookkeeping-specific HARD invariants (never approvable past):

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

Invariants 3 and 4 fire **only** on a proposal that claims the credit;
an entry with no tax claim is unaffected. The actor does not invent a
tax position in order to have one to check.

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

Invariants 3 and 4 now read `taxlaw/credit-support`, which answers in three
values — `:none` (nobody catalogued this jurisdiction), refused, supported —
and this actor holds on the first two for different reasons and with
different details.

Escalations (always human sign-off): `:issue-invoice` (external-send),
`:close-period` (hard to reverse), low confidence (< 0.6). The advisor
only ever proposes (`:effect :propose`); every `commit-record!` is
gated behind the governor's verdict.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
