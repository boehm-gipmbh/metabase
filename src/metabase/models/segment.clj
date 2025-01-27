(ns metabase.models.segment
  "A Segment is a saved MBQL 'macro', expanding to a `:filter` subclause. It is passed in as a `:filter` subclause but is
  replaced by the `expand-macros` middleware with the appropriate clauses."
  (:require
   [clojure.set :as set]
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.legacy-mbql.util :as mbql.u]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata.jvm :as lib.metadata.jvm]
   [metabase.lib.metadata.protocols :as lib.metadata.protocols]
   [metabase.lib.query :as lib.query]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.lib.schema.metadata :as lib.schema.metadata]
   [metabase.models.audit-log :as audit-log]
   [metabase.models.data-permissions :as data-perms]
   [metabase.models.database :as database]
   [metabase.models.interface :as mi]
   [metabase.models.revision :as revision]
   [metabase.models.serialization :as serdes]
   [metabase.search.core :as search]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [methodical.core :as methodical]
   [toucan2.core :as t2]
   [toucan2.tools.hydrate :as t2.hydrate]))

(methodical/defmethod t2/table-name :model/Segment [_model] :segment)
(methodical/defmethod t2/model-for-automagic-hydration [:default :segment] [_original-model _k] :model/Segment)

(t2/deftransforms :model/Segment
  {:definition mi/transform-legacy-metric-segment-definition})

(doto :model/Segment
  (derive :metabase/model)
  (derive :hook/timestamped?)
  (derive :hook/entity-id)
  (derive ::mi/write-policy.superuser)
  (derive ::mi/create-policy.superuser))

(defmethod mi/can-read? :model/Segment
  ([instance]
   (let [table (:table (t2/hydrate instance :table))]
     (data-perms/user-has-permission-for-table?
      api/*current-user-id*
      :perms/manage-table-metadata
      :yes
      (:db_id table)
      (u/the-id table))))
  ([model pk]
   (mi/can-read? (t2/select-one model pk))))

(t2/define-before-update :model/Segment  [{:keys [creator_id id], :as segment}]
  (u/prog1 (t2/changes segment)
    ;; throw an Exception if someone tries to update creator_id
    (when (contains? <> :creator_id)
      (when (not= (:creator_id <>) (t2/select-one-fn :creator_id :model/Segment :id id))
        (throw (UnsupportedOperationException. (tru "You cannot update the creator_id of a Segment.")))))))

(defmethod mi/perms-objects-set :model/Segment
  [segment read-or-write]
  (let [table (or (:table segment)
                  (t2/select-one ['Table :db_id :schema :id] :id (u/the-id (:table_id segment))))]
    (mi/perms-objects-set table read-or-write)))

(mu/defn- definition-description :- [:maybe ::lib.schema.common/non-blank-string]
  "Calculate a nice description of a Segment's definition."
  [metadata-provider                                      :- ::lib.schema.metadata/metadata-provider
   {table-id :table_id, :keys [definition], :as _segment} :- (ms/InstanceOf :model/Segment)]
  (when (seq definition)
    (try
      (let [definition  (merge {:source-table table-id}
                               definition)
            database-id (u/the-id (lib.metadata.protocols/database metadata-provider))
            query       (lib.query/query-from-legacy-inner-query metadata-provider database-id definition)]
        (lib/describe-top-level-key query :filters))
      (catch Throwable e
        (log/errorf e "Error calculating Segment description: %s" (ex-message e))
        nil))))

(mu/defn- warmed-metadata-provider :- ::lib.schema.metadata/metadata-provider
  [database-id :- ::lib.schema.id/database
   segments    :- [:maybe [:sequential (ms/InstanceOf :model/Segment)]]]
  (let [metadata-provider (doto (lib.metadata.jvm/application-database-metadata-provider database-id)
                            (lib.metadata.protocols/store-metadatas!
                             (map #(lib.metadata.jvm/instance->metadata % :metadata/segment)
                                  segments)))
        field-ids         (mbql.u/referenced-field-ids (map :definition segments))
        fields            (lib.metadata.protocols/metadatas metadata-provider :metadata/column field-ids)
        table-ids         (into #{}
                                cat
                                [(map :table-id fields)
                                 (map :table_id segments)])]
    ;; this is done for side effects
    (lib.metadata.protocols/warm-cache metadata-provider :metadata/table table-ids)
    metadata-provider))

(mu/defn- segments->table-id->warmed-metadata-provider :- fn?
  [segments :- [:maybe [:sequential (ms/InstanceOf :model/Segment)]]]
  (let [table-id->db-id             (when-let [table-ids (not-empty (into #{} (map :table_id segments)))]
                                      (t2/select-pk->fn :db_id :model/Table :id [:in table-ids]))
        db-id->metadata-provider    (memoize
                                     (mu/fn db-id->warmed-metadata-provider :- ::lib.schema.metadata/metadata-provider
                                       [database-id :- ::lib.schema.id/database]
                                       (let [segments-for-db (filter (fn [segment]
                                                                       (= (table-id->db-id (:table_id segment))
                                                                          database-id))
                                                                     segments)]
                                         (warmed-metadata-provider database-id segments-for-db))))]
    (mu/fn table-id->warmed-metadata-provider :- ::lib.schema.metadata/metadata-provider
      [table-id :- ::lib.schema.id/table]
      (-> table-id table-id->db-id db-id->metadata-provider))))

(methodical/defmethod t2.hydrate/batched-hydrate [:model/Segment :definition_description]
  [_model _key segments]
  (let [table-id->warmed-metadata-provider (segments->table-id->warmed-metadata-provider segments)]
    (for [segment segments
          :let    [metadata-provider (table-id->warmed-metadata-provider (:table_id segment))]]
      (assoc segment :definition_description (definition-description metadata-provider segment)))))

;;; --------------------------------------------------- Revisions ----------------------------------------------------

(defmethod revision/serialize-instance :model/Segment
  [_model _id instance]
  (dissoc instance :created_at :updated_at))

(defmethod revision/diff-map :model/Segment
  [model segment1 segment2]
  (if-not segment1
    ;; this is the first version of the segment
    (m/map-vals (fn [v] {:after v}) (select-keys segment2 [:name :description :definition]))
    ;; do our diff logic
    (let [base-diff ((get-method revision/diff-map :default)
                     model
                     (select-keys segment1 [:name :description :definition])
                     (select-keys segment2 [:name :description :definition]))]
      (cond-> (merge-with merge
                          (m/map-vals (fn [v] {:after v}) (:after base-diff))
                          (m/map-vals (fn [v] {:before v}) (:before base-diff)))
        (or (get-in base-diff [:after :definition])
            (get-in base-diff [:before :definition])) (assoc :definition {:before (get segment1 :definition)
                                                                          :after  (get segment2 :definition)})))))

;;; ------------------------------------------------ Serialization ---------------------------------------------------

(defmethod serdes/hash-fields :model/Segment
  [_segment]
  [:name (serdes/hydrated-hash :table) :created_at])

(defmethod serdes/dependencies "Segment" [{:keys [definition table_id]}]
  (set/union #{(serdes/table->path table_id)}
             (serdes/mbql-deps definition)))

(defmethod serdes/storage-path "Segment" [segment _ctx]
  (let [{:keys [id label]} (-> segment serdes/path last)]
    (-> segment
        :table_id
        serdes/table->path
        serdes/storage-path-prefixes
        (concat ["segments" (serdes/storage-leaf-file-name id label)]))))

(defmethod serdes/make-spec "Segment" [_model-name _opts]
  {:copy      [:name :points_of_interest :archived :caveats :description :entity_id :show_in_getting_started]
   :skip      []
   :transform {:created_at (serdes/date)
               :table_id   (serdes/fk :model/Table)
               :creator_id (serdes/fk :model/User)
               :definition {:export serdes/export-mbql :import serdes/import-mbql}}})

;;; ---------------------------------------------- Audit Log Table ----------------------------------------------------

(defmethod audit-log/model-details :model/Segment
  [metric _event-type]
  (let [table-id (:table_id metric)
        db-id    (database/table-id->database-id table-id)]
    (assoc
     (select-keys metric [:name :description :revision_message])
     :table_id    table-id
     :database_id db-id)))

;;;; ------------------------------------------------- Search ----------------------------------------------------------

(search/define-spec "segment"
  {:model        :model/Segment
   :attrs        {:archived      true
                  :collection-id false
                  :creator-id    false
                  :database-id   :table.db_id
                  ;; should probably change this, but will break legacy search tests
                  :created-at    false
                  :updated-at    true}
   :search-terms [:name :description]
   :render-terms {:table-id          :table_id
                  :table_description :table.description
                  :table_name        :table.name
                  :table_schema      :table.schema}
   :joins        {:table [:model/Table [:= :table.id :this.table_id]]}})
