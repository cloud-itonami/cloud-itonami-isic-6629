(ns wasm.apportionment-mismatch-test
  "Hosts wasm/apportionment_mismatch.wasm (compiled from wasm/
  apportionment_mismatch.kotoba, see wasm/README.md) via kototama.tender
  -- proves auxiliary.governor's `apportionment-mismatch-violations` check
  (an independent per-interest recompute against `auxiliary.registry/
  apportion-general-average`) runs as a real WASM guest, not just as JVM
  Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the four real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/apportionment_mismatch.kotoba's ns-adjacent header comment for the
  offset layout and the integer-truncation tolerance rationale."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/apportionment_mismatch.wasm"))))

(defn- run-apportionment-matches?
  [value-at-risk total-value-at-risk total-loss-amount claimed-contribution]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 value-at-risk)
    (.writeI32 memory 4 total-value-at-risk)
    (.writeI32 memory 8 total-loss-amount)
    (.writeI32 memory 12 claimed-contribution)
    (tender/call-main instance)))

(deftest apportionment-wasm-approves-exact-match
  (testing "recomputed share (value-at-risk=2000 / total-value-at-risk=6000 * total-loss-amount=600) = 200, matches the claim exactly -> approves"
    ;; hand-verified: (2000 * 600) / 6000 = 1200000 / 6000 = 200
    (is (= 1 (run-apportionment-matches? 2000 6000 600 200)))))

(deftest apportionment-wasm-approves-within-tolerance-truncation
  (testing "true share is a non-terminating fraction (2/3 of 100 = 66.66..); the wasm port's single integer quot floors to 66, an upstream that instead rounds up to 67 is only 1 unit off -> still approves (this is the integer-truncation slack the 1-unit tolerance exists to absorb, the honest analog of `close?`'s float-epsilon role -- see wasm/README.md)"
    ;; hand-verified: (2 * 100) / 3 = 200 / 3 = 66 (quot floors); |66 - 67| = 1 <= tolerance
    (is (= 1 (run-apportionment-matches? 2 3 100 67)))))

(deftest apportionment-wasm-rejects-beyond-tolerance-mismatch
  (testing "claimed contribution wildly inconsistent with the independent recompute (99 vs. the true 50) -> rejects, auxiliary.governor's :apportionment-mismatch HARD violation for this interest"
    ;; hand-verified: (500 * 100) / 1000 = 50000 / 1000 = 50; |50 - 99| = 49 > tolerance
    (is (= 0 (run-apportionment-matches? 500 1000 100 99)))))

(deftest apportionment-wasm-boundary-at-tolerance-edge
  (testing "recomputed contribution is 50 (500/1000 * 100); a claim exactly 1 unit off is still within tolerance, but 2 units off is not"
    ;; hand-verified: (500 * 100) / 1000 = 50
    (is (= 1 (run-apportionment-matches? 500 1000 100 51)))
    (is (= 0 (run-apportionment-matches? 500 1000 100 52)))))

(deftest apportionment-wasm-rejects-zero-total-value-at-risk
  (testing "total-value-at-risk = 0 would trap wasm's i32.div_s -- the guest short-circuits to 0 (mismatch, fail-closed) instead of dividing, even when every other input is also 0 (defense-in-depth; apportion-general-average already guards this case upstream with a thrown ex-info before the wasm guest would ever be called)"
    (is (= 0 (run-apportionment-matches? 0 0 0 0)))))
