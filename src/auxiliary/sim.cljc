(ns auxiliary.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean average-adjustment
  case through intake -> jurisdiction methodology assessment ->
  recommendation-finalization proposal (always escalates) -> human
  approval -> commit, then shows three HARD holds (a claims-
  administration case with no official jurisdiction spec-basis, a
  claims-administration finalization attempted with no evidence on
  file, and an average-adjustment apportionment whose claimed per-
  interest contributions do not match this vehicle's own independent
  recompute) that never reach a human at all, and prints the audit
  ledger + the draft recommendation/apportionment records."
  (:require [langgraph.graph :as g]
            [auxiliary.store :as store]
            [auxiliary.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :adjuster :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== case/intake case-1 (GBR, average-adjustment, clean apportionment) ==")
    (println (exec! actor "t1" {:op :case/intake :subject "case-1"
                                :patch {:id "case-1" :status :ready}} operator))

    (println "== jurisdiction/assess case-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "case-1"} operator))
    (println (approve! actor "t2"))

    (println "== recommendation/finalize case-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t3" {:op :recommendation/finalize :subject "case-1"} operator)]
      (println r)
      (println "-- human average adjuster approves --")
      (println (approve! actor "t3")))

    (println "== case/intake case-2 (ATL, claims-administration) ==")
    (println (exec! actor "t4" {:op :case/intake :subject "case-2"
                                :patch {:id "case-2" :status :ready}} operator))

    (println "== jurisdiction/assess case-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :jurisdiction/assess :subject "case-2"} operator))

    (println "== recommendation/finalize case-2 (no assessment on file -> HARD hold) ==")
    (println (exec! actor "t6" {:op :recommendation/finalize :subject "case-2"} operator))

    (println "== jurisdiction/assess case-3 (GBR; escalates -- human approves) ==")
    (exec! actor "t7a" {:op :jurisdiction/assess :subject "case-3"} operator)
    (approve! actor "t7a")

    (println "== recommendation/finalize case-3 (claimed contributions do not match the independent recompute -> HARD hold) ==")
    (println (exec! actor "t7" {:op :recommendation/finalize :subject "case-3"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft recommendation/apportionment records ==")
    (doseq [r (store/recommendation-history db)] (println r))))
