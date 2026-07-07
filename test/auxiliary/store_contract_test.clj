(ns auxiliary.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see every sibling actor's own store-contract
  test for the same pattern."
  (:require [clojure.test :refer [deftest is testing]]
            [auxiliary.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "GA-2026-001" (:case-reference (store/case-file s "case-1"))))
      (is (= :average-adjustment (:case-type (store/case-file s "case-1"))))
      (is (= "GBR" (:jurisdiction (store/case-file s "case-1"))))
      (is (= 3 (count (:interests (store/case-file s "case-1")))))
      (is (= :claims-administration (:case-type (store/case-file s "case-2"))))
      (is (= ["case-1" "case-2" "case-3"] (mapv :id (store/all-cases s))))
      (is (nil? (store/assessment-of s "case-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/recommendation-history s)))
      (is (zero? (store/next-sequence s "GBR"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :case/upsert
                                 :value {:id "case-1" :status :ready}})
        (is (= :ready (:status (store/case-file s "case-1"))))
        (is (= "GA-2026-001" (:case-reference (store/case-file s "case-1"))) "case-reference preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["case-1"]
                                 :payload {:jurisdiction "GBR" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "GBR" :checklist ["a" "b"]} (store/assessment-of s "case-1"))))
      (testing "finalizing an average-adjustment case independently recomputes the apportionment and drafts the record"
        (store/commit-record! s {:effect :recommendation/mark-finalized :path ["case-1"]})
        (is (= "GBR-REC-000000" (get (first (store/recommendation-history s)) "record_id")))
        (is (= "apportionment-draft" (get (first (store/recommendation-history s)) "kind")))
        (is (= :finalized (:status (store/case-file s "case-1"))))
        (is (= 1 (count (store/recommendation-history s))))
        (is (= 1 (store/next-sequence s "GBR")))
        (let [contributions (get (first (store/recommendation-history s)) "contributions")
              by-party (into {} (map (juxt #(get % "party") identity)) contributions)]
          (is (close? 200000.0 (get-in by-party ["Cargo Owner A" "contribution"])))))
      (testing "finalizing a claims-administration case drafts a claims-recommendation record"
        (store/commit-record! s {:effect :assessment/set :path ["case-2"]
                                 :payload {:jurisdiction "ATL" :checklist []}})
        ;; case-2's real jurisdiction is "ATL" (no spec-basis) but store-level
        ;; commit-record! doesn't itself gate on spec-basis -- that's the
        ;; governor's job (see governor_contract_test.clj). This test proves
        ;; only the store's own record-construction/persistence behavior.
        (store/commit-record! s {:effect :recommendation/mark-finalized :path ["case-2"]})
        (is (= "ATL-REC-000000" (get (second (store/recommendation-history s)) "record_id")))
        (is (= "claims-recommendation-draft" (get (second (store/recommendation-history s)) "kind")))
        (is (= :finalized (:status (store/case-file s "case-2"))))
        (is (= 2 (count (store/recommendation-history s)))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/case-file s "nope")))
    (is (= [] (store/all-cases s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/recommendation-history s)))
    (is (zero? (store/next-sequence s "GBR")))
    (store/with-cases s {"x" {:id "x" :case-reference "C-1" :case-type :claims-administration
                              :recommended-amount 0 :jurisdiction "GBR" :status :intake}})
    (is (= "C-1" (:case-reference (store/case-file s "x"))))))
