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
  but never `high-stakes`), so it IS auto-eligible at phase 3.")

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
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Insurance Auxiliary Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
