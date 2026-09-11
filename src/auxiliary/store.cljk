(ns auxiliary.store
  "SSoT for the insurance-auxiliary actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam `cloud-itonami-
  isic-6511`'s `underwriting.store` / `cloud-itonami-isic-6512`'s
  `casualty.store` / `cloud-itonami-isic-6621`'s `adjustment.store` /
  `cloud-itonami-isic-6622`'s `intermediation.store` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/auxiliary/store_contract_test.clj), which is the whole point:
  the actor, the Insurance Auxiliary Governor and the audit ledger never
  know which SSoT they run on.

  Unlike every sibling actor, this Store has NO separate `party`/
  `conflict-of` concept -- this actor's distinctive HARD check
  (`apportionment-mismatch-violations`, see `auxiliary.governor`) is an
  independent-recompute check on the case's OWN claimed apportionment,
  not a party-screening check, so there is genuinely nothing for a
  party directory to do here.

  The ledger stays append-only on every backend: 'which case was
  assessed on what jurisdictional/methodological basis, which
  recommendation or apportionment was finalized, approved by whom' is
  always a query over an immutable log -- the audit trail an insurer or
  a shipping interest trusting an outsourced administrator/adjuster
  needs, and the evidence an operator needs if a recommendation is later
  disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [auxiliary.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (case-file [s id])
  (all-cases [s])
  (assessment-of [s case-id] "committed jurisdiction methodology/evidence assessment, or nil")
  (ledger [s])
  (recommendation-history [s] "the append-only claims-recommendation/apportionment history (auxiliary.registry drafts)")
  (next-sequence [s jurisdiction] "next recommendation-number sequence for a jurisdiction")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-cases [s cases] "replace/seed the case directory (map id->case)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained case set (both case types) so the actor +
  tests run offline."
  []
  {:cases
   {"case-1" {:id "case-1" :case-reference "GA-2026-001" :case-type :average-adjustment
              :total-loss-amount 600000
              :interests [{:party "Cargo Owner A" :value-at-risk 2000000 :claimed-contribution 200000.0}
                         {:party "Shipowner" :value-at-risk 3000000 :claimed-contribution 300000.0}
                         {:party "Freight Interest" :value-at-risk 1000000 :claimed-contribution 100000.0}]
              :jurisdiction "GBR" :status :intake}
    "case-2" {:id "case-2" :case-reference "TPA-2026-001" :case-type :claims-administration
              :recommended-amount 15000
              :jurisdiction "ATL" :status :intake}
    "case-3" {:id "case-3" :case-reference "GA-2026-002" :case-type :average-adjustment
              :total-loss-amount 100000
              :interests [{:party "Cargo Owner B" :value-at-risk 500000 :claimed-contribution 99999.0}
                         {:party "Shipowner B" :value-at-risk 500000 :claimed-contribution 1.0}]
              :jurisdiction "GBR" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize!
  "Backend-agnostic `:recommendation/mark-finalized` -- looks up the
  case via the protocol, dispatches on `:case-type`, drafts the
  recommendation/apportionment record, and returns
  {:result .. :case-patch ..} for the caller to persist. For an
  `:average-adjustment` case, the contributions PERSISTED are this
  actor's OWN independent recompute (`registry/apportion-general-
  average`), not the case's claimed figures -- the governor has already
  verified they match within tolerance, but the authoritative record is
  always this vehicle's own math."
  [s case-id]
  (let [c (case-file s case-id)
        seq-n (next-sequence s (:jurisdiction c))]
    (case (:case-type c)
      :claims-administration
      (let [result (registry/register-claims-recommendation
                    (:case-reference c) (:recommended-amount c) (:jurisdiction c) seq-n)]
        {:result result
         :case-patch {:status :finalized
                     :recommendation-number (get result "recommendation_number")}})

      :average-adjustment
      (let [contributions (registry/apportion-general-average (:interests c) (:total-loss-amount c))
            result (registry/register-apportionment
                    (:case-reference c) (:total-loss-amount c) contributions (:jurisdiction c) seq-n)]
        {:result result
         :case-patch {:status :finalized
                     :recommendation-number (get result "recommendation_number")}}))))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (case-file [_ id] (get-in @a [:cases id]))
  (all-cases [_] (sort-by :id (vals (:cases @a))))
  (assessment-of [_ case-id] (get-in @a [:assessments case-id]))
  (ledger [_] (:ledger @a))
  (recommendation-history [_] (:recommendations @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :case/upsert
      (swap! a update-in [:cases (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :recommendation/mark-finalized
      (let [case-id (first path)
            {:keys [result case-patch]} (finalize! s case-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:cases case-id]))] (fnil inc 0))
                       (update-in [:cases case-id] merge case-patch)
                       (update :recommendations registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-cases [s cases] (when (seq cases) (swap! a assoc :cases cases)) s))

(defn seed-db
  "A MemStore seeded with the demo case set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :ledger [] :sequences {} :recommendations []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (interests, assessment payloads, ledger facts,
  recommendation/apportionment records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:case/id               {:db/unique :db.unique/identity}
   :assessment/case-id    {:db/unique :db.unique/identity}
   :ledger/seq            {:db/unique :db.unique/identity}
   :recommendation/seq    {:db/unique :db.unique/identity}
   :sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- case->tx [{:keys [id case-reference case-type total-loss-amount interests
                        recommended-amount jurisdiction status recommendation-number]}]
  (cond-> {:case/id id}
    case-reference          (assoc :case/case-reference case-reference)
    case-type                (assoc :case/case-type case-type)
    total-loss-amount       (assoc :case/total-loss-amount total-loss-amount)
    interests                (assoc :case/interests (enc interests))
    recommended-amount      (assoc :case/recommended-amount recommended-amount)
    jurisdiction              (assoc :case/jurisdiction jurisdiction)
    status                    (assoc :case/status status)
    recommendation-number  (assoc :case/recommendation-number recommendation-number)))

(def ^:private case-pull
  [:case/id :case/case-reference :case/case-type :case/total-loss-amount :case/interests
   :case/recommended-amount :case/jurisdiction :case/status :case/recommendation-number])

(defn- pull->case [m]
  (when (:case/id m)
    {:id (:case/id m) :case-reference (:case/case-reference m) :case-type (:case/case-type m)
     :total-loss-amount (:case/total-loss-amount m) :interests (or (dec* (:case/interests m)) [])
     :recommended-amount (:case/recommended-amount m)
     :jurisdiction (:case/jurisdiction m) :status (:case/status m)
     :recommendation-number (:case/recommendation-number m)}))

(defrecord DatomicStore [conn]
  Store
  (case-file [_ id]
    (pull->case (d/pull (d/db conn) case-pull [:case/id id])))
  (all-cases [_]
    (->> (d/q '[:find [?id ...] :where [?e :case/id ?id]] (d/db conn))
         (map #(pull->case (d/pull (d/db conn) case-pull [:case/id %])))
         (sort-by :id)))
  (assessment-of [_ case-id]
    (dec* (d/q '[:find ?p . :in $ ?cid
                :where [?a :assessment/case-id ?cid] [?a :assessment/payload ?p]]
              (d/db conn) case-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (recommendation-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :recommendation/seq ?s] [?e :recommendation/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :case/upsert
      (d/transact! conn [(case->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/case-id (first path) :assessment/payload (enc payload)}])

      :recommendation/mark-finalized
      (let [case-id (first path)
            {:keys [result case-patch]} (finalize! s case-id)
            jurisdiction (:jurisdiction (case-file s case-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(case->tx (assoc case-patch :id case-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:recommendation/seq (count (recommendation-history s)) :recommendation/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-cases [s cases]
    (when (seq cases) (d/transact! conn (mapv case->tx (vals cases)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:cases
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [cases]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-cases s cases))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo case set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
