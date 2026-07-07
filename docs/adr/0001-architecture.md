# ADR-0001: cloud-itonami-isic-6629 -- Claims-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622` ADR-0001s (the
  pattern this ADR ports; `6621`'s and `6622`'s ADRs establish the
  "write the lesson down, don't just fix it" discipline this build
  benefits from without needing to repeat), ADR-2607032000 (`cloud-
  itonami` insurance (ISIC 65/66) + real-estate (ISIC 68) coverage push
  -- the blueprint scaffold this ADR deepens), langgraph-clj ADR-0001
  (Pregel superstep + interrupt + Datomic checkpoint)
- Context: `cloud-itonami-isic-6629` published a business/operator-model
  blueprint (ADR-2607032000's insurance coverage push) but stopped at
  `:blueprint` maturity -- no governed actor implementation. This ADR
  deepens it to `:implemented`, the fifth insurance-adjacent actor in
  the fleet, continuing the SAME "pick a new ISIC blueprint vertical"
  direction that produced `6512`/`6621`/`6622`.

## Problem

This ISIC class bundles TWO genuinely different activities under one
governed workflow, and one of them needs a kind of judgment no sibling
actor has faced:

1. **Jurisdiction licensing/methodology correctness** -- is the required
   evidence/methodology based on an official regulator or (for average
   adjustment) the York-Antwerp Rules, the near-universal international
   framework for this ONE specific activity?
2. **Claims-administration evidence sufficiency** -- for an outsourced
   claims-administration case, is the required evidence on file? (Same
   shape as `6621`'s evidence-incomplete check.)
3. **Apportionment arithmetic correctness** -- for a general-average
   case, is a CLAIMED per-interest apportionment actually the correct
   pro-rata split of the shared loss by value-at-risk? This is not a
   party-screening or best-interest-duty question (as in `6621`/`6622`)
   -- it is a pure MATH-verification question, structurally closer to
   `cloud-itonami-isic-6499`'s/`6430`'s cross-repo "independently
   recompute a claimed allocation, never trust it" pattern than to any
   check within this actor family so far.
4. **Real actuation** -- finalizing a real recommendation or a real
   apportionment: an irreversible act an insurer, shipping interest or
   claimant will rely on.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run claims administration and average
adjustment with an LLM" but "seal the LLM inside a trust boundary and
layer licensing/methodology-authenticity, evidence-sufficiency,
apportionment-correctness, audit and human-approval on top of it, while
structurally fixing real actuation as human-only."

## Decision

### 1. Claims-LLM is sealed into the bottom node; it never finalizes directly

`auxiliary.claimsllm` returns exactly three kinds of proposal: intake
normalization, jurisdiction licensing/methodology checklist, and
recommendation-finalization (which internally dispatches on the case's
own `:case-type` for its summary/rationale, but always the same
`:effect`/`:stake`). No proposal writes the SSoT or issues a real
recommendation/apportionment directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 auxiliary-services operation

`auxiliary.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. Insurance Auxiliary Governor is a separate system from Claims-LLM, and its checks are CASE-TYPE-CONDITIONAL, not op-exclusive

`auxiliary.governor` has three checks (spec-basis, evidence-incomplete,
apportionment-mismatch) but only ONE op (`:recommendation/finalize`)
triggers the latter two -- WHICH one fires depends on the case's own
`:case-type`, not on a separate op per activity. This is a genuinely
different shape from every sibling actor (which dispatch HARD checks by
`op`, one op per real act) -- here ONE op serves two activities, and
the case's own data decides which check applies. Chosen because both
activities share the identical actuation risk profile (an irreversible
finalization a third party will rely on) and the identical rollout/
phase-gating needs; splitting them into two ops would duplicate the
StateGraph wiring for no governance benefit.

### 4. `apportionment-mismatch-violations` reuses the CROSS-REPO independent-recompute pattern, WITHIN one repo

`auxiliary.registry/apportion-general-average` independently
recomputes the pro-rata-by-value-at-risk split from a case's OWN
interests/total-loss-amount, and the governor compares EVERY interest's
contribution against the case's claimed figure -- structurally the SAME
"never trust a claimed number, independently re-derive it" discipline
`cloud-itonami-isic-6499`'s `vcfund.governor`/`cloud-itonami-isic-6430`'s
`trustfund.governor` apply ACROSS a repo boundary, applied here WITHIN
one repo (a case's own claimed apportionment vs. this repo's own
recompute of the SAME well-known formula). No sibling actor in the
insurance-adjacent family (`6511`/`6512`/`6621`/`6622`) has an analog to
this -- their checks are party-screening (conflict-of-interest/
sanctions) or evidence-completeness, never arithmetic verification.

### 5. This actor has NO party/conflict-of-interest check, unlike three of its four siblings

`6621`'s and `6622`'s conflict-of-interest checks (and `6511`'s/`6512`'s
sanctions checks) all screen a PARTY. This actor's distinctive risk is
arithmetic, not identity -- there is no analogous party to screen for
THIS repo's core failure mode. `auxiliary.store` deliberately has no
`party`/`conflict-of` protocol methods at all (see its own docstring) --
adding an unused KYC-shaped collection just to match the sibling
pattern would be exactly the kind of premature abstraction this
workspace's engineering discipline rejects.

### 6. Real actuation is structurally always human-only (enforced by two independent layers)

`auxiliary.governor`'s `high-stakes` set has exactly ONE member
(`:actuation`, matching `6511`'s/`6621`'s single-actuation shape, not
`6512`'s/`6622`'s dual-actuation one -- this ISIC class's two
activities share ONE actuation op, per Decision 3), and `auxiliary.
phase`'s phase table never puts `:recommendation/finalize` in any
phase's `:auto` set.

### 7. No fabricated international recommendation-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a claims-recommendation or
apportionment reference number. `auxiliary.registry` therefore does not
invent one; it validates required fields and assigns a jurisdiction-
scoped sequence number only.

### 8. Relationship to `kotoba-lang/insurance`

Same self-contained-sibling relationship every prior insurance actor in
this fleet has to the shared capability lib -- no code dependency.

## Consequences

- (+) Insurance-auxiliary services get the same governed, auditable-
  actor treatment as the four other insurance-adjacent actors, without
  centralizing liability in one vendor -- any licensed TPA/average-
  adjustment firm can fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/auxiliary/phase_test.clj`'s
  `recommendation-finalize-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/auxiliary/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern every sibling actor uses.
- (+) The apportionment-mismatch check is a genuine, DIFFERENT-in-kind
  contribution to this actor family -- an arithmetic-verification check
  reusing a pattern established across repos (`6499`/`6430`), not a
  party-screening pattern reused across repos (`6511`/`6512`/`6621`/
  `6622`) -- proven by a dedicated demo scenario using intentionally
  mismatched claimed contributions and a passing test suite on the
  FIRST run (no equivalent bug to `6512`'s or `6622`'s own ADRs, since
  this check is new territory rather than a ported pattern with a known
  failure mode already lurking in it).
- (-) This R0 seeds only 4 jurisdictions (JPN, USA-NY, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `auxiliary.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `apportion-general-average` models only the CORE pro-rata-by-
  value-at-risk split, not a full York-Antwerp Rules calculation
  (sacrificed-cargo contributory-value exemptions, salvage deductions
  and other refinements are out of scope -- see that fn's own
  docstring); real transfer-agent/banking-payment integration, tax/
  regulatory reporting, and multi-case/multi-vessel consolidated
  adjustments are all out of scope for this OSS actor -- each
  operator's responsibility (see README's coverage table).
- 30 tests / 125 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6629` at `:blueprint` only | ❌ | Leaves this insurance-auxiliary class without an `:implemented` reference actor, unlike its four siblings |
| Split into two ops (`:recommendation/finalize` and `:apportionment/finalize`) matching one-op-per-real-act everywhere else | ❌ | Both activities share the identical actuation risk profile and phase-gating needs; a case-type-conditional check inside ONE op avoids duplicating the StateGraph wiring for no governance benefit -- see Decision 3 |
| Add a conflict-of-interest check for consistency with `6621`/`6622` | ❌ | This actor's core failure mode is arithmetic (a wrong apportionment), not an undisclosed relationship; forcing an unused party/conflict concept in just to match the sibling shape would be premature abstraction, not honest domain modeling |
| Model full York-Antwerp Rules apportionment for conformance-test rigor | ❌ | Genuinely more complex real-world maritime law (contributory-value exemptions, salvage deductions) that this R0 does not claim to model correctly -- honestly scoped to the core pro-rata formula instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Require `kotoba.insurance` (the capability lib) directly from `auxiliary.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
