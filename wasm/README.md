# wasm/ — kotoba-wasm deployment of the apportionment-mismatch recompute

`apportionment_mismatch.kotoba` is a port of `auxiliary.governor`'s
`apportionment-mismatch-violations` check -- the independent per-interest
recompute that cross-checks a general-average case's OWN claimed
per-interest `:claimed-contribution` against `auxiliary.registry/
apportion-general-average`'s pro-rata-by-value-at-risk recompute (see
`src/auxiliary/governor.cljc`'s ns docstring, check 2b) -- into the
minimal `.kotoba` language subset, compiled to a real WASM module via
`kotoba wasm emit`, and hosted via `kototama.tender`
(`test/wasm/apportionment_mismatch_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba` and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` established (ADR-
2607062330 addendum 5) -- `fee_accrual.kotoba` is the closest analog: a
formula recompute over integer inputs, no host imports, with a `close?`
float-epsilon tolerance in the original Clojure to translate.

## Why the source differs from `auxiliary.governor`/`auxiliary.registry`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`pos?`/`neg?`/`and`/`or`/`when`, no maps, no collections/loops/recursion,
same finding `cloud-itonami-isic-6492`/`-6511`/`-6630` document). The
port therefore:

- **Ports ONE interest's comparison, not the whole collection fold.**
  `apportionment-mismatch-violations` independently recomputes every
  interest in a case (`(map ...)`, `(remove ...)`, `(when (seq
  mismatched) ...)`), but the wasm-compilable subset has no maps,
  collections or loops. The port is the single per-interest predicate a
  host would call once per interest (writing that interest's
  `value-at-risk`/`claimed-contribution` plus the case-wide
  `total-value-at-risk`/`total-loss-amount` into memory before each
  call), and the SAME "any one interest fails -> whole case fails" fold
  `apportionment-mismatch-violations` performs in Clojure is a host-side
  loop instead of a guest-side one -- there is nothing to invent here,
  just nowhere in the language subset to express the fold itself.
- Uses plain positional args instead of `{:keys [...]}` map destructuring
  and drops `auxiliary.registry/apportion-general-average`'s two `throw`/
  `ex-info` precondition guards (`interests` non-empty,
  `total-loss-amount >= 0`) -- a WASM export can't throw a JVM exception,
  and both guards are a governor's job upstream of ever calling this
  guest, not this predicate's. The one guard the port DOES keep --
  `total-value-at-risk = 0` -- is kept for a WASM-specific reason, not a
  business-logic one: see "Divide-by-zero guard" below.
- Represents the pro-rata share computation as a single
  multiply-then-`quot` (`(quot (* value-at-risk total-loss-amount)
  total-value-at-risk)`) instead of the original's two-step double
  division (`share = value-at-risk / total-value`, then `contribution =
  share * total-loss-amount`) -- the algebraically equivalent
  cross-multiplied form, computed with exact integer arithmetic and no
  floats, the same "no floats in the wasm-compilable subset" constraint
  `affordability.kotoba`/`fee_accrual.kotoba` work around.

## The tolerance: kept, not dropped -- and why this differs from `fee_accrual.kotoba`

`fee_accrual.kotoba`'s README concluded that its wasm port should compare
with an exact `=`, not a ported tolerance: `fundmgmt.governor`'s
`close?` there absorbs pure IEEE-754 double rounding noise from a formula
whose only division is the FIXED conversion factor `/ 1_000_000` at the
very end (`fee-basis * rate-bps * years-x100 / 1_000_000`) -- a scaling
factor, not a data-dependent split, so the integer port's exact-integer
arithmetic has no rounding noise of that kind to absorb.

`apportionment-mismatch-violations`'s `close?` (`auxiliary.governor`,
`contribution-tolerance = 1e-6`, docstring: *"Not a business tolerance
for real apportionment disputes -- purely for double arithmetic, kept
tiny"*) is nominally the same kind of float-epsilon absorber in the
ORIGINAL double-precision system. But the wasm port's division is NOT a
fixed scaling factor -- it divides by `total-value-at-risk`, a
data-dependent, per-case, per-interest-set denominator that in general
does **not** divide the numerator evenly (a 3-way pro-rata split of a
loss not divisible by 3 is the ordinary case, not an edge case). A single
integer `quot` here **floors** the true rational share, discarding a
fractional remainder that can be anywhere in `[0, 1)` of a currency unit
-- a REAL, unavoidable per-call truncation this specific formula shape
introduces, structurally different from `fee_accrual.kotoba`'s
scaling-factor division (which, for well-formed integer inputs, is exact
whenever the three-way product happens to be a multiple of 1,000,000, and
otherwise floors by the same amount, but was never advertised as a
"business" split in the first place).

So the port keeps a **1-unit integer tolerance** (`(<= (abs-diff
recomputed claimed-contribution) 1)`) as the honest translation of
`close?`'s role: it absorbs exactly the largest truncation error a single
floor-division can introduce (mathematically, `floor(a/b)` is always
strictly less than `a/b` by less than 1, so any independently-computed
claim that rounds the same true fraction a different way -- e.g.
round-half-up instead of floor -- lands at most 1 unit away), never a
real apportionment dispute allowance -- same discipline the original
docstring commits to, just re-derived for the specific kind of
imprecision THIS formula shape (a per-case pro-rata division, not a fixed
scaling factor) actually introduces. `test/wasm/
apportionment_mismatch_test.clj`'s `apportionment-wasm-approves-within-
tolerance-truncation` and `apportionment-wasm-boundary-at-tolerance-edge`
tests exercise exactly this boundary (a true share of `2/3 * 100 =
66.66..` floors to `66`; a claim of `67` is 1 unit off and still
approves; `68` would not).

**Known scope limit (i32 range):** like `fee_accrual.kotoba`, the guest
computes `value-at-risk * total-loss-amount` in a single 32-bit WASM
`i32.mul` *before* dividing, so that intermediate product must stay under
the signed i32 ceiling (~2.147e9) or it silently wraps instead of
trapping -- confirmed the hard way while writing this port's own tests:
an early draft reused this repo's demo case-1/case-3 fixture magnitudes
(`value-at-risk` in the low millions) verbatim and two tests failed
because `2000000 * 600000 = 1.2e12` wrapped silently, producing a
plausible-looking but wrong recomputed contribution. The test suite's
values were rescaled down (by 1000x, preserving the same ratios/shapes as
the real fixtures) to stay safely inside i32 headroom. This is a
PoC-scale limitation, not a design claim that the formula holds for
realistic large-cargo general-average magnitudes -- promoting to `i64`
arithmetic (`i64*`/`i64-` exist in the subset, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`) would raise the ceiling, a
follow-up, not attempted in this pass.

**Divide-by-zero guard:** WASM's `i32.div_s` TRAPS (aborts the whole
instance) on divide-by-zero, unlike a JVM `ex-info` a caller can catch --
`apportion-general-average` already throws when `total-value-at-risk` is
zero, long before this vehicle would ever call the wasm guest, so the
guest's own `(if (zero? total-value-at-risk) 0 ...)` guard is
defense-in-depth, not the primary guard (same posture
`affordability.kotoba`'s `(if (> annual-income 0) ... 0)` guard
documents). `apportionment-wasm-rejects-zero-total-value-at-risk` proves
the guest fails CLOSED (returns 0 / mismatch) rather than trapping.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba`, `cloud-itonami-isic-
6511`'s `underwriting_decision.kotoba` and `cloud-itonami-isic-6630`'s
`fee_accrual.kotoba` use. A host writes four little-endian i32 values
before calling `main()`:

| offset | field                    | unit                                              |
|--------|--------------------------|----------------------------------------------------|
| 0      | `value-at-risk`          | THIS ONE interest's value at risk, currency unit    |
| 4      | `total-value-at-risk`    | SUM of value-at-risk across every interest in the case -- the pro-rata denominator |
| 8      | `total-loss-amount`      | the case's jointly-shared general-average loss      |
| 12     | `claimed-contribution`   | THIS interest's OWN claimed contribution, rounded to the nearest currency unit |

`main()` returns `1` (this interest's claimed contribution matches the
independent recompute, within the 1-unit integer-truncation tolerance) or
`0` (mismatch -- `auxiliary.governor`'s `:apportionment-mismatch` HARD
violation for this interest). A host iterating a case's interests treats
the WHOLE case as mismatched if ANY one interest's call returns `0`, the
wasm-guest-per-call mirror of `apportionment-mismatch-violations`'s
`(when (seq mismatched) ...)`. All four offsets are well below
`heap-base` (2048), so they never collide with anything the compiler
itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6629/wasm/apportionment_mismatch.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6629/wasm/apportionment_mismatch.wasm --json
```

Fleet deployment: not attempted in this pass -- see
`cloud-itonami-isic-6492`/`cloud-itonami-isic-6511` for the established
pattern.
