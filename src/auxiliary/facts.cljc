(ns auxiliary.facts
  "Per-jurisdiction claims-administration licensing / average-adjustment
  methodology catalog -- the G2-style spec-basis table the Insurance
  Auxiliary Governor checks every jurisdiction/assess proposal against
  ('did the advisor cite an OFFICIAL public source for this
  jurisdiction's requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  `cloud-itonami-isic-6511`'s `underwriting.facts` / `cloud-itonami-isic-
  6512`'s `casualty.facts` / `cloud-itonami-isic-6621`'s
  `adjustment.facts` / `cloud-itonami-isic-6622`'s `intermediation.facts`
  use: a jurisdiction not in this table has NO spec-basis, full stop --
  the advisor must not fabricate one, and the governor holds if it
  tries.

  This actor covers TWO distinct activities (see README `Scope`):
  outsourced claims administration (licensing-basis, like the sibling
  actors) and marine general-average adjustment (methodology-basis --
  the York-Antwerp Rules are the real, near-universal international
  framework here, unlike this fleet's other per-jurisdiction licensing
  catalogs). `:legal-basis` names whichever applies for that
  jurisdiction's `:required-evidence` entries; `:average-methodology`
  is the SAME York-Antwerp Rules citation for every jurisdiction (it is
  adopted by contract/bill-of-lading incorporation worldwide, not a
  per-country statute), stated once per entry for completeness rather
  than a shared constant, since a real deployment's actual adjustment
  clause always governs.

  Seed values are drawn from each jurisdiction's official claims-
  administration regulator (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a real
  source, done -- never invent a jurisdiction's requirements to make
  coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  claims-administration/average-adjustment evidence set submitted in
  some form; `:legal-basis` / `:owner-authority` / `:provenance` are the
  G2 citation the governor requires before any :jurisdiction/assess
  proposal can commit; `:average-methodology` is the marine
  general-average framework citation (see ns docstring)."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "保険業法 (Insurance Business Act) -- 損害保険代理店・鑑定人制度"
          :national-spec "日本海損精算人協会 業務指針 (Japan Average Adjusters' Association practice guidelines)"
          :provenance "https://www.fsa.go.jp/"
          :average-methodology "York-Antwerp Rules 2016 (YAR 2016), as commonly incorporated by charter-party/bill-of-lading clause"
          :required-evidence ["委任契約書 (engagement agreement)"
                              "損害明細書 (loss statement)"
                              "利害関係者一覧 (interest schedule)"
                              "本人確認書類"]}
   "USA-NY" {:name "United States -- New York (exemplar; federalism note below)"
             :owner-authority "New York State Department of Financial Services (NYDFS)"
             :legal-basis "New York Insurance Law Article 21 (Adjusters)"
             :national-spec "NYDFS third-party administrator/adjuster licensing regulation"
             :provenance "https://www.dfs.ny.gov/"
             :notes "No federal insurance regulator -- TPA/adjuster licensing is regulated per-state; New York is an exemplar, not a national authority. Marine average adjustment itself is governed by maritime law/contract (York-Antwerp Rules), not state insurance law."
             :average-methodology "York-Antwerp Rules 2016 (YAR 2016), as commonly incorporated by charter-party/bill-of-lading clause"
             :required-evidence ["Engagement agreement"
                                 "Loss statement"
                                 "Interest schedule (values at risk)"
                                 "Identity verification"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Association of Average Adjusters (AAA) / Financial Conduct Authority (FCA)"
          :legal-basis "Marine Insurance Act 1906 (general average provisions) -- AAA Rules of Practice"
          :national-spec "York-Antwerp Rules 2016 (YAR 2016), as commonly incorporated by charter-party/bill-of-lading clause"
          :provenance "https://www.average-adjusters.com/"
          :average-methodology "York-Antwerp Rules 2016 (YAR 2016), as commonly incorporated by charter-party/bill-of-lading clause"
          :required-evidence ["Engagement agreement"
                              "Loss statement"
                              "Interest schedule (values at risk)"
                              "Identity verification"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Handelsgesetzbuch (HGB) §§ 588 ff. (Große Haverei / general average)"
          :national-spec "York-Antwerp Rules 2016 (YAR 2016), as commonly incorporated by charter-party/bill-of-lading clause"
          :provenance "https://www.bafin.de/"
          :average-methodology "York-Antwerp Rules 2016 (YAR 2016), as commonly incorporated by charter-party/bill-of-lading clause"
          :required-evidence ["Auftragsbestätigung (engagement agreement)"
                              "Schadenaufstellung (loss statement)"
                              "Interessentenliste (interest schedule)"
                              "Identitätsnachweis"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to finalize a
  recommendation on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6629 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `auxiliary.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
