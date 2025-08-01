(ns metabase.lib.drill-thru.underlying-records-test
  (:require
   #?@(:cljs ([metabase.test-runner.assert-exprs.approximately-equal])
       :clj  ([java-time.api :as t]
              [metabase.util.malli.fn :as mu.fn]))
   [clojure.test :refer [deftest is testing]]
   [medley.core :as m]
   [metabase.lib.core :as lib]
   [metabase.lib.drill-thru :as lib.drill-thru]
   [metabase.lib.drill-thru.test-util :as lib.drill-thru.tu]
   [metabase.lib.drill-thru.test-util.canned :as canned]
   [metabase.lib.join :as lib.join]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.options :as lib.options]
   [metabase.lib.test-metadata :as meta]
   [metabase.lib.test-util :as lib.tu]
   [metabase.lib.test-util.macros :as lib.tu.macros]
   [metabase.lib.test-util.metadata-providers.mock :as providers.mock]
   [metabase.lib.underlying :as lib.underlying]))

#?(:cljs (comment metabase.test-runner.assert-exprs.approximately-equal/keep-me))

(deftest ^:parallel underlying-records-availability-test
  (testing "underlying-records is available for non-header clicks with at least one breakout"
    (canned/canned-test
     :drill-thru/underlying-records
     (fn [test-case context {:keys [click column-kind]}]
        ;; TODO: The docs claim that underlying-records works on pivot cells, and so it does, but the so-called pivot case
        ;; never occurs in actual pivot tables!
        ;; - Clicks on row/column "headers", (that is, breakout values like a month or product category) look like regular
        ;;   cell clicks (column and value set per the breakout, no :dimensions).
        ;; - Clicks on cells (that is, aggregation values) have column, column-ref and value all nil, and :dimensions
        ;;   contains all the breakouts (not exactly 2 as claimed in the spec).
        ;; That all makes sense to me (Braden) and I think this is a bug in the docs, but it also might be a bug in the FE
        ;; code that should be setting the aggregation :value for cell clicks?
        ;; Tech debt issue: #39380
       (and (#{:cell #_:pivot :legend} click)
            (not (:native? test-case))
            (or (seq (:dimensions context))
                (= column-kind :aggregation)))))))

(deftest ^:parallel returns-underlying-records-test-1
  (lib.drill-thru.tu/test-returns-drill
   {:drill-type  :drill-thru/underlying-records
    :click-type  :cell
    :query-type  :aggregated
    :query-kinds [:mbql]
    :column-name "count"
    :expected    {:type :drill-thru/underlying-records, :row-count 77, :table-name "Orders"}}))

(deftest ^:parallel returns-underlying-records-test-2
  (lib.drill-thru.tu/test-returns-drill
   {:drill-type  :drill-thru/underlying-records
    :click-type  :cell
    :query-type  :aggregated
    :query-kinds [:mbql]
    :column-name "sum"
    :expected    {:type :drill-thru/underlying-records, :row-count 1, :table-name "Orders"}}))

(deftest ^:parallel returns-underlying-records-test-3
  (lib.drill-thru.tu/test-returns-drill
   {:drill-type  :drill-thru/underlying-records
    :click-type  :cell
    :query-type  :aggregated
    :query-kinds [:mbql]
    :column-name "max"
    :expected    {:type :drill-thru/underlying-records, :row-count 2, :table-name "Orders"}}))

(deftest ^:parallel returns-underlying-records-test-4-table-name-correct-for-nested-query
  (lib.drill-thru.tu/test-returns-drill
   {:drill-type   :drill-thru/underlying-records
    :click-type   :cell
    :query-type   :aggregated
    :query-kinds [:mbql]
    :column-name  "count"
    :custom-query (-> (lib/query (lib.tu/metadata-provider-with-mock-cards) (:orders (lib.tu/mock-cards)))
                      (lib/aggregate (lib/count))
                      (lib/breakout (lib.metadata/field (lib.tu/metadata-provider-with-mock-cards)
                                                        (meta/id :orders :created-at))))
    :custom-row   {"CREATED_AT" "2023-12-01"
                   "count"      9}
    :expected     {:type :drill-thru/underlying-records, :row-count 9, :table-name "Mock orders card"}}))

(deftest ^:parallel do-not-return-fk-filter-for-non-fk-column-test
  (testing "underlying-records should only get shown once for aggregated query (#34439)"
    (lib.drill-thru.tu/test-available-drill-thrus
     {:click-type  :cell
      :query-type  :aggregated
      :query-kinds [:mbql]
      :column-name "max"
      :expected    #(->> % (map :type) (filter #{:drill-thru/underlying-records}) count (= 1))})))

(deftest ^:parallel returns-underlying-records-for-multi-stage-query-test
  (lib.drill-thru.tu/test-returns-drill
   {:drill-type   :drill-thru/underlying-records
    :click-type   :cell
    :query-type   :aggregated
    :query-kinds  [:mbql]
    :column-name  "count"
    :custom-query #(lib.drill-thru.tu/append-filter-stage % "count")
    :expected     {:type       :drill-thru/underlying-records
                   :row-count  77
                   :table-name "Orders"
                   ;; the "underlying" aggregation ref is reconstructed.
                   :column-ref [:aggregation {:lib/source-name "count"} string?]
                   ;; the "underlying" dimensions are reconstructed from the row.
                   :dimensions [{:column     {:name       "PRODUCT_ID"
                                              :lib/source :source/previous-stage}
                                 :column-ref [:field {} "PRODUCT_ID"]
                                 :value      3}
                                {:column     {:name       "CREATED_AT"
                                              :lib/source :source/previous-stage}
                                 :column-ref [:field {} "CREATED_AT"]
                                 :value      "2022-12-01T00:00:00+02:00"}]}}))

(def ^:private last-month
  #?(:cljs (let [now    (js/Date.)
                 year   (.getFullYear now)
                 month  (.getMonth now)]
             (-> (js/Date.UTC year (dec month))
                 (js/Date.)
                 (.toISOString)))
     :clj  (let [last-month (-> (t/zoned-date-time (t/year) (t/month))
                                (t/minus (t/months 1)))]
             (t/format :iso-offset-date-time last-month))))

(defn- underlying-state [query agg-index agg-value breakout-values exp-filters-fn]
  (let [columns    (lib/returned-columns query)
        aggs       (filter #(= (:lib/source %) :source/aggregations)
                           columns)
        breakouts  (filter :lib/breakout? columns)
        agg-column (nth aggs agg-index)
        agg-dim    {:column     agg-column
                    :column-ref (lib/ref agg-column)
                    :value      agg-value}]
    (is (= (count breakouts)
           (count breakout-values)))
    (let [breakout-dims (for [[breakout value] (map vector breakouts breakout-values)]
                          {:column     breakout
                           :column-ref (lib/ref breakout)
                           :value      value})
          context       (merge agg-dim
                               ;; rows aren't supposed to use `:null`, so change them to `nil` instead.
                               {:row        (for [value (cons agg-dim breakout-dims)]
                                              (update value :value (fn [v]
                                                                     (when-not (= v :null)
                                                                       v))))
                                :dimensions breakout-dims})]
      (is (=? {:lib/type :mbql/query
               :stages   [{:filters     (exp-filters-fn agg-dim breakout-dims)
                           :aggregation (symbol "nil #_\"key is not present.\"")
                           :breakout    (symbol "nil #_\"key is not present.\"")
                           :fields      (symbol "nil #_\"key is not present.\"")}]}
              (->> (lib.drill-thru/available-drill-thrus query context)
                   (m/find-first #(= (:type %) :drill-thru/underlying-records))
                   (lib.drill-thru/drill-thru query -1 nil)))))))

(deftest ^:parallel underlying-records-apply-test
  (testing "sum(subtotal) over time"
    (underlying-state (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                          (lib/aggregate (lib/sum (meta/field-metadata :orders :subtotal)))
                          (lib/breakout (lib/with-temporal-bucket
                                          (meta/field-metadata :orders :created-at)
                                          :month)))
                      0 #_agg-index
                      42295.12
                      [last-month]
                      (fn [_agg-dim [breakout-dim]]
                        [[:= {}
                          (-> (:column-ref breakout-dim)
                              (lib.options/with-options {:temporal-unit :month}))
                          last-month]]))))

(deftest ^:parallel underlying-records-apply-test-2
  (testing "sum_where(subtotal, products.category = \"Doohickey\") over time"
    (underlying-state (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                          (lib/aggregate (lib/sum-where
                                          (meta/field-metadata :orders :subtotal)
                                          (lib/= (meta/field-metadata :products :category)
                                                 "Doohickey")))
                          (lib/breakout (lib/with-temporal-bucket
                                          (meta/field-metadata :orders :created-at)
                                          :month)))
                      0 #_agg-index
                      6572.12
                      [last-month]
                      (fn [_agg-dim [breakout-dim]]
                        [[:= {}
                          (-> (:column-ref breakout-dim)
                              (lib.options/with-options {:temporal-unit :month}))
                          last-month]
                         [:= {} (-> (meta/field-metadata :products :category)
                                    lib/ref
                                    (lib.options/with-options {}))
                          "Doohickey"]]))))

(deftest ^:parallel underlying-records-apply-test-3
  (testing "metric over time"
    (let [metric-id 101
          metric-card {:description "Orders with a subtotal of $100 or more."
                       :lib/type :metadata/card
                       :type :metric
                       :dataset-query (lib.tu.macros/mbql-query orders
                                        {:aggregation  [[:count]]
                                         :filter       [:>= $subtotal 100]})
                       :database-id (meta/id)
                       :name "Large orders"
                       :id metric-id}
          provider (lib/composed-metadata-provider
                    meta/metadata-provider
                    (providers.mock/mock-metadata-provider {:cards [metric-card]}))]
      (underlying-state (-> (lib/query provider (lib.metadata/card provider metric-id))
                            (lib/breakout (lib/with-temporal-bucket
                                            (meta/field-metadata :orders :created-at)
                                            :month)))
                        0 #_agg-index
                        6572.12
                        [last-month]
                        (fn [_agg-dim [breakout-dim]]
                          (let [monthly-breakout (-> (:column-ref breakout-dim)
                                                     (lib.options/with-options {:temporal-unit :month}))]
                            [[:=  {} monthly-breakout last-month]]))))))

(deftest ^:parallel multiple-aggregations-multiple-breakouts-test
  (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                  (lib/aggregate (lib/count))
                  (lib/aggregate (lib/sum (meta/field-metadata :orders :tax)))
                  (lib/aggregate (lib/max (meta/field-metadata :orders :discount)))
                  (lib/breakout  (meta/field-metadata :orders :product-id))
                  (lib/breakout  (lib/with-temporal-bucket (meta/field-metadata :orders :created-at) :month)))]
    (doseq [[agg-index agg-value] [[0 77]
                                   [1 1]
                                   [2 :null]]]
      (underlying-state query agg-index agg-value
                        [120 last-month]
                        (fn [_agg-dim [product-id-dim created-at-dim]]
                          [[:= {}
                            (-> (:column-ref product-id-dim)
                                (lib.options/update-options dissoc :lib/uuid))
                            120]
                           [:= {}
                            (-> (:column-ref created-at-dim)
                                (lib.options/with-options {:temporal-unit :month}))
                            last-month]])))))

(deftest ^:parallel temporal-unit-breakouts-test
  (let [column (-> (meta/field-metadata :orders :created-at)
                   (lib/with-temporal-bucket :day-of-month))]
    (doseq [[types column] {:default column
                            :forced  (-> column
                                         (dissoc :semantic-type)
                                         (assoc :base-type :type/Integer
                                                :effective-type :type/Integer))}
            :let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                            (lib/aggregate (lib/count))
                            (lib/breakout  column))]]
      (testing (str "breakout with temporal-unit with " (name types) " types")
        (let [agg-index 0
              agg-value 96]
          (underlying-state query agg-index agg-value
                            [1]
                            (fn [_agg-dim [created-at-dim]]
                              [[:= {}
                                (-> (:column-ref created-at-dim)
                                    (lib.options/with-options {:temporal-unit :day-of-month}))
                                1]])))))))

(deftest ^:parallel binned-column-test
  (testing "Underlying records for a binned column should generate a filters for current bin's min/max values (#35431)"
    (let [query                    (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                                       (lib/aggregate (lib/count))
                                       (lib/breakout (-> (meta/field-metadata :orders :quantity)
                                                         (lib/with-binning {:strategy :num-bins, :num-bins 10}))))
          col-count                (m/find-first #(= (:name %) "count")
                                                 (lib/returned-columns query))
          _                        (is (some? col-count))
          col-orders-quantity      (m/find-first #(= (:name %) "QUANTITY")
                                                 (lib/returned-columns query))
          _                        (is (some? col-orders-quantity))
          context                  {:column     col-count
                                    :column-ref (lib/ref col-count)
                                    :value      1
                                    :dimensions [{:column     col-orders-quantity
                                                  :column-ref (lib/ref col-orders-quantity)
                                                  :value      20}]}
          available-drills         (lib/available-drill-thrus query context)
          underlying-records-drill (m/find-first #(= (:type %) :drill-thru/underlying-records)
                                                 available-drills)
          _                        (is (some? underlying-records-drill))
          query'                   (lib/drill-thru query underlying-records-drill)]
      (is (=? {:stages [{:lib/type :mbql.stage/mbql
                         :filters  [[:>=
                                     {}
                                     [:field
                                      {:binning (symbol "nil #_\"key is not present.\"")}
                                      (meta/id :orders :quantity)]
                                     20]
                                    [:<
                                     {}
                                     [:field
                                      {:binning (symbol "nil #_\"key is not present.\"")}
                                      (meta/id :orders :quantity)]
                                     30.0]]}]}
              query')))))

(deftest ^:parallel chart-legend-click-test
  (testing "chart legend clicks have no `column` set, but should still work (#35343)"
    (let [query    (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                       (lib/aggregate (lib/count))
                       (lib/breakout (meta/field-metadata :products :category))
                       (lib/breakout (meta/field-metadata :orders :created-at)))
          columns  (lib/returned-columns query)
          category (m/find-first #(= (:name %) "CATEGORY") columns)
          context  {:column     nil
                    :column-ref nil
                    :value      nil
                    :dimensions [{:column     category
                                  :column-ref (lib/ref category)
                                  :value      "Gadget"}]}
          drills   (lib.drill-thru/available-drill-thrus query context)
          drill    (m/find-first #(= (:type %) :drill-thru/underlying-records)
                                 drills)]
      (is (=? {:type       :drill-thru/underlying-records
               :row-count  2
               :table-name "Orders"
               :dimensions [{}]}
              drill))
      (is (=? {:lib/type :mbql/query
               :stages [{:filters     [[:= {} [:field {} (meta/id :products :category)] "Gadget"]]
                         :aggregation (symbol "nil #_\"key is not present.\"")
                         :breakout    (symbol "nil #_\"key is not present.\"")
                         :fields      (symbol "nil #_\"key is not present.\"")}]}
              (lib.drill-thru/drill-thru query drill))))))

(deftest ^:parallel preserve-temporal-bucket-test
  (testing "preserve the temporal bucket on a breakout column in the previous stage (#13504 #36582)"
    (let [base-query     (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                             (lib/aggregate (lib/count))
                             (lib/filter (lib/> (meta/field-metadata :orders :total) 50))
                             (lib/breakout (-> (meta/field-metadata :orders :created-at)
                                               (lib/with-temporal-bucket :month)))
                             lib/append-stage)
          count-col      (m/find-first #(= (:name %) "count")
                                       (lib/returned-columns base-query))
          _              (is (some? count-col))
          query          (lib/filter base-query (lib/> count-col 100))
          created-at-col (m/find-first #(= (:name %) "CREATED_AT")
                                       (lib/returned-columns query))
          context        {:column     count-col
                          :column-ref (lib/ref count-col)
                          :value      127
                          :row        [{:column     created-at-col,
                                        :column-ref (lib/ref created-at-col)
                                        :value      "2023-03-01T00:00:00Z"}
                                       {:column     count-col
                                        :column-ref (lib/ref count-col)
                                        :value      127}]
                          :dimensions [{:column     created-at-col
                                        :column-ref (lib/ref created-at-col)
                                        :value      "2023-03-01T00:00:00Z"}
                                       {:column     count-col
                                        :column-ref (lib/ref count-col)
                                        :value      127}]}
          drill          (m/find-first #(= (:type %) :drill-thru/underlying-records)
                                       (lib/available-drill-thrus query context))]
      (is (some? drill))
      (is (=? {:stages [{:filters [[:> {}
                                    [:field {} (meta/id :orders :total)]
                                    50]
                                   [:= {}
                                    [:field {:temporal-unit :month} (meta/id :orders :created-at)]
                                    "2023-03-01T00:00:00Z"]]}]}
              (lib/drill-thru query drill))))))

(deftest ^:parallel negative-aggregation-values-display-info-test
  (testing "should use the default row count for aggregations with negative values (#36143)"
    (let [query     (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                        (lib/aggregate (lib/count))
                        (lib/breakout (lib.metadata/field (lib.tu/metadata-provider-with-mock-cards)
                                                          (meta/id :orders :created-at))))
          count-col (m/find-first #(= (:name %) "count")
                                  (lib/returned-columns query))
          _         (is (some? count-col))
          context   {:column     count-col
                     :column-ref (lib/ref count-col)
                     :value      -10,
                     :row        [{:column     (meta/field-metadata :orders :created-at)
                                   :column-ref (lib/ref (meta/field-metadata :orders :created-at))
                                   :value      "2020-01-01"}
                                  {:column     count-col
                                   :column-ref (lib/ref count-col)
                                   :value      -10}]
                     :dimensions [{:column     (meta/field-metadata :orders :created-at)
                                   :column-ref (lib/ref (meta/field-metadata :orders :created-at)),
                                   :value      "2020-01-01"}]}
          drill     (m/find-first #(= (:type %) :drill-thru/underlying-records)
                                  (lib/available-drill-thrus query -1 context))]
      (is (=? {:lib/type   :metabase.lib.drill-thru/drill-thru
               :type       :drill-thru/underlying-records
               :row-count  2
               :table-name "Orders"
               :dimensions [{:column     {:name "CREATED_AT"}
                             :column-ref [:field {} (meta/id :orders :created-at)]
                             :value      "2020-01-01"}]
               :column-ref [:aggregation {} string?]}
              drill))
      ;; display info currently doesn't include a `display-name` for drill thrus... we can fix this later.
      (binding #?(:clj [mu.fn/*enforce* false] :cljs [])
        (is (=? {:type       :drill-thru/underlying-records
                 :row-count  2
                 :table-name "Orders"}
                (lib/display-info query -1 drill)))))))

(deftest ^:parallel nil-aggregation-value-binned-test
  (testing "nil dimension value for binned column should return a valid query (#11345 #36581)"
    (let [query        (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                           (lib/aggregate (lib/count))
                           (lib/breakout (-> (meta/field-metadata :orders :discount)
                                             (lib/with-binning {:strategy :default}))))
          count-col    (m/find-first #(= (:name %) "count")
                                     (lib/returned-columns query))
          _            (is (some? count-col))
          discount-col (m/find-first #(= (:name %) "DISCOUNT")
                                     (lib/returned-columns query))
          _            (is (some? discount-col))
          context      {:column     count-col
                        :column-ref (lib/ref count-col)
                        :value      16845
                        :row        [{:column     discount-col
                                      :column-ref (lib/ref discount-col)
                                      :value      nil}
                                     {:column     count-col
                                      :column-ref (lib/ref count-col)
                                      :value      16845}]
                        :dimensions [{:column     discount-col
                                      :column-ref (lib/ref discount-col)
                                      :value      nil}]}
          drill (m/find-first #(= (:type %) :drill-thru/underlying-records)
                              (lib/available-drill-thrus query context))]
      (is (some? drill))
      (is (=? {:stages [{:filters [[:is-null
                                    {}
                                    [:field {:binning (symbol "nil #_\"key is not present.\"")} (meta/id :orders :discount)]]]}]}
              (lib/drill-thru query drill))))))

(deftest ^:parallel nil-aggregation-value-unbinned-test
  (testing "nil dimension value for unbinned column should return an is-null filter (#51751)"
    (let [query        (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                           (lib/aggregate (lib/count))
                           (lib/breakout (meta/field-metadata :products :category)))
          count-col    (m/find-first #(= (:name %) "count")
                                     (lib/returned-columns query))
          _            (is (some? count-col))
          category-col (m/find-first #(= (:name %) "CATEGORY")
                                     (lib/returned-columns query))
          _            (is (some? category-col))
          context      {:column     count-col
                        :column-ref (lib/ref count-col)
                        :value      16845
                        :row        [{:column     category-col
                                      :column-ref (lib/ref category-col)
                                      :value      nil}
                                     {:column     count-col
                                      :column-ref (lib/ref count-col)
                                      :value      16845}]
                        :dimensions [{:column     category-col
                                      :column-ref (lib/ref category-col)
                                      :value      nil}]}
          drill (m/find-first #(= (:type %) :drill-thru/underlying-records)
                              (lib/available-drill-thrus query context))]
      (is (some? drill))
      (is (=? {:stages [{:filters [[:is-null {} [:field {} (meta/id :products :category)]]]}]}
              (lib/drill-thru query drill))))))

(deftest ^:parallel multi-stage-query-test
  (testing "sum_where(subtotal, products.category = \"Doohickey\") over time with appended filter stage"
    (let [base-query     (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                             (lib/aggregate (lib/sum-where
                                             (meta/field-metadata :orders :subtotal)
                                             (lib/= (meta/field-metadata :products :category)
                                                    "Doohickey")))
                             (lib/breakout (lib/with-temporal-bucket
                                             (meta/field-metadata :orders :created-at)
                                             :month))
                             lib/append-stage)
          sum-where-col  (m/find-first #(= (:name %) "sum_where_SUBTOTAL")
                                       (lib/returned-columns base-query))
          _              (is (some? sum-where-col))
          created-at-col (m/find-first #(= (:name %) "CREATED_AT")
                                       (lib/returned-columns base-query))
          _              (is (some? created-at-col))
          query          (lib/filter base-query (lib/> sum-where-col 0))
          context        {:column     sum-where-col
                          :column-ref (lib/ref sum-where-col)
                          :value      16845
                          :row        [{:column     sum-where-col
                                        :column-ref (lib/ref sum-where-col)
                                        :value      16845}
                                       {:column     created-at-col
                                        :column-ref (lib/ref created-at-col)
                                        :value      "2023-12-01"}]}
          drill          (m/find-first #(= (:type %) :drill-thru/underlying-records)
                                       (lib/available-drill-thrus query context))]
      (is (=? {:lib/type   :metabase.lib.drill-thru/drill-thru
               :type       :drill-thru/underlying-records
               :row-count  16845
               :table-name "Orders"
               :dimensions [{:column     {:name "CREATED_AT"}
                             :column-ref [:field {} "CREATED_AT"]
                             :value      "2023-12-01"}]
               :column-ref [:aggregation {:lib/source-name "sum_where_SUBTOTAL"} string?]}
              drill))
      (is (=? {:lib/type :mbql/query
               :stages   [{:filters     [[:= {}
                                          (-> (meta/field-metadata :orders :created-at)
                                              lib/ref
                                              (lib.options/with-options {}))
                                          "2023-12-01"]
                                         [:= {} (-> (meta/field-metadata :products :category)
                                                    lib/ref
                                                    (lib.options/with-options {}))
                                          "Doohickey"]]
                           :aggregation (symbol "nil #_\"key is not present.\"")
                           :breakout    (symbol "nil #_\"key is not present.\"")
                           :fields      (symbol "nil #_\"key is not present.\"")}]}
              (lib/drill-thru query drill))))))

(deftest ^:parallel include-all-joined-columns-test
  (testing "underlying records for a query with a join should include all fields from the join (#48032)"
    (let [query        (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                           (lib/join (meta/table-metadata :people))
                           (lib/aggregate (lib/count))
                           (lib/breakout (-> (meta/field-metadata :orders :discount)
                                             (lib/with-binning {:strategy :default}))))
          count-col    (m/find-first #(= (:name %) "count")
                                     (lib/returned-columns query))
          _            (is (some? count-col))
          discount-col (m/find-first #(= (:name %) "DISCOUNT")
                                     (lib/returned-columns query))
          _            (is (some? discount-col))
          context      {:column     count-col
                        :column-ref (lib/ref count-col)
                        :value      16845
                        :row        [{:column     discount-col
                                      :column-ref (lib/ref discount-col)
                                      :value      nil}
                                     {:column     count-col
                                      :column-ref (lib/ref count-col)
                                      :value      16845}]
                        :dimensions [{:column     discount-col
                                      :column-ref (lib/ref discount-col)
                                      :value      nil}]}
          drill (m/find-first #(= (:type %) :drill-thru/underlying-records)
                              (lib/available-drill-thrus query context))]
      (is (some? drill))
      (is (=? :all
              (-> query
                  (lib/drill-thru drill)
                  lib.join/joins
                  first
                  lib.join/join-fields))))))

(deftest ^:parallel breakout-by-expression-test
  (testing "underlying records for a query with a breakout on an expression should produce a correct ref (#59005)"
    (let [base               (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                                 (lib/expression "cost bucket" (lib/floor (lib// (meta/field-metadata :orders :subtotal) 10)))
                                 (lib/aggregate (lib/count)))
          cols               (lib/breakoutable-columns base)
          bucket-expr        (m/find-first #(= (:name %) "cost bucket") cols)
          query              (lib/breakout base bucket-expr)
          [bucket count-col] (lib/returned-columns query)
          bucket-dim         {:column     (dissoc bucket :lib/expression-name)
                              :column-ref (lib/ref bucket)
                              :value      "12"}
          count-dim          {:column     count-col
                              :column-ref (lib/ref count-col)
                              :value      81}
          context            (assoc count-dim
                                    :row [bucket-dim count-dim]
                                    :dimensions [bucket-dim])
          drill              (m/find-first #(= (:type %) :drill-thru/underlying-records)
                                           (lib/available-drill-thrus query context))]
      (is (some? drill))
      (testing "top-level-query is a no-op for a one-stage query"
        (is (identical? query (lib.underlying/top-level-query query))))
      (testing "top-level-column is a no-op for a top-level breakout"
        (is (identical? (:column bucket-dim)
                        (lib.underlying/top-level-column query (:column bucket-dim)))))
      (is (=? [[:= {} [:expression {} "cost bucket"] "12"]]
              (lib/filters (lib/drill-thru query drill)))))))
