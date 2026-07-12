(ns auxiliary.governor-contract-test
  "The governor contract as executable tests -- the auxiliary-services
  analog of every sibling actor's own governor-contract test. The
  single invariant under test:

    Claims-LLM never finalizes a recommendation/apportionment the
    Insurance Auxiliary Governor would reject, `:recommendation/
    finalize` NEVER auto-commits at any phase, `:case/intake` (no
    liability risk) MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [auxiliary.store :as store]
            [auxiliary.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :adjuster :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :case/intake :subject "case-1"
                   :patch {:id "case-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/case-file db "case-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "case-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "case-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "case-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "case-2")) "no assessment written"))))

(deftest finalize-claims-administration-without-evidence-is-held
  (testing "case-2 (claims-administration) with no assessment on file -> HOLD (evidence-incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :recommendation/finalize :subject "case-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis)))
      (is (empty? (store/recommendation-history db))))))

(deftest finalize-average-adjustment-with-mismatched-contributions-is-held
  (testing "case-3's claimed contributions do not match the independent pro-rata recompute -> HOLD"
    (let [[db actor] (fresh)
          _ (exec-op actor "t5a" {:op :jurisdiction/assess :subject "case-3"} operator)
          _ (approve! actor "t5a")
          res (exec-op actor "t5" {:op :recommendation/finalize :subject "case-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:apportionment-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/recommendation-history db))))))

(deftest recommendation-finalize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed average-adjustment finalization still ALWAYS interrupts for human approval -- actuation is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "case-1"} operator)
          _ (approve! actor "t6a")
          r1 (exec-op actor "t6" {:op :recommendation/finalize :subject "case-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, apportionment record drafted"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :finalized (:status (store/case-file db "case-1"))))
          (is (= 1 (count (store/recommendation-history db))) "one draft apportionment record")))))
  (testing "reject -> hold, nothing finalized"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "case-1"} operator)
          _ (approve! actor "t7a")
          _ (exec-op actor "t7" {:op :recommendation/finalize :subject "case-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/recommendation-history db)) "nothing finalized on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :case/intake :subject "case-1"
                       :patch {:id "case-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "case-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
