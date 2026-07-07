(ns auxiliary.registry
  "Pure-function claims-administration-recommendation and average-
  adjustment-apportionment record construction -- an append-only
  insurance-auxiliary book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a recommendation-report number -- every
  administrator/adjuster assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `auxiliary.facts` uses.

  `apportion-general-average` is DIFFERENT in kind from every sibling
  actor's math: it is a real, well-known formula (marine general-average
  contribution = value-at-risk share of the total jointly-shared loss),
  not an invented placeholder default. It deliberately models only the
  CORE pro-rata-by-value-at-risk split -- a full York-Antwerp Rules
  calculation additionally adjusts for sacrificed-cargo contributory-
  value exemptions, salvage deductions, and other refinements this fn
  does NOT model (see its own docstring). This is the same 'reimplement
  the well-known math independently, so a downstream governor can
  cross-check a claimed figure against it' pattern `cloud-itonami-isic-
  6499`'s `vcfund.registry/capital-call-allocations` and `cloud-itonami-
  isic-6430`'s `trustfund.registry/capital-call-allocations` establish
  -- applied here WITHIN one repo (the case's own claimed apportionment
  vs. this repo's own independent recompute), not across a repo
  boundary.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any insurer's core-administration or claims-payment system. It
  builds the RECORD an administrator/adjuster would keep, not the act of
  finalizing the recommendation itself (that is `auxiliary.operation`'s
  `:recommendation/finalize`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed administrator's/average adjuster's act, not this actor's.
  See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn apportion-general-average
  "Pure pro-rata apportionment of a general-average loss across
  interests, by value-at-risk share (see ns docstring for the honest
  simplification this makes vs. a full York-Antwerp Rules calculation).

  `interests` -- coll of `{:party .. :value-at-risk ..}`.
  `total-loss-amount` -- the jointly-shared general-average loss to
  apportion.

  Returns one map per interest: `{:party :value-at-risk :contribution}`."
  [interests total-loss-amount]
  (when (empty? interests)
    (throw (ex-info "apportion-general-average: at least one interest required" {})))
  (when (neg? total-loss-amount)
    (throw (ex-info "apportion-general-average: total-loss-amount must be >= 0" {})))
  (let [total-value (reduce + (map :value-at-risk interests))]
    (when (zero? total-value)
      (throw (ex-info "apportion-general-average: total value-at-risk is zero" {})))
    (mapv (fn [{:keys [party value-at-risk]}]
            (let [share (/ (double value-at-risk) (double total-value))]
              {:party party
               :value-at-risk value-at-risk
               :contribution (* share (double total-loss-amount))}))
          interests)))

(defn register-claims-recommendation
  "Validate + construct the CLAIMS-ADMINISTRATION-RECOMMENDATION DRAFT.
  Pure function -- does not touch any real claims-payment system."
  [case-reference recommended-amount jurisdiction sequence]
  (when-not (and case-reference (not= case-reference ""))
    (throw (ex-info "claims-recommendation: case-reference required" {})))
  (when (< recommended-amount 0)
    (throw (ex-info "claims-recommendation: recommended-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "claims-recommendation: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "claims-recommendation: sequence must be >= 0" {})))
  (let [record-number (str (str/upper-case jurisdiction) "-REC-" (zero-pad sequence 6))
        record {"record_id" record-number
                "kind" "claims-recommendation-draft"
                "case_reference" case-reference
                "recommended_amount" recommended-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "recommendation_number" record-number
     "certificate" (unsigned-certificate "ClaimsRecommendationCertificate" record-number record-number)}))

(defn register-apportionment
  "Validate + construct the AVERAGE-ADJUSTMENT-APPORTIONMENT DRAFT. Pure
  function -- does not touch any real claims-payment system.
  `auxiliary.governor` independently re-verifies `contributions` against
  its OWN recompute (`apportion-general-average`) before this is ever
  allowed to commit -- never trusts the case's claimed figures as-is."
  [case-reference total-loss-amount contributions jurisdiction sequence]
  (when-not (and case-reference (not= case-reference ""))
    (throw (ex-info "apportionment: case-reference required" {})))
  (when (< total-loss-amount 0)
    (throw (ex-info "apportionment: total-loss-amount must be >= 0" {})))
  (when-not (seq contributions)
    (throw (ex-info "apportionment: at least one contribution required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "apportionment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "apportionment: sequence must be >= 0" {})))
  (let [record-number (str (str/upper-case jurisdiction) "-REC-" (zero-pad sequence 6))
        record {"record_id" record-number
                "kind" "apportionment-draft"
                "case_reference" case-reference
                "total_loss_amount" total-loss-amount
                "contributions" (mapv (fn [{:keys [party contribution]}]
                                       {"party" party "contribution" contribution})
                                     contributions)
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "recommendation_number" record-number
     "certificate" (unsigned-certificate "ApportionmentCertificate" record-number record-number)}))

(defn append
  "Append a claims-recommendation/apportionment record, returning a NEW
  list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
