(ns auxiliary.facts-test
  (:require [clojure.test :refer [deftest is]]
            [auxiliary.facts :as facts]))

(deftest gbr-has-a-spec-basis
  (is (some? (facts/spec-basis "GBR")))
  (is (string? (:provenance (facts/spec-basis "GBR")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "GBR")]
    (is (facts/required-evidence-satisfied? "GBR" all))
    (is (not (facts/required-evidence-satisfied? "GBR" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
