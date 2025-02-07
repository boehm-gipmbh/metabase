(ns metabase.pivot.core
  (:require
   #?(:clj [metabase.util.json :as json])
   [flatland.ordered.map :as ordered-map]
   [medley.core :as m]
   [metabase.util :as u]))

(defn is-pivot-group-column
  "Is the given column the pivot-grouping column?"
  [col]
  (= (:name col) "pivot-grouping"))

;; TODO: Remove these JSON helpers once more logic is encapsualted in CLJC and we don't need to use
;; JSON-encoded keys in maps.
(defn- to-key
  [x]
  #?(:cljs (js/JSON.stringify (clj->js x))
     :clj x))

(defn- from-key
  [x]
  #?(:cljs (js->clj (js/JSON.parse (clj->js x)))
     :clj x))

(defn- json-parse
  [x]
  #?(:cljs (js->clj (js/JSON.parse (clj->js x)))
     :clj (json/decode x)))

(defn split-pivot-data
  "Pulls apart different aggregations that were packed into one result set returned from the QP.
  The pivot-grouping column indicates which breakouts were used to compute a given row. We used that column
  to split apart the data and convert field refs to indices"
  [data]
  (let [group-index (u/index-of is-pivot-group-column (:cols data))
        columns     (filter #(not (is-pivot-group-column %)) (:cols data))
        breakouts   (filter #(= (:source %) "breakout") columns)
        pivot-data  (->> (:rows data)
                         (group-by #(nth % group-index))
                         ;; TODO: Make this logic more understandable
                         (m/map-kv
                          (fn [k rows]
                            (let [breakout-indexes (range (count breakouts))
                                  indexes (into [] (filter #(zero? (bit-and (bit-shift-left 1 %) k)) breakout-indexes))]
                              [(to-key indexes)
                               (map #(vec (concat (subvec % 0 group-index) (subvec % (inc group-index))))
                                    rows)]))))]
    {:pivot-data pivot-data
     :columns columns}))

(defn subtotal-values
  "Returns subtotal values"
  [pivot-data value-column-indexes]
  (m/map-kv-vals
   (fn [subtotalName subtotal]
     (let [indexes (from-key subtotalName)]
       (reduce
        (fn [acc row]
          (let [value-key (to-key (map #(nth row %) indexes))]
            (assoc acc
                   value-key
                   (map #(nth row %) value-column-indexes))))
        {}
        subtotal)))
   pivot-data))

(defn- collapse-level
  "Marks all nodes at the given level as collapsed. 1 = root node; 2 = children
  of the root, etc."
  [tree level]
  (m/map-vals
   (if (= level 1)
     #(assoc % :isCollapsed true)
     #(update % :children (fn [subtree] (collapse-level subtree (dec level)))))
   tree))

(defn- add-is-collapsed
  "Annotates a row tree with :isCollapsed values, based on the contents of
  collapsed-subtotals"
  [tree collapsed-subtotals]
  (let [parsed-collapsed-subtotals (map json-parse collapsed-subtotals)]
    ;; e.g. ["Widget","Odessa Emmerich Inc",4.5]
    (reduce
     (fn [tree collapsed-subtotal]
       (cond
         ;; A plain integer represents an entire level of the tree which is
         ;; collapsed (1-indexed)
         (int? collapsed-subtotal)
         (collapse-level tree collapsed-subtotal)

         ;; A seq represents a specific path in the tree which is collapsed
         (sequential? collapsed-subtotal)
         (let [key-path (conj (into [] (interpose :children collapsed-subtotal))
                              :isCollapsed)]
           (assoc-in tree key-path true))))
     tree
     parsed-collapsed-subtotals)))

(defn- add-path-to-tree
  "Adds a path of values to a row or column tree. Each level of the tree is an
  ordered map with values as keys, each associated with a sub-map like
  {:children (ordered-map/ordered-map)}."
  [path tree]
  (if (seq path)
    (let [v       (first path)
          subtree (or (get-in tree [v :children]) (ordered-map/ordered-map))]
      (-> tree
          (assoc-in [v :children] (add-path-to-tree (rest path) subtree))
          (assoc-in [v :isCollapsed] false)))
    tree))

(defn- select-indexes
  "Given a row, "
  [row indexes]
  (map #(nth row %) indexes))

(defn build-values-by-key
  "Replicate valuesByKey construction"
  [rows cols row-indexes col-indexes val-indexes]
  (let [col-and-row-indexes (concat col-indexes row-indexes)]
    (reduce
     (fn [acc row]
       (let [value-key  (to-key (select-indexes row col-and-row-indexes))
             values     (select-indexes row val-indexes)
             ;; @tsp - this now assumes that cols is indexed the same as the row
             data       (map-indexed
                         (fn [index value]
                           {:value value
                            :colIdx index})
                            ;;:col (nth cols index)})
                         row)
             ;; @tsp TODO? this could use the `data` above
             dimensions (->> row
                             (map-indexed (fn [index value]
                                            {:value value
                                             :colIdx index}))
                             (filter (fn [tmp]
                                       (= ((nth cols (:colIdx tmp)) "source") "breakout"))))]
         (assoc acc
                value-key
                {:values values
                 ;;:valueColumns value-cols
                 :data data
                 :dimensions dimensions})))
     {}
     rows)))

(defn- sort-orders-from-settings
  [col-settings indexes]
  (->> (map col-settings indexes)
       (map :pivot_table.column_sort_order)))

(defn- compare-fn
  [sort-order]
  (case (keyword sort-order)
    :ascending  compare
    :descending #(compare %2 %1)
    nil))

(defn- sort-tree
  "Converts each level of a tree to a sorted map as needed, based on the values
  in `sort-orders`."
  [tree sort-orders]
  (let [curr-compare-fn (compare-fn (first sort-orders))]
    (into
     (if curr-compare-fn
       (sorted-map-by curr-compare-fn)
       (ordered-map/ordered-map))
     (for [[k v] tree]
       [k (assoc v :children (sort-tree (:children v) (rest sort-orders)))]))))

(defn build-pivot-trees
  "TODO"
  [rows cols row-indexes col-indexes val-indexes col-settings collapsed-subtotals]
  (let [start (js/Date.now)
        {:keys [row-tree col-tree]}
        (reduce
         (fn [{:keys [row-tree col-tree]} row]
           (let [row-path (select-indexes row row-indexes)
                 col-path (select-indexes row col-indexes)]
             {:row-tree (add-path-to-tree row-path row-tree)
              :col-tree (add-path-to-tree col-path col-tree)}))
         {:row-tree (ordered-map/ordered-map)
          :col-tree (ordered-map/ordered-map)}
         rows)
        collapsed-row-tree (add-is-collapsed row-tree collapsed-subtotals)
        row-sort-orders (sort-orders-from-settings col-settings row-indexes)
        col-sort-orders (sort-orders-from-settings col-settings col-indexes)
        sorted-row-tree (sort-tree collapsed-row-tree row-sort-orders)
        sorted-col-tree (sort-tree col-tree col-sort-orders)
        values-by-key   (build-values-by-key rows cols row-indexes col-indexes val-indexes)
        end (js/Date.now)]
    (println "TSP build-pivot-trees took: " (- end start) "ms")
    {:row-tree sorted-row-tree
     :col-tree sorted-col-tree
     :values-by-key values-by-key}))

(defn format-values-in-tree
  "Formats values in a row or column tree according to `formatters`"
  [tree formatters cols]
  (let [formatter (first formatters)
        col       (first cols)]
    (map
     (fn [{:keys [value children] :as node}]
       (assoc node
              :value (formatter value)
              :children (format-values-in-tree children (rest formatters) (rest cols))
              :rawValue value
              :clicked {:value value
                        :column col
                        :data [{:value value
                                :col col}]}))
     tree)))

(defn add-subtotal
  "TODO"
  [row-item show-subs-by-col should-show-subtotal]
  (let [is-subtotal-enabled (first show-subs-by-col)
        rest-subs-by-col    (rest show-subs-by-col)
        has-subtotal        (and is-subtotal-enabled should-show-subtotal)
        subtotal            (if has-subtotal
                              [{:value (str "Totals for " (:value row-item))
                                :rawValue (:rawValue row-item)
                                :span 1
                                :isSubtotal true
                                :children []
                                }]
                              [])]
    (if (:isCollapsed row-item)
      subtotal
      (let [node (merge row-item
                        {:hasSubtotal has-subtotal
                         :children (mapcat (fn [child] (if (not-empty (:children child))
                                                      (add-subtotal child
                                                                    rest-subs-by-col
                                                                    (or (> (count (:children child)) 1)
                                                                        (:isCollapsed child)))
                                                      [child]))
                                        (:children row-item))})]
        (if (not-empty subtotal)
          [node (first subtotal)]
          [node])))))

(defn add-subtotals
  "Adds subtotals to the row tree if needed, based on column settings."
  [row-tree row-indexes col-settings]
  (let [show-subs-by-col (map (fn [idx]
                                (not= ((nth col-settings idx) :pivot_table.column_show_totals) false))
                              row-indexes)
        not-flat         (some #(> (count (:children %)) 1) row-tree)
        res              (mapcat (fn [row-item] (add-subtotal row-item show-subs-by-col (or not-flat (> (count (:children row-item)) 1)))) row-tree)]
    res))

(comment
;;function addValueColumnNodes(nodes, valueColumns) {
;;  const leafNodes = valueColumns.map(([column, columnSettings]) => {
;;    return {
;;      value: columnSettings.column_title || displayNameForColumn(column),
;;      children: [],
;;      isValueColumn: true,
;;    };
;;  });
;;  if (nodes.length === 0) {
;;    return leafNodes;
;;  }
;;  if (valueColumns.length <= 1) {
;;    return nodes;
;;  }
;;  function updateNode(node) {
;;    const children =
;;      node.children.length === 0 ? leafNodes : node.children.map(updateNode);
;;    return { ...node, children };
;;  }
;;  return nodes.map(updateNode);
;;}
)

(defn- display-name-for-col
  "@tsp - ripped from frontend/src/metabase/lib/formatting/column.ts"
  [column]
  (or (:display_name (:remapped_to_column column))
   (:display_name column)
   "(empty)"))

(defn- update-node
  "TODO"
  [node leaf-nodes]
  (let [new-children (if (empty? (:children node))
                       leaf-nodes
                       (map #(update-node % leaf-nodes) (:children node)))]
    (merge node {:children new-children})))

(defn add-value-column-nodes
  "TODO"
  [col-tree col-indexes col-settings]
  (let [val-cols (map (fn [idx] [(:column (nth col-settings idx)) (nth col-settings idx)]) col-indexes)
        leaf-nodes (map (fn [[col col-setting]] {:value (or (:column_title col-setting) (display-name-for-col col))
                                                 :children []
                                                 :isValueColumn true})
                        val-cols)]
    (if (empty? col-tree)
      leaf-nodes)
    (if (<= (count val-cols) 1)
      col-tree)
    (map #(update-node % leaf-nodes) col-tree)))

