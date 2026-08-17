# cloud-itonami-isco-4311

**Community Bookkeeping Service** — the ISCO-08 4311 (Accounting and
Bookkeeping Clerks) actor, an ISCO **Wave 0 (cognitive substrate)**
occupation per ADR-2607121000: pure-cognitive work, the LLM-first
wave, no robotics gate.

**Maturity: `:implemented`** — BookkeepingAdvisor ⊣
BookkeepingClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt for escalations), modeled on cloud-itonami-isco-2411's
accounting actor. 30 tests / 175 assertions green.

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

## The jurisdiction catalog

`src/bookkeeping/jurisdictions.cljc` holds the primary sources — e-Gov
法令検索 for 電子帳簿保存法 / 消費税法 / 法人税法 / 所得税法 / 会社法 /
会社計算規則 / 商法, and 国税庁 for インボイス制度, 仕入税額控除,
帳簿書類等の保存期間, e-Tax. It is **data the governor reads**, not a
reading list: invariants 3 and 4 above resolve against it.

Two things are kept apart on purpose, because conflating them is how a
citation list becomes decoration:

- **reachability** — all 15 URLs answered HTTP 200 on 2026-08-17.
  Re-check any time: `nbb tools/verify_citations.cljs` (exit `0` clean,
  `1` a citation went bad, `2` the run could not answer — an empty scan
  is never reported as clean).
- **content** — verified for exactly one claim, the registration-number
  format, read off the NTA's own publication site. Every other entry
  cites the instrument without quoting article text and is marked
  `:rule/review :reachable-not-read`. **This actor renders no tax or
  accounting opinion**, and neither does the catalog.

Two candidate sources were **dropped rather than cited**: `asb.or.jp`
(connection timed out) and 中小企業の会計に関する基本要領 (403 to a plain
client; 200 only with a spoofed browser User-Agent). Both are recorded
in `:catalog/rejected` with the reason. An unfetchable citation is not
a citation.

## The shared governor layer

The four rules that are not about bookkeeping at all — `:no-client`,
`:no-actuation`, `:unknown-source-doc`, `:source-doc-wrong-client` — and
the verdict assembly now come from
[`kotoba-lang/governor`](https://github.com/kotoba-lang/governor) rather
than being hand-copied. That library surveyed 376 governors in this fleet
and found one that had silently drifted into reporting a HARD violation as
escalatable, inviting an approver to try to wave through something no
approval can pass.

`test/bookkeeping/conformance_test.clj` pins every disposition this actor
can reach against `gov/conformance-failures`. **Measured: re-injecting that
exact drift leaves all 26 pre-existing tests green and reddens only the
conformance suite** — which is why the drift survived elsewhere, and why
this suite is the one that had to exist.

Escalations (always human sign-off): `:issue-invoice` (external-send),
`:close-period` (hard to reverse), low confidence (< 0.6). The advisor
only ever proposes (`:effect :propose`); every `commit-record!` is
gated behind the governor's verdict.

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
