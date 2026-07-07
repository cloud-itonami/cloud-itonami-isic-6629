# cloud-itonami-isic-6629

Open Business Blueprint for **ISIC Rev.5 6629**: Other activities
auxiliary to insurance and pension funding. This repository publishes
an outsourced claims-administration and marine general-average
adjustment execution actor as an OSS business that any qualified,
licensed operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(life insurance), [`cloud-itonami-isic-6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512)
(non-life insurance), [`cloud-itonami-isic-6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621)
(independent loss adjustment) and [`cloud-itonami-isic-6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622)
(insurance intermediation). Here it is **Claims-LLM ⊣ Insurance
Auxiliary Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a case
> summary, normalizing case intake, and running the arithmetic for a
> general-average apportionment -- but it has **no notion of which
> jurisdiction's licensing/methodology requirements are official, no
> license to administer claims or adjust average, and no way to know on
> its own whether a claimed apportionment is arithmetically correct**.
> Letting it finalize a recommendation or an apportionment directly
> invites fabricated licensing citations, silently-wrong loss-sharing
> math that a shipping interest or insurer would actually rely on for a
> real settlement, and liability for whoever runs it. This project seals
> the Claims-LLM into a single node and wraps it with an independent
> **Insurance Auxiliary Governor**, a human **approval workflow**, and
> an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers TWO distinct activities bundled under this ISIC
class, sharing one governed workflow: outsourced claims-administration
case intake/recommendation, and marine general-average adjustment
(loss apportionment across cargo/vessel/freight interests by value-at-
risk share). It does **not**, by itself, hold a license to administer
claims or practice as an average adjuster in any jurisdiction, and it
does not claim to. It also does **not** implement a full York-Antwerp
Rules calculation -- only the CORE pro-rata-by-value-at-risk split (see
`auxiliary.registry/apportion-general-average`'s own docstring for the
honest simplification this makes). Whoever deploys and operates a live
instance (a licensed TPA, a firm of average adjusters) supplies the
jurisdiction-specific license, the real methodology expertise and the
real claims-payment integrations, and bears that jurisdiction's
liability -- the software supplies the governed, spec-cited, audited
execution scaffold so that operator does not have to build the
compliance layer from scratch for every new market.

### Actuation

**Finalizing a real claims-administration recommendation or a real
general-average apportionment is never autonomous, at any phase, by
construction.** Two independent layers enforce this
(`auxiliary.governor`'s `:actuation` high-stakes gate and
`auxiliary.phase`'s phase table, which never puts `:recommendation/
finalize` in any phase's `:auto` set) -- see `auxiliary.phase`'s
docstring and `test/auxiliary/phase_test.clj`'s `recommendation-
finalize-never-auto-at-any-phase`. The actor may draft, check and
recommend; a human operator (a licensed administrator/average adjuster)
is always the one who actually finalizes a recommendation or an
apportionment.

## The core contract

```
case intake + jurisdiction facts (auxiliary.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Claims-LLM   │ ─────────────▶ │ Insurance Auxiliary     │  (independent system)
   │  (sealed)    │  + citations    │ Governor: spec-basis ·  │
   └──────────────┘                 │ evidence-incomplete ·   │
                             commit ◀────┼──────────▶ hold │ apportionment-mismatch
                                 │             │           │ (independent recompute,
                           record + ledger  escalate ─▶ human   un-overridable)
                                             (ALWAYS for
                                              :recommendation/finalize)
```

**The Claims-LLM never finalizes a recommendation or an apportionment
the Insurance Auxiliary Governor would reject, and never finalizes
without a human sign-off.** Hard violations (fabricated jurisdiction
requirements; unsupported claims-administration evidence; a general-
average apportionment that does not match this vehicle's own
independent recompute) force **hold** and *cannot* be approved past; a
clean finalization proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean average-adjustment finalization lifecycle + three HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a cargo and vessel damage-
survey robot documents marine loss for the human average adjuster,
under the actor, gated by the independent **Insurance Auxiliary
Governor**. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Insurance Auxiliary Governor, claims-recommendation + apportionment draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6629`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `auxiliary.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship its insurance siblings have
toward the same lib.

## Layout

| File | Role |
|---|---|
| `src/auxiliary/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + claims-recommendation/apportionment history. No separate party/conflict concept -- this actor's distinctive check is an independent-recompute check, not a party screen |
| `src/auxiliary/registry.cljc` | Claims-recommendation + apportionment draft records, plus `apportion-general-average` (a REAL, simplified pro-rata-by-value-at-risk formula -- see docstring for what it does not model) |
| `src/auxiliary/facts.cljc` | Per-jurisdiction claims-administration licensing / average-adjustment methodology catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/auxiliary/claimsllm.cljc` | **Claims-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/finalization proposals (finalization dispatches on the case's own `:case-type`) |
| `src/auxiliary/governor.cljc` | **Insurance Auxiliary Governor** -- 3 checks: spec-basis (HARD) · evidence-incomplete (HARD, claims-administration cases) · apportionment-mismatch (HARD, average-adjustment cases, independent recompute) + 1 soft (confidence/actuation gate) |
| `src/auxiliary/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (finalization always human; case intake auto-eligible, no liability risk) |
| `src/auxiliary/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/auxiliary/sim.cljc` | demo driver |
| `test/auxiliary/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers case intake through recommendation/apportionment
finalization -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Case intake + per-jurisdiction licensing/methodology checklisting, HARD-gated on an official spec-basis citation (`:case/intake`/`:jurisdiction/assess`) | Full York-Antwerp Rules calculation (sacrificed-cargo contributory-value exemptions, salvage deductions, and other refinements -- see `apportion-general-average`'s docstring) |
| Claims-administration recommendation finalization, independently checked against the jurisdiction's own required-evidence checklist (`:recommendation/finalize`, `:case-type :claims-administration`) | Real transfer-agent/banking-payment integration, tax/regulatory reporting |
| General-average apportionment finalization, independently re-verified against this vehicle's OWN pro-rata-by-value-at-risk recompute (`:recommendation/finalize`, `:case-type :average-adjustment`) | Multi-case/multi-vessel consolidated adjustments |
| Immutable audit ledger for every intake/assessment/finalization decision | |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's flagship op already
establishes.

## Jurisdiction coverage (honest)

`auxiliary.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `auxiliary.facts/catalog` --
currently 4 seeded (JPN, USA-NY, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `auxiliary.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Claims-LLM` + `Insurance Auxiliary Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the sibling
`cloud-itonami-isic-6511`/`6512`/`6621`/`6622`'s architecture. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
