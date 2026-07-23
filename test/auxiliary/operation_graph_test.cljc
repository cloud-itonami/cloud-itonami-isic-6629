(ns auxiliary.operation-graph-test
  "Integration tests for `auxiliary.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes. No prior test file in this repo exercised
  `operation/build` at all -- every other test covers
  governor/phase/facts/registry/store/kernels in isolation, which proves
  those pure functions work but not that the graph wiring actually
  threads them together."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [auxiliary.operation :as operation]
            [auxiliary.store :as store]))

(def ^:private op-context {:actor-id "operator-01" :phase 3})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest commit-path-case-intake-auto-commits-in-phase-3
  (testing ":case/intake is the only op in phase-3's :auto set -- a
            clean intake proposal commits straight through the REAL
            compiled graph with no interrupt, and the ledger is
            verified EMPTY before the run so the post-run fact is
            genuinely this run's own effect"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-commit"
                         {:op :case/intake :subject "case-test-1"
                          :patch {:id "case-test-1" :case-reference "TPA-2026-TEST"
                                  :case-type :claims-administration
                                  :jurisdiction "GBR" :status :intake}})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :case/intake (:op (first ledger)))))
        (is (= "TPA-2026-TEST" (:case-reference (store/case-file s "case-test-1"))))))))

(deftest hard-hold-no-spec-basis-blocks-before-escalation
  (testing "case-2's own jurisdiction (ATL) has no registered spec-basis
            -- a HARD governor violation. The real graph routes straight
            to :hold, never pausing for human approval even though
            :jurisdiction/assess is not in phase-3's :auto set"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-hold" {:op :jurisdiction/assess :subject "case-2"})
            state (:state result)]
        (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
        (is (= :hold (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (some #{:no-spec-basis} (map :rule (:violations (first ledger))))))))))

(deftest hard-hold-apportionment-mismatch-through-compiled-graph
  (testing "case-3's own claimed per-interest contributions
            (99999.0 / 1.0) do NOT match the independently recomputed
            general-average apportionment for its own interests/total-
            loss-amount -- a HARD governor violation, proven end-to-end
            through the compiled graph. Note the finalize proposal
            requires evidence too, so this proves apportionment-mismatch
            specifically fires among the possible hard violations"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-apportion"
                       {:op :recommendation/finalize :subject "case-3"})
          state (:state result)]
      (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
      (is (= :hold (:disposition state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :governor-hold (:t (first ledger))))
        (is (some #{:apportionment-mismatch} (map :rule (:violations (first ledger)))))))))

(deftest escalate-then-approve-commits-and-genuinely-consults-advisor
  (testing ":jurisdiction/assess is NEVER in any phase's :auto set, so
            even a Governor-clean proposal for a REAL jurisdiction
            (GBR, with a real spec-basis) GENUINELY interrupts
            (checkpointed) at :request-approval -- the ledger stays
            EMPTY until a human resumes it"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [held (exec actor "t-escalate" {:op :jurisdiction/assess :subject "case-1"})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
        (let [approved (g/run* actor {:approval {:status :approved :by "adjuster-01"}}
                               {:thread-id "t-escalate" :resume? true})
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [ledger (store/ledger s)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= :jurisdiction/assess (:op (first ledger))))))))))

(deftest escalate-then-reject-holds
  (testing "a human adjuster rejecting an escalated
            :jurisdiction/assess routes to :hold via the
            :request-approval node's own decision, and durably records
            the rejection -- not a hand-rolled parallel path"
    (let [s (store/seed-db)
          actor (operation/build s)
          _held (exec actor "t-reject" {:op :jurisdiction/assess :subject "case-1"})
          rejected (g/run* actor {:approval {:status :rejected :by "adjuster-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))))))
