(ns auxiliary.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [auxiliary.registry :as r]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

;; ----------------------------- apportion-general-average -----------------------------

(def interests-fixture
  [{:party "Cargo Owner A" :value-at-risk 2000000}
   {:party "Shipowner" :value-at-risk 3000000}
   {:party "Freight Interest" :value-at-risk 1000000}])

(deftest apportion-general-average-splits-pro-rata-by-value-at-risk
  (let [result (r/apportion-general-average interests-fixture 600000)
        by-party (into {} (map (juxt :party identity)) result)]
    (is (close? 200000.0 (:contribution (get by-party "Cargo Owner A"))))
    (is (close? 300000.0 (:contribution (get by-party "Shipowner"))))
    (is (close? 100000.0 (:contribution (get by-party "Freight Interest"))))))

(deftest apportion-general-average-contributions-sum-to-the-total-loss
  (testing "the whole point of general average -- shares must sum exactly to the shared loss"
    (let [result (r/apportion-general-average interests-fixture 600000)]
      (is (close? 600000.0 (reduce + (map :contribution result)))))))

(deftest apportion-general-average-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/apportion-general-average [] 600000)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/apportion-general-average interests-fixture -1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/apportion-general-average [{:party "x" :value-at-risk 0}] 600000))))

;; ----------------------------- register-claims-recommendation -----------------------------

(deftest claims-recommendation-is-a-draft-not-a-real-payment
  (let [result (r/register-claims-recommendation "TPA-2026-001" 15000 "ATL" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest claims-recommendation-assigns-recommendation-number
  (let [result (r/register-claims-recommendation "TPA-2026-001" 15000 "JPN" 7)]
    (is (= (get result "recommendation_number") "JPN-REC-000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "claims-recommendation-draft"))))

(deftest claims-recommendation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claims-recommendation "" 15000 "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claims-recommendation "TPA-2026-001" -1 "JPN" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claims-recommendation "TPA-2026-001" 15000 "" 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-claims-recommendation "TPA-2026-001" 15000 "JPN" -1))))

;; ----------------------------- register-apportionment -----------------------------

(deftest apportionment-is-a-draft-not-a-real-payment
  (let [contributions (r/apportion-general-average interests-fixture 600000)
        result (r/register-apportionment "GA-2026-001" 600000 contributions "GBR" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest apportionment-assigns-recommendation-number
  (let [contributions (r/apportion-general-average interests-fixture 600000)
        result (r/register-apportionment "GA-2026-001" 600000 contributions "GBR" 7)]
    (is (= (get result "recommendation_number") "GBR-REC-000007"))
    (is (= (get-in result ["record" "kind"]) "apportionment-draft"))
    (is (= 3 (count (get-in result ["record" "contributions"]))))))

(deftest apportionment-validation-rules
  (let [contributions (r/apportion-general-average interests-fixture 600000)]
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-apportionment "" 600000 contributions "GBR" 0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-apportionment "GA-2026-001" -1 contributions "GBR" 0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-apportionment "GA-2026-001" 600000 [] "GBR" 0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-apportionment "GA-2026-001" 600000 contributions "" 0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-apportionment "GA-2026-001" 600000 contributions "GBR" -1)))))

(deftest recommendation-history-is-append-only
  (let [contributions (r/apportion-general-average interests-fixture 600000)
        r1 (r/register-apportionment "GA-2026-001" 600000 contributions "GBR" 0)
        hist (r/append [] r1)
        r2 (r/register-claims-recommendation "TPA-2026-001" 15000 "GBR" 1)
        hist2 (r/append hist r2)]
    (is (= 2 (count hist2)))
    (is (= "GBR-REC-000000" (get-in hist2 [0 "record_id"])))
    (is (= "GBR-REC-000001" (get-in hist2 [1 "record_id"])))))
