(ns auxiliary.phase-test
  "The phase table as executable tests. The single invariant this repo
  cannot regress on: `:recommendation/finalize` must NEVER be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [auxiliary.phase :as phase]))

(deftest recommendation-finalize-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real recommendation/apportionment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :recommendation/finalize))
          (str "phase " n " must not auto-commit :recommendation/finalize")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-intake
  (is (= #{:case/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :case/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :recommendation/finalize} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :case/intake} :commit)))))
