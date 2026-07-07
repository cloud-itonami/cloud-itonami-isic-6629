(ns auxiliary.claimsllm
  "Claims-LLM client -- the *contained intelligence node* for the
  insurance-auxiliary actor.

  It normalizes case intake, drafts a per-jurisdiction licensing/
  methodology checklist, and drafts the recommendation-finalization
  action (a claims-administration recommendation OR a general-average
  apportionment, depending on the case's own `:case-type`). CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  recommendation/apportionment. Every output is censored downstream by
  `auxiliary.governor` before anything touches the SSoT, and
  `:recommendation/finalize` proposals NEVER auto-commit at any phase --
  see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation if it touches a real recommendation
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [auxiliary.facts :as facts]
            [auxiliary.registry :as registry]
            [auxiliary.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the case's reference, case-type, interests, total
  loss amount or jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "案件レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :case/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction licensing/methodology checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in
  `auxiliary.facts` -- the Insurance Auxiliary Governor must reject this
  (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [c (store/case-file db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction c))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "auxiliary.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-finalize
  "Draft the actual recommendation-finalization action -- issuing a
  real claims-administration recommendation or a real general-average
  apportionment. ALWAYS `:stake :actuation` -- this is a REAL-WORLD act,
  never a draft the actor may auto-run. See README `Actuation`: no
  phase ever adds this op to a phase's `:auto` set (`auxiliary.phase`);
  the governor also always escalates on `:actuation`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [c (store/case-file db subject)
        assessment (store/assessment-of db subject)]
    (case (:case-type c)
      :claims-administration
      (let [evidence-ok? (and assessment (facts/required-evidence-satisfied?
                                          (:jurisdiction c)
                                          (:checklist assessment)))]
        {:summary    (str (:case-reference c) " (" (:jurisdiction c)
                          ") のクレーム管理提案準備ができました" (when-not evidence-ok? " (根拠資料未充足)"))
         :rationale  (if assessment (str "spec-basis: " (:spec-basis assessment)) "assessment未実施")
         :cites      (if assessment [(:spec-basis assessment)] [])
         :effect     :recommendation/mark-finalized
         :value      {:case-id subject}
         :stake      :actuation
         :confidence (if evidence-ok? 0.9 0.3)})

      :average-adjustment
      (let [recomputed (registry/apportion-general-average (:interests c) (:total-loss-amount c))
            by-party (into {} (map (juxt :party identity)) recomputed)
            matches? (every? (fn [{:keys [party claimed-contribution]}]
                              (when-let [r (get by-party party)]
                                (< (Math/abs (- (double claimed-contribution) (double (:contribution r)))) 1e-6)))
                            (:interests c))]
        {:summary    (str (:case-reference c) " (" (:jurisdiction c)
                          ") の分担額確定準備ができました" (when-not matches? " (分担額不一致)"))
         :rationale  (if assessment (str "spec-basis: " (:spec-basis assessment)) "assessment未実施")
         :cites      (if assessment [(:spec-basis assessment)] [])
         :effect     :recommendation/mark-finalized
         :value      {:case-id subject}
         :stake      :actuation
         :confidence (if (and assessment matches?) 0.9 0.3)}))))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :case/intake             (normalize-intake db request)
    :jurisdiction/assess     (assess-jurisdiction db request)
    :recommendation/finalize (propose-finalize db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは損害保険その他補助業務(クレーム管理受託・海損精算)エージェントの"
       "助言者です。与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:case/upsert|:assessment/set|:recommendation/mark-finalized) "
       ":stake(:actuation か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess     {:case (store/case-file st subject)}
    :recommendation/finalize {:case (store/case-file st subject)
                              :assessment (store/assessment-of st subject)}
    {:case (store/case-file st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Insurance Auxiliary Governor
  escalates/holds -- an LLM hiccup can never auto-finalize a
  recommendation."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :claimsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
