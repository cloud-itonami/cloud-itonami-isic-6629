(ns auxiliary.render-html
  "BUILD-TIME RENDERER -- runs this repo's OperationActor for real and
  writes `docs/samples/operator-console.html` from the ACTUAL run
  output.

  Nothing on the emitted page is authored by hand: every case id,
  jurisdiction, confidence, verdict flag, violation rule, ledger fact,
  apportionment figure and record number below is read back out of a
  live `auxiliary.operation/build` graph run (`langgraph.graph/run*`)
  over `auxiliary.store/seed-db`. If a value cannot be derived from a
  run it is not printed.

  Run it:

      clojure -M:dev:render-html                 ; -> docs/samples/operator-console.html
      clojure -M:dev:render-html <out.html>      ; -> anywhere (determinism checks)

  BUILD-TIME INVARIANT: `-main` throws and writes NOTHING if the run
  produced zero HARD governor holds. A page that shows only happy
  paths would be evidence of nothing -- the point of a governed actor
  is that it can REFUSE, so the build fails if no refusal was
  observed. Two further invariants are enforced the same way (see
  `assert-invariants!`): the fact-type classification must agree with
  the governor verdict on every scenario, and the phase gate must be
  observed at least once so 'governor refusal' and 'rollout gate' are
  demonstrably different things on this page rather than assumed to be.

  Classification note (this is the easy thing to get wrong): a hold is
  classified on the audit fact's TYPE first, never on `:violations`
  alone.

    :governor-hold    with NO :phase-reason  -> HARD governor refusal
    :governor-hold    with    :phase-reason  -> rollout phase gate
    :approval-rejected                       -> HUMAN approver refusal

  The third kind carries `:violations [{:rule :approver-rejected}]`
  (see `auxiliary.operation`'s :request-approval node), so anything
  that counted violation-bearing facts would silently count a human's
  refusal as a governor refusal."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [auxiliary.facts :as facts]
            [auxiliary.kernels.gate :as kernel]
            [auxiliary.operation :as op]
            [auxiliary.phase :as phase]
            [auxiliary.registry :as registry]
            [auxiliary.store :as store]))

(def ^:private default-out "docs/samples/operator-console.html")

;; ============================ scenarios ============================
;; The operator-console script. Each entry drives ONE actor run; the
;; page reports whatever that run actually did, including when that
;; differs from the intent recorded in :why.

(def ^:private actor-id
  "The EXECUTING actor's id (goes into `:context`, surfaces as `:actor`
  on every ledger fact). Deliberately distinct from every approver id
  below so the page can PROVE that a ledger fact's `:actor` is not the
  approver."
  "op-1")

(defn- ctx [phase] {:actor-id actor-id :actor-role :adjuster :phase phase})

(def ^:private scenarios
  [{:id "S1" :thread "s1" :phase 3
    :request {:op :case/intake :subject "case-1"
              :patch {:id "case-1" :status :ready}}
    :why "海損精算案件 GA-2026-001 の取込。phase 3 の唯一の auto セル。"}

   {:id "S2" :thread "s2" :phase 3
    :request {:op :jurisdiction/assess :subject "case-1"}
    :approval {:status :approved :by "adj-hanako"}
    :why "GBR の公式 spec-basis に基づく必要書類チェックリスト。governor は clean、phase gate が承認を要求。"}

   {:id "S3" :thread "s3" :phase 3
    :request {:op :recommendation/finalize :subject "case-1"}
    :approval {:status :approved :by "adj-hanako"}
    :why "分担額の確定 (:actuation)。どの phase でも auto にならない。"}

   {:id "S4" :thread "s4" :phase 3
    :request {:op :case/intake :subject "case-2"
              :patch {:id "case-2" :status :ready}}
    :why "クレーム管理受託案件 TPA-2026-001 の取込。法域 ATL は未登録。"}

   {:id "S5" :thread "s5" :phase 3
    :request {:op :jurisdiction/assess :subject "case-2"}
    :why "未登録法域 ATL の要件を提案しようとする。governor が拒否するはず。"}

   {:id "S6" :thread "s6" :phase 3
    :request {:op :recommendation/finalize :subject "case-2"}
    :why "根拠資料が 1 件も無い状態でクレーム管理提案を確定しようとする。"}

   {:id "S7" :thread "s7" :phase 3
    :request {:op :jurisdiction/assess :subject "case-3"}
    :approval {:status :approved :by "adj-hanako"}
    :why "GA-2026-002 の法域評価。S8 の前提 (assessment を実際に置く)。"}

   {:id "S8" :thread "s8" :phase 3
    :request {:op :recommendation/finalize :subject "case-3"}
    :why "案件が主張する分担額を独立再計算と突き合わせる。ズレていれば拒否するはず。"}

   {:id "S9" :thread "s9" :phase 1
    :request {:op :jurisdiction/assess :subject "case-1"}
    :why "phase 1 では jurisdiction/assess は書込不可。governor は clean なのに HOLD になる — これは governor の拒否ではない。"}

   {:id "S10" :thread "s10" :phase 2
    :request {:op :case/intake :subject "case-3"
              :patch {:id "case-3" :status :ready}}
    :approval {:status :approved :by "tpa-tanaka"}
    :why "phase 2 の case/intake は書込可・auto 不可 → 承認経由で commit。承認者が記録に残るかを見る。"}

   {:id "S11" :thread "s11" :phase 3
    :request {:op :jurisdiction/assess :subject "case-1"}
    :approval {:status :rejected :by "tpa-tanaka"}
    :why "人間の承認者が拒否する。これは governor の拒否ではない (ただし :rule :approver-rejected を持つ)。"}])

;; ============================ execution ============================

(defn- run-scenario!
  "One scenario = one actor run (plus a resume when the run interrupts
  for approval and the scenario supplies one). Returns the scenario map
  enriched with what the run ACTUALLY produced."
  [actor {:keys [thread phase request approval] :as sc}]
  (let [r1    (g/run* actor {:request request :context (ctx phase)} {:thread-id thread})
        r2    (when (and approval (= :interrupted (:status r1)))
                (g/run* actor {:approval approval} {:thread-id thread :resume? true}))
        final (or r2 r1)]
    (assoc sc
           :interrupted?  (= :interrupted (:status r1))
           :resumed?      (some? r2)
           :proposal      (get-in r1 [:state :proposal])
           :verdict       (get-in r1 [:state :verdict])
           :disposition   (get-in final [:state :disposition])
           :audit         (get-in final [:state :audit])
           :status        (:status final))))

;; --------------------------- classification ---------------------------

(defn- fact-kind
  "Classify ONE audit fact by its type first. `:violations` is never the
  discriminator: an `:approval-rejected` fact carries one."
  [f]
  (case (:t f)
    :governor-hold      (if (:phase-reason f) :phase-gate-hold :governor-refusal)
    :approval-rejected  :approver-refusal
    :approval-requested :escalation
    :approval-granted   :approval-granted
    :committed          :commit
    :claimsllm-proposal :proposal
    :other))

(defn- kinds-of [sc] (map fact-kind (:audit sc)))

(defn- has-kind? [sc k] (boolean (some #{k} (kinds-of sc))))

(defn- governor-refusals
  "Every HARD governor refusal observed across all runs, as
  {:scenario .. :fact ..} pairs."
  [runs]
  (for [sc runs
        f  (:audit sc)
        :when (= :governor-refusal (fact-kind f))]
    {:scenario sc :fact f}))

(defn- outcome
  "The single human-facing outcome label for a run."
  [sc]
  (cond
    (has-kind? sc :governor-refusal) "GOVERNOR HOLD (hard)"
    (has-kind? sc :approver-refusal) "APPROVER REJECTED"
    (has-kind? sc :phase-gate-hold)  "PHASE GATE HOLD"
    (has-kind? sc :commit)           (if (has-kind? sc :approval-granted)
                                       "COMMIT (人間承認あり)"
                                       "COMMIT (auto)")
    (= :interrupted (:status sc))    "ESCALATED (承認待ちで停止)"
    :else                            "—"))

(defn- outcome-class [sc]
  (cond
    (has-kind? sc :governor-refusal) "hold"
    (has-kind? sc :approver-refusal) "reject"
    (has-kind? sc :phase-gate-hold)  "gate"
    (has-kind? sc :commit)           "commit"
    :else                            "wait"))

;; --------------------- approver attribution (derived) ---------------------

(def ^:private approver-key-names
  "Keys that WOULD carry the human approver's identity if a write path
  kept it. `:actor` is deliberately NOT in this set -- in this actor
  `:actor` is the executing actor id from `:context`, not the approver."
  #{:approved-by :approved_by :approver :approver-id :signed-by
    "approved_by" "approved-by" "approver" "signed_by"})

(defn- approver-keys-present
  "Recursively scan any EDN value for approver-shaped keys. Derived at
  render time so this page stops claiming a defect the moment someone
  fixes the write path."
  [x]
  (cond
    (map? x)        (into (set (filter approver-key-names (keys x)))
                          (mapcat approver-keys-present (vals x)))
    (sequential? x) (into #{} (mapcat approver-keys-present x))
    :else           #{}))

(defn- ssot-surface-for
  "The SSoT surface a committed effect actually wrote to, read back
  through the Store protocol."
  [db effect subject]
  (case effect
    :assessment/set                {:label "assessments" :value (store/assessment-of db subject)}
    :case/upsert                   {:label "cases"       :value (store/case-file db subject)}
    :recommendation/mark-finalized {:label "cases + recommendations"
                                    :value {:case (store/case-file db subject)
                                            :recommendations (store/recommendation-history db)}}
    {:label (str effect) :value nil}))

(defn- attribution-rows
  "For every run where a HUMAN actually approved and the write
  committed: does the resulting SSoT surface retain an approver-shaped
  key? Measured, never assumed."
  [db runs]
  (for [sc runs
        :when (and (has-kind? sc :approval-granted) (has-kind? sc :commit))
        :let [effect  (get-in sc [:proposal :effect])
              subject (get-in sc [:request :subject])
              {:keys [label value]} (ssot-surface-for db effect subject)
              found (approver-keys-present value)]]
    {:scenario-id (:id sc)
     :effect effect
     :subject subject
     :surface label
     :approver (get-in sc [:approval :by])
     :keys-found (vec (sort-by str found))
     :retained? (boolean (seq found))}))

;; ---------------------------- invariants ----------------------------

(defn- assert-invariants!
  "Build-time gate. Throws (so nothing is written) rather than emitting
  a page that would quietly assert less than it looks like it does."
  [runs]
  (let [holds (governor-refusals runs)]
    (when (zero? (count holds))
      (throw (ex-info (str "REFUSING to write the operator console: the actor run produced "
                           "ZERO hard governor holds. A console that shows only happy paths "
                           "is evidence of nothing.")
                      {:scenarios (count runs)
                       :outcomes (mapv (juxt :id outcome) runs)})))
    ;; The fact-type classification must AGREE with the governor's own
    ;; verdict on every single run. If these ever disagree, the page's
    ;; hold counts are meaningless -- fail rather than print them.
    (doseq [sc runs]
      (let [by-fact    (has-kind? sc :governor-refusal)
            by-verdict (boolean (:hard? (:verdict sc)))]
        (when (not= by-fact by-verdict)
          (throw (ex-info "Hold classification disagrees with the governor verdict"
                          {:scenario (:id sc) :by-fact by-fact :by-verdict by-verdict
                           :verdict (:verdict sc)})))))
    ;; A page that never observed the rollout gate cannot claim to
    ;; distinguish it from a governor refusal.
    (when-not (some #(has-kind? % :phase-gate-hold) runs)
      (throw (ex-info "No phase-gate hold observed -- this page cannot demonstrate that a rollout gate differs from a governor refusal"
                      {:outcomes (mapv (juxt :id outcome) runs)})))
    holds))

;; ============================== HTML ==============================

(defn- esc [x]
  (-> (str x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- cell
  "Never leak a bare nil into the page."
  [x]
  (if (or (nil? x) (and (coll? x) (empty? x)) (= "" x)) "—" (esc x)))

(defn- group3 [s]
  (let [neg? (str/starts-with? s "-")
        body (if neg? (subs s 1) s)]
    (str (when neg? "-")
         (->> (reverse body)
              (partition-all 3)
              (map #(apply str (reverse %)))
              reverse
              (str/join ",")))))

(defn- fmt-num
  "Deterministic, locale-free number rendering. Integral doubles print
  as integers; everything else keeps 6 decimals (the governor's own
  tolerance is 1e-6, so a difference it would catch stays visible)."
  [n]
  (cond
    (nil? n) "—"
    (integer? n) (group3 (str n))
    :else
    (let [d (double n)
          r (Math/round d)]
      (if (< (Math/abs (- d (double r))) 1e-9)
        (group3 (str r))
        (let [s (String/format java.util.Locale/ROOT "%.6f" (into-array Object [d]))
              [i f] (str/split s #"\.")]
          (str (group3 i) "." f))))))

(defn- kw [x] (if (nil? x) "—" (str "<code>" (esc x) "</code>")))

(defn- section [id title & body]
  (str "<section id=\"" id "\">\n<h2>" (esc title) "</h2>\n"
       (str/join "\n" (remove nil? body))
       "\n</section>\n"))

(defn- table [headers rows]
  (str "<div class=\"tw\"><table>\n<thead><tr>"
       (str/join (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n<tbody>\n"
       (str/join "\n" (for [r rows]
                        (str "<tr>" (str/join (map #(str "<td>" % "</td>") r)) "</tr>")))
       "\n</tbody></table></div>"))

(def ^:private css "
:root{--bg:#f7f8fa;--fg:#1a1c1f;--muted:#5b6470;--line:#d5dae1;--card:#fff;
--accent:#0b4da2;--hold:#a5122a;--gate:#8a5a00;--commit:#0d6a3f;--reject:#6d3ab0;--code:#f0f2f5}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font-family:-apple-system,BlinkMacSystemFont,'Hiragino Sans','Noto Sans JP',sans-serif;
line-height:1.6;font-size:15px}
.wrap{max-width:1180px;margin:0 auto;padding:32px 20px 72px}
header.top{border-bottom:3px solid var(--accent);padding-bottom:20px;margin-bottom:8px}
h1{font-size:26px;margin:0 0 6px}
.sub{color:var(--muted);font-size:14px;margin:0}
h2{font-size:19px;margin:0 0 12px;padding-left:10px;border-left:5px solid var(--accent)}
section{background:var(--card);border:1px solid var(--line);border-radius:10px;
padding:20px;margin:20px 0}
p{margin:0 0 12px}
p.note{color:var(--muted);font-size:13.5px}
.tw{overflow-x:auto}
table{border-collapse:collapse;width:100%;font-size:13.5px}
th,td{border:1px solid var(--line);padding:7px 9px;text-align:left;vertical-align:top}
th{background:#eef1f5;font-weight:600;white-space:nowrap}
.num{font-variant-numeric:tabular-nums;white-space:nowrap}
td:has(>.num){text-align:right}
code{background:var(--code);padding:1px 5px;border-radius:4px;
font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px}
.kpis{display:flex;flex-wrap:wrap;gap:12px;margin:0 0 4px;padding:0;list-style:none}
.kpis li{flex:1 1 150px;background:var(--card);border:1px solid var(--line);
border-radius:10px;padding:14px 16px}
.kpis .n{font-size:26px;font-weight:700;font-variant-numeric:tabular-nums;display:block}
.kpis .l{font-size:12.5px;color:var(--muted)}
.kpis li.hold .n{color:var(--hold)}
.kpis li.gate .n{color:var(--gate)}
.kpis li.commit .n{color:var(--commit)}
.kpis li.reject .n{color:var(--reject)}
.tag{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;
font-weight:600;white-space:nowrap;border:1px solid}
.tag.hold{color:var(--hold);border-color:var(--hold);background:#fdeef1}
.tag.gate{color:var(--gate);border-color:var(--gate);background:#fdf5e6}
.tag.commit{color:var(--commit);border-color:var(--commit);background:#e9f6ef}
.tag.reject{color:var(--reject);border-color:var(--reject);background:#f3ecfb}
.tag.wait{color:var(--muted);border-color:var(--muted);background:#eef0f3}
.hold-card{border:1px solid var(--hold);border-left:6px solid var(--hold);
border-radius:8px;padding:14px 16px;margin:0 0 12px;background:#fffafb}
.hold-card h3{margin:0 0 6px;font-size:15px;color:var(--hold)}
.hold-card dl{display:grid;grid-template-columns:max-content 1fr;gap:3px 14px;margin:0;font-size:13.5px}
.hold-card dt{color:var(--muted)}
.hold-card dd{margin:0}
.callout{border:1px solid var(--gate);border-left:6px solid var(--gate);
background:#fffdf6;border-radius:8px;padding:14px 16px;margin:12px 0}
.callout h3{margin:0 0 6px;font-size:15px;color:var(--gate)}
.bad{color:var(--hold);font-weight:600}
.good{color:var(--commit);font-weight:600}
footer{color:var(--muted);font-size:13px;margin-top:28px;border-top:1px solid var(--line);padding-top:16px}
")

;; ---------------------------- sections ----------------------------

(defn- head-html [title]
  (str "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
       "<title>" (esc title) "</title>\n<style>" css "</style>\n</head>\n<body>\n<div class=\"wrap\">\n"))

(defn- kpi [class n label]
  (str "<li class=\"" class "\"><span class=\"n\">" n "</span><span class=\"l\">" (esc label) "</span></li>"))

(defn- summary-section [runs holds]
  (let [n-commit (count (filter #(has-kind? % :commit) runs))
        n-gate   (count (filter #(has-kind? % :phase-gate-hold) runs))
        n-rej    (count (filter #(has-kind? % :approver-refusal) runs))
        n-esc    (count (filter #(has-kind? % :escalation) runs))]
    (section
     "summary" "実行サマリ (このページの全数値は実行結果)"
     (str "<ul class=\"kpis\">"
          (kpi "" (count runs) "actor run")
          (kpi "hold" (count holds) "HARD governor 拒否")
          (kpi "gate" n-gate "phase gate による HOLD")
          (kpi "reject" n-rej "人間承認者による拒否")
          (kpi "commit" n-commit "SSoT へ commit")
          (kpi "" n-esc "承認要求 (escalation)")
          "</ul>")
     (str "<p class=\"note\">HARD governor 拒否・phase gate・承認者拒否は<strong>別物</strong>として数えている。"
          "分類は audit fact の型 (<code>:t</code>) を第一基準にし、<code>:violations</code> の有無では判定しない — "
          "<code>:approval-rejected</code> fact 自身が <code>{:rule :approver-rejected}</code> を持つため、"
          "violation を数えると人間の拒否を governor の拒否として数えてしまう。</p>"))))

(defn- scenario-section [runs]
  (section
   "runs" "シナリオ別の実行結果"
   (table ["#" "phase" "op" "対象" "advisor conf." "ok?" "hard?" "escalate?" "high-stakes?" "violations" "結果" "意図"]
          (for [sc runs
                :let [v (:verdict sc)]]
            [(esc (:id sc))
             (esc (:phase sc))
             (kw (get-in sc [:request :op]))
             (cell (get-in sc [:request :subject]))
             (str "<span class=\"num\">" (cell (:confidence v)) "</span>")
             (if (:ok? v) "true" "false")
             (if (:hard? v) "<span class=\"bad\">true</span>" "false")
             (if (:escalate? v) "true" "false")
             (if (:high-stakes? v) "true" "false")
             (str (count (:violations v)))
             (str "<span class=\"tag " (outcome-class sc) "\">" (esc (outcome sc)) "</span>")
             (esc (:why sc))]))
   (str "<p class=\"note\"><code>:ok?</code> はこの repo では「hard violation が無く、かつ escalate も要らない」"
        "= そのまま commit してよい、の意味 (<code>auxiliary.governor/check</code> の <code>(= 0 code)</code>)。"
        "したがって <code>:ok? false</code> かつ <code>:escalate? true</code> かつ violations 0 件は"
        "<strong>違反ではなく escalation</strong> — 上の表で S3 がそれにあたる。</p>")))

(defn- hold-section [holds]
  (section
   "holds" (str "HARD governor 拒否 " (count holds) " 件 — 何を拒んだか")
   (str/join "\n"
             (for [{:keys [scenario fact]} holds]
               (str "<div class=\"hold-card\">\n<h3>" (esc (:id scenario)) " · "
                    (esc (:op fact)) " · " (esc (:subject fact)) "</h3>\n<dl>"
                    "<dt>rule</dt><dd>" (str/join " " (map kw (:basis fact))) "</dd>"
                    "<dt>detail</dt><dd>"
                    (str/join "<br>" (map #(esc (:detail %)) (:violations fact))) "</dd>"
                    "<dt>advisor 自己申告 confidence</dt><dd>" (cell (:confidence fact)) "</dd>"
                    "<dt>disposition</dt><dd>" (kw (:disposition fact)) "</dd>"
                    "<dt>ledger 上の :actor</dt><dd>" (cell (:actor fact)) " (実行主体であって承認者ではない)</dd>"
                    "<dt>人間が覆せるか</dt><dd><span class=\"bad\">不可</span> — HARD violation は "
                    "<code>auxiliary.kernels.gate/verdict-code</code> で 2 を返し、"
                    "<code>phase-disposition</code> がどの phase でも 2 (HOLD) を返す。"
                    "承認ノードに到達しないので承認しようがない。</dd>"
                    "</dl>\n</div>")))))

(defn- discriminate-section [runs]
  (let [gate  (filter #(has-kind? % :phase-gate-hold) runs)
        rej   (filter #(has-kind? % :approver-refusal) runs)]
    (section
     "discriminate" "HOLD の 3 種類 — 同じ「止まった」でも別物"
     (table ["種類" "audit fact :t" "判別条件" "governor は clean か" "観測されたシナリオ"]
            [["<span class=\"tag hold\">HARD governor 拒否</span>"
              (kw :governor-hold)
              (str (kw :phase-reason) " が無い")
              "<span class=\"bad\">いいえ (hard? true)</span>"
              (str/join " " (map #(esc (:id %)) (filter #(has-kind? % :governor-refusal) runs)))]
             ["<span class=\"tag gate\">phase gate による HOLD</span>"
              (kw :governor-hold)
              (str (kw :phase-reason) " が有る")
              "<span class=\"good\">はい (hard? false, violations 0)</span>"
              (str/join " " (map #(esc (:id %)) gate))]
             ["<span class=\"tag reject\">人間承認者による拒否</span>"
              (kw :approval-rejected)
              "fact 型そのもの"
              "<span class=\"good\">はい (governor は escalate まで通した)</span>"
              (str/join " " (map #(esc (:id %)) rej))]])
     (str "<p class=\"note\">phase gate の HOLD (" (str/join " " (map #(esc (:id %)) gate))
          ") は <code>:violations</code> が 0 件で "
          (str/join " " (for [sc gate
                              f (:audit sc)
                              :when (= :phase-gate-hold (fact-kind f))]
                          (str (kw (:phase-reason f)) " (phase " (esc (:phase f)) ")")))
          " を持つ。承認者拒否 (" (str/join " " (map #(esc (:id %)) rej))
          ") は逆に <code>:violations</code> を <em>持つ</em> — "
          (str/join " " (for [sc rej
                              f (:audit sc)
                              :when (= :approver-refusal (fact-kind f))
                              v (:violations f)]
                          (kw (:rule v))))
          " — なので violation の有無で分類すると、この 3 行目が 1 行目に紛れ込む。</p>"))))

(defn- recompute-section [db runs]
  (let [ga-cases (filter #(= :average-adjustment (:case-type %)) (store/all-cases db))]
    (section
     "recompute"
     "独立再計算 — 案件が主張する分担額 vs. この repo 自身の計算"
     (str "<p>governor の <code>:apportionment-mismatch</code> チェックは、案件が主張する "
          "<code>:claimed-contribution</code> を信用せず、同じ "
          "<code>:interests</code> / <code>:total-loss-amount</code> から "
          "<code>auxiliary.registry/apportion-general-average</code> で独立に再計算し、"
          "1 件でも許容差 (1e-6) を超えたら HOLD する。下表は build 時にその関数を"
          "実際に呼んで作っている。</p>")
     (str/join "\n"
               (for [c ga-cases
                     :let [recomputed (registry/apportion-general-average
                                       (:interests c) (:total-loss-amount c))
                           by-party (into {} (map (juxt :party identity)) recomputed)
                           rows (for [{:keys [party value-at-risk claimed-contribution]} (:interests c)
                                      :let [r (get by-party party)
                                            delta (- (double claimed-contribution)
                                                     (double (:contribution r)))
                                            ok? (< (Math/abs delta) 1e-6)]]
                                  [(esc party)
                                   (str "<span class=\"num\">" (fmt-num value-at-risk) "</span>")
                                   (str "<span class=\"num\">" (fmt-num claimed-contribution) "</span>")
                                   (str "<span class=\"num\">" (fmt-num (:contribution r)) "</span>")
                                   (str "<span class=\"num" (when-not ok? " bad") "\">"
                                        (fmt-num delta) "</span>")
                                   (if ok? "<span class=\"good\">一致</span>"
                                       "<span class=\"bad\">不一致</span>")])
                           n-bad (count (remove (fn [{:keys [party claimed-contribution]}]
                                                  (< (Math/abs (- (double claimed-contribution)
                                                                  (double (:contribution (get by-party party)))))
                                                     1e-6))
                                                (:interests c)))]]
                 (str "<h3>" (esc (:case-reference c)) " · " (esc (:id c))
                      " · 共同海損 " (fmt-num (:total-loss-amount c)) " ("
                      (esc (:jurisdiction c)) ")</h3>"
                      (table ["利害関係者" "value-at-risk" "案件の主張額" "独立再計算" "差" "判定"] rows)
                      "<p class=\"note\">不一致 " (str n-bad) " 件 → "
                      (if (pos? n-bad)
                        (str "governor は <code>:apportionment-mismatch</code> で HOLD ("
                             (str/join " " (for [sc runs
                                                 :when (and (= (:id c) (get-in sc [:request :subject]))
                                                            (has-kind? sc :governor-refusal))]
                                             (esc (:id sc))))
                             ")。")
                        "この案件については通過。")
                      "</p>"))))))

(defn- register-section [db]
  (section
   "register" "案件レジスタ (実行後の SSoT)"
   (table ["case" "参照番号" "case-type" "法域" "共同海損/推奨額" "status" "recommendation-number"]
          (for [c (store/all-cases db)]
            [(kw (:id c))
             (cell (:case-reference c))
             (kw (:case-type c))
             (cell (:jurisdiction c))
             (str "<span class=\"num\">"
                  (fmt-num (or (:total-loss-amount c) (:recommended-amount c)))
                  "</span>")
             (kw (:status c))
             (cell (:recommendation-number c))]))))

(defn- assessment-section [db]
  (let [ids (map :id (store/all-cases db))
        rows (for [id ids
                   :let [a (store/assessment-of db id)]
                   :when a]
               [(kw id)
                (cell (:jurisdiction a))
                (str (count (:checklist a)))
                (cell (:spec-basis a))
                (cell (:legal-basis a))
                (cell (:approved-by a))])]
    (section
     "assessments" "committed な法域評価 (assessment/set)"
     (if (seq rows)
       (table ["case" "法域" "必要書類 件数" "spec-basis" "legal-basis" ":approved-by"] rows)
       "<p class=\"note\">commit された assessment は 0 件。</p>")
     (str "<p class=\"note\">未登録法域 (ATL) の評価は commit されていない — governor が HOLD したため、"
          "SSoT に行が作られない。</p>"))))

(defn- records-section [db]
  (let [recs (store/recommendation-history db)]
    (section
     "records" "確定した推奨/分担ドラフト記録 (append-only)"
     (if (seq recs)
       (str/join "\n"
                 (for [r recs]
                   (str "<h3>" (esc (get r "record_id")) " · " (esc (get r "kind")) "</h3>"
                        (table ["field" "value"]
                               (concat
                                [["case_reference" (cell (get r "case_reference"))]
                                 ["jurisdiction" (cell (get r "jurisdiction"))]
                                 ["immutable" (cell (get r "immutable"))]]
                                (when-let [t (get r "total_loss_amount")]
                                  [["total_loss_amount" (str "<span class=\"num\">" (fmt-num t) "</span>")]])
                                (when-let [a (get r "recommended_amount")]
                                  [["recommended_amount" (str "<span class=\"num\">" (fmt-num a) "</span>")]])
                                (for [c (get r "contributions")]
                                  [(str "contribution · " (esc (get c "party")))
                                   (str "<span class=\"num\">" (fmt-num (get c "contribution")) "</span>")]))))))
       "<p class=\"note\">記録は 0 件。</p>")
     (str "<p class=\"note\">記録に載る分担額は案件の主張値ではなく "
          "<code>auxiliary.registry/apportion-general-average</code> の"
          "独立再計算値 (<code>auxiliary.store/finalize!</code>)。"
          "証書は全て <code>status: draft-unsigned</code> / <code>issued_by_registry: false</code> — "
          "署名は免許を持つ精算人の行為であってこの actor の行為ではない。</p>"))))

(defn- ledger-section [db]
  (let [l (store/ledger db)]
    (section
     "ledger" (str "append-only 監査台帳 (" (count l) " 件)")
     (table ["#" ":t" ":op" ":actor" ":subject" ":disposition" ":basis / :violations"]
            (map-indexed
             (fn [i f]
               [(str i)
                (kw (:t f))
                (kw (:op f))
                (cell (:actor f))
                (cell (:subject f))
                (kw (:disposition f))
                (if (seq (:violations f))
                  (str/join "<br>" (for [v (:violations f)]
                                     (str (kw (:rule v)) " " (esc (:detail v)))))
                  (if (seq (:basis f))
                    (str/join "<br>" (map esc (:basis f)))
                    "—"))])
             l)))))

(defn- attribution-section [db runs]
  (let [rows (attribution-rows db runs)
        ledger-facts (store/ledger db)
        approval-facts-in-ledger (filter #(#{:approval-granted} (:t %)) ledger-facts)
        ledger-approver-keys (approver-keys-present ledger-facts)
        ledger-actors (into (sorted-set) (keep :actor ledger-facts))
        approvers-used (into (sorted-set) (keep #(get-in % [:approval :by]) runs))
        lossy (remove :retained? rows)]
    (section
     "attribution" "承認者の帰属 — 実行結果から導出した所見"
     (str "<p>下表は build 時に <strong>実際に人間の承認を受けて commit した run だけ</strong>を対象に、"
          "その effect が書いた SSoT 面を Store プロトコル経由で読み直し、"
          "承認者らしきキー (<code>:approved-by</code> 等) が残っているかを走査した結果。"
          "「この repo には欠陥がある」と決め打ちで書いてはいない — 書込経路が直れば次の build でこの表は変わる。</p>")
     (table ["#" "effect" "対象" "書込先" "承認者" "残っているキー" "帰属"]
            (for [r rows]
              [(esc (:scenario-id r))
               (kw (:effect r))
               (cell (:subject r))
               (esc (:surface r))
               (cell (:approver r))
               (if (seq (:keys-found r)) (str/join " " (map kw (:keys-found r))) "—")
               (if (:retained? r)
                 "<span class=\"good\">保持</span>"
                 "<span class=\"bad\">消失</span>")]))
     (when (seq lossy)
       (str "<div class=\"callout\">\n<h3>開示 (このタスクでは修正していない)</h3>\n<p>"
            "上の走査では effect ごとに挙動が割れた。"
            (str/join "" (for [r lossy]
                           (str "<br>· " (kw (:effect r)) " は " (esc (:surface r))
                                " に承認者を残さない (" (esc (:scenario-id r)) ", 承認者 "
                                (esc (:approver r)) ")。")))
            "<br>原因は <code>auxiliary.operation/commit-record</code> が承認者を "
            "<code>:payload</code> にだけ載せるのに対し、<code>auxiliary.store/MemStore</code> の "
            "<code>:case/upsert</code> は <code>:value</code> を使い、"
            "<code>:recommendation/mark-finalized</code> は <code>:value</code> も "
            "<code>:payload</code> も使わず <code>finalize!</code> を呼ぶため。"
            "<code>:assessment/set</code> だけが <code>:payload</code> を使うので承認者が残る。</p>"
            "<p>台帳側も同じ穴がある: 実行された <code>:approval-granted</code> fact は run の "
            "<code>:audit</code> チャネルには載るが、commit ノードが台帳へ書くのは "
            "<code>commit-fact</code> だけなので、<strong>永続化された台帳 "
            (str (count ledger-facts)) " 件のうち <code>:approval-granted</code> は "
            (str (count approval-facts-in-ledger)) " 件</strong>、承認者らしきキーは "
            (if (seq ledger-approver-keys) (str/join " " (map kw (sort-by str ledger-approver-keys))) "0 件")
            "。つまり「誰が承認したか」は永続層に残っていない。</p>"
            "<p>rendering タスクの範囲で governor / store を書き換えるのは筋が悪いので"
            "修正はしていない。ここに開示する。</p>\n</div>"))
     (str "<p class=\"note\"><strong>:actor は承認者ではない。</strong> "
          "台帳 fact の <code>:actor</code> は <code>:context</code> の実行主体 id で、実測値は "
          (str/join " " (map kw ledger-actors))
          "。この run で使った承認者 id は " (str/join " " (map kw approvers-used))
          " であり、両者は交わらない ("
          (if (empty? (set/intersection (set ledger-actors) (set approvers-used)))
            "<span class=\"good\">互いに素であることを実測</span>"
            "<span class=\"bad\">重なりあり</span>")
          ") — <code>:actor</code> を承認者として読むと、この 2 つが同じ値の環境では"
          "見分けがつかなくなる。</p>"))))

(defn- facts-section []
  (let [cov (facts/coverage)]
    (section
     "facts" "法域 spec-basis カタログ (seed)"
     (table ["ISO3" "名称" "所管当局" "法的根拠" "出典" "必要書類"]
            (for [[iso3 m] (sort-by key facts/catalog)]
              [(kw iso3)
               (esc (:name m))
               (esc (:owner-authority m))
               (esc (:legal-basis m))
               (str "<code>" (esc (:provenance m)) "</code>")
               (str (count (:required-evidence m)) " 件")]))
     (str "<p class=\"note\">coverage: 登録 " (str (:covered cov)) " / 要求 " (str (:requested cov))
          " 法域。" (esc (:note cov)) "</p>")
     (str "<p class=\"note\">海損精算の方法論は法域別ではなく "
          "York-Antwerp Rules 2016 が全エントリ共通 (契約/船荷証券への組込みによる)。"
          "この表に無い法域は spec-basis 無し = governor が HOLD する (S5 がその実例)。</p>"))))

(defn- gate-section []
  (section
   "gate" "rollout phase テーブルと safety kernel"
   (table ["phase" "label" "writes" "auto-commit"]
          (for [[p m] (sort-by key phase/phases)]
            [(str p)
             (esc (:label m))
             (if (seq (:writes m)) (str/join " " (map kw (sort-by str (:writes m)))) "—")
             (if (seq (:auto m)) (str/join " " (map kw (sort-by str (:auto m)))) "—")]))
   (str "<p class=\"note\"><code>:recommendation/finalize</code> はどの phase の "
        "<code>:auto</code> にも入っていない — rollout の到達点ではなく構造的な不変条件。"
        "governor 側の <code>:actuation</code> high-stakes ゲートが同じことを独立に強制している。</p>")
   (str "<p class=\"note\">safety kernel battery (build 時に実行): "
        "<code>auxiliary.kernels.gate/battery-pass-count</code> = "
        (str (kernel/battery-pass-count)) " / "
        (str kernel/battery-case-count) " ケース"
        (if (= (kernel/battery-pass-count) kernel/battery-case-count)
          " <span class=\"good\">全通過</span>"
          " <span class=\"bad\">不一致</span>")
        "。confidence floor = <code>" (str kernel/confidence-floor-x100)
        "</code> (x100)。</p>")))

(defn- footer-html [runs holds]
  (str "<footer><p>この HTML は <code>src/auxiliary/render_html.clj</code> が "
       "<code>auxiliary.store/seed-db</code> に対して "
       "<code>auxiliary.operation/build</code> のグラフを "
       (str (count runs)) " 回実行し、その出力だけから生成している。"
       "再生成: <code>clojure -M:dev:render-html</code>。</p>"
       "<p>build 時不変条件: HARD governor 拒否が 0 件なら <code>-main</code> は例外を投げ、"
       "ファイルを書かない (今回の実測値 " (str (count holds)) " 件)。"
       "加えて fact 型による分類と governor verdict の <code>:hard?</code> が"
       "全 run で一致すること、phase gate による HOLD が最低 1 件観測されることも"
       "同じく build を落とす条件にしてある。</p>"
       "<p>cloud-itonami-isic-6629 · ISIC 6629 保険・年金基金補助活動 · "
       "生成物であり手書きではない。</p></footer>\n</div>\n</body>\n</html>\n"))

;; ============================== main ==============================

(defn render
  "Run the actor and return {:html .. :db .. :runs .. :holds ..}."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (mapv #(run-scenario! actor %) scenarios)
        holds (assert-invariants! runs)
        html  (str (head-html "cloud-itonami-isic-6629 · Operator Console")
                   "<header class=\"top\">\n<h1>保険補助業務アクター オペレーターコンソール</h1>\n"
                   "<p class=\"sub\">cloud-itonami-isic-6629 · ISIC 6629 · "
                   "クレーム管理受託 / 海損精算 — 実行結果のみ</p>\n</header>\n"
                   (summary-section runs holds)
                   (scenario-section runs)
                   (hold-section holds)
                   (discriminate-section runs)
                   (recompute-section db runs)
                   (register-section db)
                   (assessment-section db)
                   (records-section db)
                   (ledger-section db)
                   (attribution-section db runs)
                   (facts-section)
                   (gate-section)
                   (footer-html runs holds))]
    {:html html :db db :runs runs :holds holds}))

(defn -main [& args]
  (let [out (or (first args) default-out)
        {:keys [html db runs holds]} (render)
        f (io/file out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f html)
    (println "wrote" (.getPath f) (count html) "chars")
    (println "  runs:" (count runs)
             "| hard governor holds:" (count holds)
             "| phase-gate holds:" (count (filter #(has-kind? % :phase-gate-hold) runs))
             "| approver refusals:" (count (filter #(has-kind? % :approver-refusal) runs))
             "| commits:" (count (filter #(has-kind? % :commit) runs)))
    (println "  ledger facts:" (count (store/ledger db))
             "| records:" (count (store/recommendation-history db)))
    (doseq [{:keys [scenario fact]} holds]
      (println "  HOLD" (:id scenario) (:op fact) (:subject fact) (vec (:basis fact))))))
