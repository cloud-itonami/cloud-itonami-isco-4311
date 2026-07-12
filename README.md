# cloud-itonami-isco-4311

**Community Bookkeeping Service** — the ISCO-08 4311 (Accounting and
Bookkeeping Clerks) actor, an ISCO **Wave 0 (cognitive substrate)**
occupation per ADR-2607121000: pure-cognitive work, the LLM-first
wave, no robotics gate.

**Maturity: `:implemented`** — BookkeepingAdvisor ⊣
BookkeepingClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt for escalations), modeled on cloud-itonami-isco-2411's
accounting actor. 14 tests / 36 assertions green.

Two bookkeeping-specific HARD invariants (never approvable past):

1. **Source-document basis** — a journal-entry draft must cite a
   REGISTERED source document belonging to the same client. A journal
   entry without a source document is an invented transaction (the
   fleet's fabricated-spec-basis rule, bookkeeping edition).
2. **Double-entry balance** — debit total must equal credit total.
   A human approver cannot approve their way past bad arithmetic.

Escalations (always human sign-off): `:issue-invoice` (external-send),
`:close-period` (hard to reverse), low confidence (< 0.6). The advisor
only ever proposes (`:effect :propose`); every `commit-record!` is
gated behind the governor's verdict.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
