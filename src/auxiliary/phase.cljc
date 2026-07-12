(ns auxiliary.phase
  "Phase 0->3 staged rollout -- the insurance-auxiliary analog of
  `cloud-itonami-isic-6511`'s `underwriting.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- case intake allowed, every write needs
                                 human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:case/intake` (no liability risk) may
                                 auto-commit. `:recommendation/finalize`
                                 NEVER auto-commits, at any phase.

  `:recommendation/finalize` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Finalizing a real claims-
  administration recommendation or a real general-average apportionment
  is the one real-world act this actor performs (an insurer, shipping
  interest or claimant will rely on it); it is always a human
  administrator's/adjuster's call. `auxiliary.governor`'s `:actuation`
  high-stakes gate enforces the same invariant independently -- two
  layers, not one, agree on this. `:case/intake` moves no capital or
  liability (governed by its own HARD checks in `auxiliary.governor`,
  but never `high-stakes`), so it IS auto-eligible at phase 3.

  The decision core is delegated to the safety kernel
  `auxiliary.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `auxiliary.kernels.gate-test` pin the two
  representations together."
  (:require [auxiliary.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:case/intake :jurisdiction/assess :recommendation/finalize})

;; NOTE the invariant: `:recommendation/finalize` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                          :auto #{}}
   1 {:label "assisted-intake" :writes #{:case/intake}                              :auto #{}}
   2 {:label "assisted-assess" :writes #{:case/intake :jurisdiction/assess}          :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:case/intake}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. This repo's `read-ops` is empty, so 0 (read) is
  never produced here — kept for the fleet-wide wire contract. Unknown
  ops map to 4 (unknown write) — the kernel never write-enables it, so
  an unrecognized op fails closed to HOLD exactly as the old
  set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)            0
    (= op :case/intake)                1
    (= op :jurisdiction/assess)        2
    (= op :recommendation/finalize)    3
    :else                              4))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:recommendation/finalize` is never auto-eligible at any phase, so
    it always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map an Insurance Auxiliary Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
