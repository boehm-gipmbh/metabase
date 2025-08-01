(ns metabase.actions.models
  (:require
   [medley.core :as m]
   [metabase.models.interface :as mi]
   [metabase.models.serialization :as serdes]
   [metabase.queries.models.query :as query]
   [metabase.search.core :as search]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]
   [metabase.util.malli :as mu]
   [methodical.core :as methodical]
   [toucan2.core :as t2]
   [toucan2.tools.hydrate :as t2.hydrate]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------- Entity & Life Cycle ----------------------------------------------

(methodical/defmethod t2/table-name :model/Action [_model] :action)
(methodical/defmethod t2/table-name :model/QueryAction [_model] :query_action)
(methodical/defmethod t2/table-name :model/HTTPAction [_model] :http_action)
(methodical/defmethod t2/table-name :model/ImplicitAction [_model] :implicit_action)

(def ^:private action-sub-models [:model/QueryAction :model/HTTPAction :model/ImplicitAction])

(doto :model/Action
  (derive :metabase/model)
  ;;; You can read/write an Action if you can read/write its model (Card)
  (derive ::mi/read-policy.full-perms-for-perms-set)
  (derive ::mi/write-policy.full-perms-for-perms-set)
  (derive :hook/entity-id)
  (derive :hook/timestamped?))

(doseq [model action-sub-models]
  (derive model :metabase/model))

(derive :model/QueryAction :hook/search-index)

(methodical/defmethod t2/primary-keys :model/QueryAction    [_model] [:action_id])
(methodical/defmethod t2/primary-keys :model/HTTPAction     [_model] [:action_id])
(methodical/defmethod t2/primary-keys :model/ImplicitAction [_model] [:action_id])

(def ^:private transform-action-visualization-settings
  {:in  mi/json-in
   :out (comp (fn [viz-settings]
                ;; the keys of :fields should be strings, not keywords
                (m/update-existing viz-settings :fields update-keys name))
              mi/json-out-with-keywordization)})

(t2/deftransforms :model/Action
  {:type                   mi/transform-keyword
   :parameter_mappings     mi/transform-parameters-list
   :parameters             mi/transform-card-parameters-list
   :visualization_settings transform-action-visualization-settings})

(t2/deftransforms :model/QueryAction
  ;; shouldn't this be mi/transform-metabase-query?
  {:dataset_query mi/transform-json})

(def ^:private transform-json-with-nested-parameters
  {:in  (comp mi/json-in
              (fn [template]
                (u/update-if-exists template :parameters mi/normalize-parameters-list)))
   :out (comp (fn [template]
                (u/update-if-exists template :parameters (mi/catch-normalization-exceptions mi/normalize-parameters-list)))
              mi/json-out-with-keywordization)})

(t2/deftransforms :model/HTTPAction
  {:template transform-json-with-nested-parameters})

(methodical/defmethod t2/batched-hydrate [:model/Action :model]
  [_model k actions]
  (mi/instances-with-hydrated-data
   actions k
   #(t2/select-pk->fn identity :model/Card :id [:in (map :model_id actions)])
   :model_id))

(defn- check-model-is-not-a-saved-question
  [model-id]
  (when-not (= (t2/select-one-fn :type [:model/Card :type :card_schema] :id model-id) :model)
    (throw (ex-info (tru "Actions must be made with models, not cards.")
                    {:status-code 400}))))

(t2/define-before-insert :model/Action
  [{model-id :model_id, :as action}]
  (u/prog1 action
    (check-model-is-not-a-saved-question model-id)))

(t2/define-before-update :model/Action
  [{archived? :archived, id :id, model-id :model_id, :as changes}]
  (u/prog1 changes
    (if archived?
      (t2/delete! :model/DashboardCard :action_id id)
      (check-model-is-not-a-saved-question model-id))))

(mu/defmethod mi/perms-objects-set :model/Action :- [:set {:min 1} :string]
  [instance      :- [:map
                     [:model_id pos-int?]]
   read-or-write :- [:enum :read :write]]
  (mi/perms-objects-set (t2/select-one :model/Card :id (:model_id instance)) read-or-write))

(def action-columns
  "The columns that are common to all Action types."
  [:archived :created_at :creator_id :description :entity_id :made_public_by_id :model_id :name :parameter_mappings
   :parameters :public_uuid :type :updated_at :visualization_settings])

(defn type->model
  "Returns the model from an action type.
   `action-type` can be a string or a keyword."
  [action-type]
  (case action-type
    :http     :model/HTTPAction
    :implicit :model/ImplicitAction
    :query    :model/QueryAction))

;;; ------------------------------------------------ CRUD fns -----------------------------------------------------

(defn insert!
  "Inserts an Action and related type table. Returns the action id."
  [action-data]
  (t2/with-transaction [_conn]
    (let [action (first (t2/insert-returning-instances! :model/Action (select-keys action-data action-columns)))
          model  (type->model (:type action))]
      (t2/query-one {:insert-into (t2/table-name model)
                     :values [(-> (apply dissoc action-data action-columns)
                                  (assoc :action_id (:id action))
                                  (cond-> (= (:type action) :implicit)
                                    (dissoc :database_id)
                                    (= (:type action) :http)
                                    (update :template json/encode)
                                    (= (:type action) :query)
                                    (update :dataset_query json/encode)))]})
      (:id action))))

(defn update!
  "Updates an Action and the related type table.
   Deletes the old type table row if the type has changed."
  [{:keys [id] :as action} existing-action]
  (when-let [action-row (not-empty (select-keys action action-columns))]
    (t2/update! :model/Action id action-row))
  (when-let [type-row (not-empty (cond-> (apply dissoc action :id action-columns)
                                   (= (or (:type action) (:type existing-action))
                                      :implicit)
                                   (dissoc :database_id)))]
    (let [type-row (assoc type-row :action_id id)
          existing-model (type->model (:type existing-action))]
      (if (and (:type action) (not= (:type action) (:type existing-action)))
        (let [new-model (type->model (:type action))]
          (t2/delete! existing-model :action_id id)
          (t2/insert! new-model (assoc type-row :action_id id)))
        (t2/update! existing-model id type-row)))))

(defn- normalize-query-actions [actions]
  (when (seq actions)
    (let [query-actions (t2/select :model/QueryAction :action_id [:in (map :id actions)])
          action-id->query-actions (m/index-by :action_id query-actions)]
      (for [action actions]
        (merge action (-> action :id action-id->query-actions (dissoc :action_id)))))))

(defn- normalize-http-actions [actions]
  (when (seq actions)
    (let [http-actions (t2/select :model/HTTPAction :action_id [:in (map :id actions)])
          http-actions-by-action-id (m/index-by :action_id http-actions)]
      (map (fn [action]
             (let [http-action (get http-actions-by-action-id (:id action))]
               (-> action
                   (merge
                    {:disabled false}
                    (select-keys http-action [:template :response_handle :error_handle])
                    (select-keys (:template http-action) [:parameters :parameter_mappings])))))
           actions))))

(defn- normalize-implicit-actions [actions]
  (when (seq actions)
    (let [implicit-actions (t2/select :model/ImplicitAction :action_id [:in (map :id actions)])
          implicit-actions-by-action-id (m/index-by :action_id implicit-actions)]
      (map (fn [action]
             (let [implicit-action (get implicit-actions-by-action-id (:id action))]
               (merge action
                      (select-keys implicit-action [:kind]))))
           actions))))

(defn- select-actions-without-implicit-params
  "Select Actions and fill in sub type information. Don't use this if you need implicit parameters
   for implicit actions, use [[select-action]] instead.
   `options` is passed to `t2/select` `& options` arg."
  [& options]
  (let [{:keys [query http implicit]} (group-by :type (apply t2/select :model/Action options))
        query-actions                 (normalize-query-actions query)
        http-actions                  (normalize-http-actions http)
        implicit-actions              (normalize-implicit-actions implicit)]
    (sort-by :updated_at (concat query-actions http-actions implicit-actions))))

(defn unique-field-slugs?
  "Makes sure that if `coll` is indexed by `index-by`, no keys will be in conflict."
  [fields]
  (empty? (m/filter-vals #(not= % 1) (frequencies (map (comp u/slugify :name) fields)))))

(defn- implicit-action-parameters
  "Returns a map of card-id -> implicit-parameters for the given models"
  [cards]
  (let [card-by-table-id (into {}
                               (for [card cards
                                     :let [{:keys [table-id]} (query/query->database-and-table-ids (:dataset_query card))]
                                     :when table-id]
                                 [table-id card]))
        tables (when-let [table-ids (seq (keys card-by-table-id))]
                 (t2/hydrate (t2/select 'Table :id [:in table-ids]) :fields))]
    (into {}
          (for [table tables
                :let [fields (:fields table)]
                ;; Skip tables for have conflicting slugified columns i.e. table has "name" and "NAME" columns.
                :when (unique-field-slugs? fields)
                :let [card         (get card-by-table-id (:id table))
                      id->metadata (m/index-by :id (:result_metadata card))
                      parameters (->> fields
                                      ;; get display_name from metadata
                                      (keep (fn [field]
                                              (when-let [metadata (id->metadata (:id field))]
                                                (assoc field :display_name (:display_name metadata)))))
                                      ;; remove exploded json fields and any structured field
                                      (remove (some-fn
                                               ;; exploded json fields can't be recombined in sql yet
                                               :nfc_path
                                               ;; their parents, a json field, nor things like cidr, macaddr, xml, etc
                                               (comp #(isa? % :type/Structured) :effective_type)
                                               ;; or things which we don't recognize
                                               (comp #{:type/*} :effective_type)))
                                      (map (fn [field]
                                             {:id (u/slugify (:name field))
                                              :display-name (:display_name field)
                                              :target [:variable [:template-tag (u/slugify (:name field))]]
                                              :type (:base_type field)
                                              :required (:database_required field)
                                              :is-auto-increment (:database_is_auto_increment field)
                                              ::field-id (:id field)
                                              ::pk? (isa? (:semantic_type field) :type/PK)})))]]
            [(:id card) parameters]))))

(defn select-actions
  "Find actions with given options and generate implicit parameters for execution. Also adds the `:database_id` of the
   model for implicit actions.

   Pass in known-models to save a second Card lookup."
  [known-models & options]
  (let [actions                       (apply select-actions-without-implicit-params options)
        implicit-action-model-ids     (set (map :model_id (filter #(= :implicit (:type %)) actions)))
        implicit-action-models        (if known-models
                                        (->> known-models
                                             (filter #(contains? implicit-action-model-ids (:id %)))
                                             distinct)
                                        (when (seq implicit-action-model-ids)
                                          (t2/select :model/Card :id [:in implicit-action-model-ids])))
        model-id->db-id               (into {} (for [card implicit-action-models]
                                                 [(:id card) (:database_id card)]))
        model-id->implicit-parameters (when (seq implicit-action-models)
                                        (implicit-action-parameters implicit-action-models))]
    (for [action actions]
      (case (:type action)
        :implicit
        (let [model-id        (:model_id action)
              saved-params    (m/index-by :id (:parameters action))
              action-kind     (:kind action)
              implicit-params (cond->> (get model-id->implicit-parameters model-id)
                                :always
                                (map (fn [param]
                                       (let [saved-param  (saved-params (:id param))
                                             ;; we ignore the saved type, to allow schema changes (type changes) to be
                                             ;; reflected in the field presentation
                                             ;; this also fixes #39101 and avoids us making awkward changes to
                                             ;; :parameter transforms for QueryActions.
                                             saved-param' (dissoc saved-param :type)]
                                         (merge param saved-param'))))

                                (= "row/delete" action-kind)
                                (filter ::pk?)

                                (= "row/create" action-kind)
                                (remove #(or (:is-auto-increment %)
                                             ;; non-required PKs like column with default is uuid_generate_v4()
                                             (and (::pk? %) (not (:required %)))))

                                (contains? #{"row/update" "row/delete"} action-kind)
                                (map (fn [param] (cond-> param (::pk? param) (assoc :required true))))

                                :always
                                (map #(dissoc % ::pk? ::field-id)))]
          (cond-> (assoc action :database_id (model-id->db-id (:model_id action)))
            (seq implicit-params)
            (-> (assoc :parameters implicit-params)
                (update-in [:visualization_settings :fields]
                           (fn [fields]
                             (let [param-ids (map :id implicit-params)
                                   fields    (->> (or fields {})
                                                  ;; remove entries that don't match params (in case of deleted columns)
                                                  (m/filter-keys (set param-ids)))]
                               ;; add default entries for params that don't have an entry
                               (reduce (fn [acc param-id]
                                         (if (contains? acc param-id)
                                           acc
                                           (assoc acc param-id {:id param-id, :hidden false})))
                                       fields
                                       param-ids)))))))
        (:query :http)
        action))))

(defn select-action
  "Selects an Action and fills in the subtype data and implicit parameters.
   `options` is [[apply]]ed to [[t2/select]]."
  [& options]
  ;; TODO -- it's dumb that we're calling `t2/select` rather than `t2/select-one` above, limiting like this should never
  ;; be done server-side. I don't have time to fix this right now. -- Cam
  (first (apply select-actions nil options)))

(defn- map-assoc-database-enable-actions
  "Adds a boolean field `:database-enabled-actions` to each action according to the `database-enable-actions` setting for
   the action's database."
  [actions]
  (let [action-ids                  (map :id actions)
        get-database-enable-actions (fn [{:keys [settings]}]
                                      (boolean (some-> settings
                                                       ((get-in (t2/transforms :model/Database) [:settings :out]))
                                                       :database-enable-actions)))
        id->database-enable-actions (into {}
                                          (map (juxt :id get-database-enable-actions))
                                          (t2/query {:select [:action.id :db.settings]
                                                     :from   :action
                                                     :join   [[:report_card :card] [:= :card.id :action.model_id]
                                                              [:metabase_database :db] [:= :db.id :card.database_id]]
                                                     :where  [:in :action.id action-ids]}))]
    (map (fn [action]
           (assoc action :database_enabled_actions (get id->database-enable-actions (:id action))))
         actions)))

(methodical/defmethod t2.hydrate/batched-hydrate [:model/DashboardCard :dashcard/action]
  "Hydrates actions from DashboardCards. Adds a boolean field `:database-enabled-actions` to each action according to
  the\n `database-enable-actions` setting for the action's database."
  [_model _k dashcards]
  (let [actions-by-id
        (when-let [action-ids (seq (keep :action_id dashcards))]
          (->> (select-actions nil :id [:in action-ids])
               map-assoc-database-enable-actions
               (m/index-by :id)))]
    (for [dashcard dashcards
          :let [action-id (:action_id dashcard)
                action    (get actions-by-id action-id)]]
      (m/assoc-some dashcard :action action))))

(defn dashcard->action
  "Get the action associated with a dashcard if exists, return `nil` otherwise."
  [dashcard-or-dashcard-id]
  (some->> (t2/select-one-fn :action_id :model/DashboardCard :id (u/the-id dashcard-or-dashcard-id))
           (select-action :id)))

;;; ------------------------------------------------ Serialization ---------------------------------------------------

(defmethod serdes/hash-fields :model/Action [_action]
  [:name (serdes/hydrated-hash :model) :created_at])

(defmethod serdes/generate-path "QueryAction" [_ _] nil)
(defmethod serdes/make-spec "QueryAction" [_model-name _opts]
  {:copy      []
   :transform {:action_id     (serdes/parent-ref)
               :database_id   (serdes/fk :model/Database :name)
               :dataset_query {:export serdes/export-mbql :import serdes/import-mbql}}})

(defmethod serdes/generate-path "HTTPAction" [_ _] nil)
(defmethod serdes/make-spec "HTTPAction" [_model-name _opts]
  {:copy      [:error_handle :response_handle :template]
   :transform {:action_id (serdes/parent-ref)}})

(defmethod serdes/generate-path "ImplicitAction" [_ _] nil)
(defmethod serdes/make-spec "ImplicitAction" [_model-name _opts]
  {:copy      [:kind]
   :transform {:action_id (serdes/parent-ref)}})

(defmethod serdes/make-spec "Action" [_model-name opts]
  {:copy      [:archived :description :entity_id :name :public_uuid]
   :skip      []
   :transform {:created_at             (serdes/date)
               :type                   (serdes/kw)
               :creator_id             (serdes/fk :model/User)
               :made_public_by_id      (serdes/fk :model/User)
               :model_id               (serdes/fk :model/Card)
               :query                  (serdes/nested :model/QueryAction :action_id opts)
               :http                   (serdes/nested :model/HTTPAction :action_id opts)
               :implicit               (serdes/nested :model/ImplicitAction :action_id opts)
               :parameters             {:export serdes/export-parameters :import serdes/import-parameters}
               :parameter_mappings     {:export serdes/export-parameter-mappings
                                        :import serdes/import-parameter-mappings}
               :visualization_settings {:export serdes/export-visualization-settings
                                        :import serdes/import-visualization-settings}}})

(defmethod serdes/dependencies "Action" [action]
  (set
   (concat
    ;; other stuff is implicitly referenced through a Card
    [[{:model "Card" :id (:model_id action)}]]
    ;; this method is called on ingested data before transformation, and so here it always will be a string
    (when (= (:type action) "query")
      (let [{:keys [database_id dataset_query]} (first (:query action))]
        (concat
         [[{:model "Database" :id database_id}]]
         (serdes/mbql-deps dataset_query)))))))

(defmethod serdes/storage-path "Action" [action _ctx]
  (let [{:keys [id label]} (-> action serdes/path last)]
    ["actions" (serdes/storage-leaf-file-name id label)]))

;;;; ------------------------------------------------- Search ----------------------------------------------------------

(search/define-spec "action"
  {:model        :model/Action
   :attrs        {:archived       true
                  :collection-id  :model.collection_id
                  :creator-id     true
                  :database-id    :query_action.database_id
                  :native-query   :query_action.dataset_query
                  ;; workaround for actions not having revisions (yet)
                  :last-edited-at :updated_at
                  :created-at     true
                  :updated-at     true}
   :search-terms [:name :description]
   :render-terms {:model-id   :model.id
                  :model-name :model.name}
   :where        [:= :collection.namespace nil]
   :joins        {:model        [:model/Card [:= :model.id :this.model_id]]
                  :query_action [:model/QueryAction [:= :query_action.action_id :this.id]]
                  :collection   [:model/Collection [:= :collection.id :model.collection_id]]}})
