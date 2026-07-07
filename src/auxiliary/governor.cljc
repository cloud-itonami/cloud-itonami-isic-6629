(ns auxiliary.governor
  "Insurance Auxiliary Governor -- the independent compliance layer that
  earns the Claims-LLM the right to commit. The LLM has no notion of
  which jurisdiction's licensing/methodology requirements are official,
  no way to know whether a claims-administration recommendation is
  actually supported by the required evidence, and no way to know
  whether a claimed general-average apportionment is arithmetically
  correct, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the auxiliary-services analog of
  `cloud-itonami-isic-6511`'s UnderwritingGovernor / `cloud-itonami-isic-
  6512`'s Non-Life Insurance Governor / `cloud-itonami-isic-6621`'s Loss
  Adjustment Governor / `cloud-itonami-isic-6622`'s Insurance
  Intermediation Governor.

  Three checks, in priority order. The first two are HARD violations: a
  human approver CANNOT override them (you don't get to approve your
  way past a fabricated jurisdiction citation, an unsupported claims
  recommendation, or a general-average apportionment that does not
  match the independently recomputed split). The last is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `auxiliary.phase`: for `:stake :actuation`
  (finalizing a real recommendation or apportionment) NO phase ever
  allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis              -- did the jurisdiction proposal cite an
                                   OFFICIAL source (`auxiliary.facts`),
                                   or invent one?
    2a. Evidence incomplete    -- for a `:claims-administration` case's
                                   `:recommendation/finalize`, is the
                                   jurisdiction's required evidence
                                   actually satisfied?
    2b. Apportionment mismatch -- for an `:average-adjustment` case's
                                   `:recommendation/finalize`, does the
                                   case's OWN claimed per-interest
                                   contribution match what `auxiliary.
                                   registry/apportion-general-average`
                                   INDEPENDENTLY recomputes from the
                                   SAME interests/total-loss-amount
                                   (within float tolerance)? A mismatch
                                   means either the claim was tampered
                                   with or the underlying data has
                                   drifted -- never silently accept it.
                                   The SAME 'never trust a claimed
                                   figure, independently re-derive it'
                                   discipline `cloud-itonami-isic-6499`'s
                                   `vcfund.governor`/`cloud-itonami-isic-
                                   6430`'s `trustfund.governor` apply
                                   across a repo boundary, applied here
                                   WITHIN one repo.
    3. Confidence floor / actuation gate -- LLM confidence below
                                   threshold, OR the op is
                                   `:recommendation/finalize` (a REAL
                                   act -- see README `Actuation`) ->
                                   escalate."
  (:require [auxiliary.facts :as facts]
            [auxiliary.registry :as registry]
            [auxiliary.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  :actuation = finalizing a real claims-administration recommendation
  or a real general-average apportionment (an insurer, shipping
  interest or claimant will rely on it). There is exactly one member on
  purpose: actuation is not a spectrum."
  #{:actuation})

(def ^:private contribution-tolerance
  "Floating-point tolerance for comparing a case's claimed per-interest
  contribution against this vehicle's own independent recomputation.
  Not a business tolerance for real apportionment disputes -- purely
  for double arithmetic, kept tiny."
  1e-6)

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) contribution-tolerance))

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:recommendation/finalize`) proposal
  with no spec-basis citation is a HARD violation -- never invent a
  jurisdiction's licensing/methodology requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :recommendation/finalize} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For a `:claims-administration` case's `:recommendation/finalize`,
  the jurisdiction's required evidence must actually be satisfied -- do
  not trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :recommendation/finalize)
    (let [c (store/case-file st subject)]
      (when (= :claims-administration (:case-type c))
        (let [assessment (store/assessment-of st subject)]
          (when-not (and assessment
                         (facts/required-evidence-satisfied?
                          (:jurisdiction c) (:checklist assessment)))
            [{:rule :evidence-incomplete
              :detail "法域の必要根拠資料が充足していない状態での提案確定提案"}]))))))

(defn- apportionment-mismatch-violations
  "For an `:average-adjustment` case's `:recommendation/finalize`,
  independently recompute the general-average apportionment
  (`auxiliary.registry/apportion-general-average`) from the case's OWN
  interests/total-loss-amount and compare EVERY interest's contribution
  against the case's claimed figure -- never trust the claimed
  apportionment as-is."
  [{:keys [op subject]} st]
  (when (= op :recommendation/finalize)
    (let [c (store/case-file st subject)]
      (when (= :average-adjustment (:case-type c))
        (let [recomputed (registry/apportion-general-average (:interests c) (:total-loss-amount c))
              by-party (into {} (map (juxt :party identity)) recomputed)
              mismatched (remove (fn [{:keys [party claimed-contribution]}]
                                  (when-let [r (get by-party party)]
                                    (close? claimed-contribution (:contribution r))))
                                (:interests c))]
          (when (seq mismatched)
            [{:rule :apportionment-mismatch
              :detail (str (count mismatched) "件の利害関係者の分担額が独立再計算結果と一致しない")}]))))))

(defn check
  "Censors a Claims-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (apportionment-mismatch-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
