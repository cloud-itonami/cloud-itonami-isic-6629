(ns auxiliary.render-html-test
  "Locks the operator-console renderer's build-time invariants so they
  keep MEANING something.

  JVM-only (`.clj`) on purpose: `auxiliary.render-html` is a build-time
  tool, not actor runtime, so it is deliberately absent from
  `auxiliary.portable-cljs-test-runner`'s namespace list. Everything it
  exercises -- the graph, the governor, the safety kernel, the store --
  is portable `.cljc` and IS covered there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [auxiliary.render-html :as r]))

(defn- render [] (r/render))

(deftest renders-from-a-real-run
  (let [{:keys [html runs holds]} (render)]
    (testing "the actor actually ran"
      (is (= (count @#'r/scenarios) (count runs)))
      (is (every? #(some? (:proposal %)) runs)
          "every scenario produced an advisor proposal")
      (is (every? #(some? (:verdict %)) runs)
          "every scenario produced a governor verdict"))
    (testing "the page is non-trivial and structurally sane"
      (is (str/starts-with? html "<!DOCTYPE html>"))
      (is (str/ends-with? (str/trim html) "</html>"))
      (is (= (count (re-seq #"<section id=" html))
             (count (re-seq #"</section>" html))))
      (is (not (re-find #">nil<|>\s*nil\s*<" html)) "no nil leaked into the page")
      (is (not (str/includes? html "%s")) "no unfilled format placeholder"))
    (testing "at least one HARD governor hold is on the page"
      (is (pos? (count holds)))
      (is (str/includes? html "HARD governor")))))

(deftest hard-hold-invariant-can-fail
  (testing "the guard is satisfiable AND falsifiable -- a guard that can
            never fail proves nothing, and one that can never pass is
            indistinguishable from a broken build"
    (is (pos? (count (:holds (render)))) "satisfiable on the real scenario set")
    (with-redefs [r/scenarios (vec (remove #(#{"S5" "S6" "S8"} (:id %)) @#'r/scenarios))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ZERO hard governor holds"
                            (render))
          "cutting the three refusal scenarios must fail the build"))))

(deftest hold-count-tracks-the-refusal-scenarios
  (testing "the count moves one-for-one with the scenarios that cause it"
    (let [n (count (:holds (render)))]
      (with-redefs [r/scenarios (vec (remove #(#{"S8"} (:id %)) @#'r/scenarios))]
        (is (= (dec n) (count (:holds (render))))))
      (testing "and does NOT move when a non-governor refusal is cut"
        (with-redefs [r/scenarios (vec (remove #(#{"S11"} (:id %)) @#'r/scenarios))]
          (is (= n (count (:holds (render))))
              "S11 is a HUMAN approver rejection; counting it as a governor
               refusal is the exact mistake this classification exists to
               avoid"))))))

(deftest classification-is-not-violation-counting
  (testing "an :approval-rejected fact carries {:rule :approver-rejected},
            so counting violation-bearing facts over-counts governor refusals"
    (let [{:keys [runs holds]} (render)
          by-violations (count (for [sc runs
                                     f (:audit sc)
                                     :when (and (#{:governor-hold :approval-rejected} (:t f))
                                                (seq (:violations f)))]
                                 f))]
      (is (< (count holds) by-violations)
          "the two counts must differ, or this page has not demonstrated the distinction"))))

(deftest phase-gate-is-not-a-governor-refusal
  (let [{:keys [runs]} (render)
        gate-facts (for [sc runs
                         f (:audit sc)
                         :when (and (= :governor-hold (:t f)) (:phase-reason f))]
                     {:sc sc :f f})]
    (is (seq gate-facts) "at least one rollout-phase hold was observed")
    (doseq [{:keys [sc f]} gate-facts]
      (is (false? (boolean (:hard? (:verdict sc))))
          "a phase-gate hold means the governor was CLEAN")
      (is (empty? (:violations f))
          "a phase-gate hold carries no governor violation"))
    (with-redefs [r/scenarios (vec (remove #(#{"S9"} (:id %)) @#'r/scenarios))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No phase-gate hold observed"
                            (render))))))

(deftest deterministic
  (is (= (:html (render)) (:html (render)))
      "no per-run uuid or wall-clock value may reach the page"))
