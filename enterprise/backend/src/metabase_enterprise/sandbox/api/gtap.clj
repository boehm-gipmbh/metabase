(ns metabase-enterprise.sandbox.api.gtap
  "`/api/mt/gtap` endpoints, for CRUD operations and the like on GTAPs (Group Table Access Policies)."
  (:require
   [metabase-enterprise.sandbox.models.group-table-access-policy :as gtap]
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.api.open-api :as open-api]
   [metabase.driver.util :as driver.u]
   [metabase.premium-features.core :as premium-features]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

(api.macros/defendpoint :get "/"
  "Fetch a list of all GTAPs currently in use, or a single GTAP if both `group_id` and `table_id` are provided."
  [_route-params
   {:keys [group_id table_id]} :- [:map
                                   [:group_id {:optional true} [:maybe ms/PositiveInt]]
                                   [:table_id {:optional true} [:maybe ms/PositiveInt]]]]
  (if (and group_id table_id)
    (t2/select-one :model/GroupTableAccessPolicy :group_id group_id :table_id table_id)
    (t2/select :model/GroupTableAccessPolicy {:order-by [[:id :asc]]})))

(api.macros/defendpoint :get "/:id"
  "Fetch GTAP by `id`"
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (api/check-404 (t2/select-one :model/GroupTableAccessPolicy :id id)))

;; TODO - not sure what other endpoints we might need, e.g. for fetching the list above but for a given group or Table

(def ^:private AttributeRemappings
  :any
  ;; TODO -- fix me
  #_(mu/with-api-error-message [:maybe [:map-of ms/NonBlankString ms/NonBlankString]]
      "value must be a valid attribute remappings map (attribute name -> remapped name)"))

(api.macros/defendpoint :post "/"
  "Create a new GTAP."
  [_route-params
   _query-params
   body :- [:map
            [:table_id             ms/PositiveInt]
            [:card_id              {:optional true} [:maybe ms/PositiveInt]]
            [:group_id             ms/PositiveInt]
            [:attribute_remappings {:optional true} AttributeRemappings]]]
  (first (t2/insert-returning-instances!
          :model/GroupTableAccessPolicy
          (select-keys body [:table_id :card_id :group_id :attribute_remappings]))))

(api.macros/defendpoint :put "/:id"
  "Update a GTAP entry. The only things you're allowed to update for a GTAP are the Card being used (`card_id`) or the
  parameter mappings; changing `table_id` or `group_id` would effectively be deleting this entry and creating a new
  one. If that's what you want to do, do so explicity with appropriate calls to the `DELETE` and `POST` endpoints."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   _query-params
   body :- [:map
            [:card_id              {:optional true} [:maybe ms/PositiveInt]]
            [:attribute_remappings {:optional true} AttributeRemappings]]]
  (api/check-404 (t2/select-one :model/GroupTableAccessPolicy :id id))
  ;; Only update `card_id` and/or `attribute_remappings` if the values are present in the body of the request.
  ;; This allows existing values to be "cleared" by being set to nil
  (when (some #(contains? body %) [:card_id :attribute_remappings])
    (t2/update! :model/GroupTableAccessPolicy id
                (u/select-keys-when body
                                    :present #{:card_id :attribute_remappings})))
  (t2/select-one :model/GroupTableAccessPolicy :id id))

(api.macros/defendpoint :post "/validate"
  "Validate a sandbox which may not have yet been saved. This runs the same validation that is performed when the
  sandbox is saved, but doesn't actually save the sandbox."
  [_route-params
   _query-params
   {:keys [table_id card_id]} :- [:map
                                  [:table_id ms/PositiveInt]
                                  [:card_id  {:optional true} [:maybe ms/PositiveInt]]]]
  (when card_id
    (let [db (t2/select-one :model/Database
                            :id {:select [:t.db_id]
                                 :from [[(t2/table-name :model/Table) :t]]
                                 :where [:= :t.id table_id]})]
      (when (not (driver.u/supports? (:engine db) :saved-question-sandboxing db))
        (throw (ex-info (tru "Sandboxing with a saved question is not enabled for this database.")
                        {:status-code 400
                         :message     (tru "Sandboxing with a saved question is not enabled for this database.")})))))
  (gtap/check-columns-match-table {:table_id table_id
                                   :card_id  card_id}))

(api.macros/defendpoint :delete "/:id"
  "Delete a GTAP entry."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (api/check-404 (t2/select-one :model/GroupTableAccessPolicy :id id))
  (t2/delete! :model/GroupTableAccessPolicy :id id)
  api/generic-204-no-content)

(defn- +check-sandboxes-enabled
  "Wrap the Ring handler to make sure sandboxes are enabled before allowing access to the API endpoints."
  [handler]
  (open-api/handler-with-open-api-spec
   (fn [request respond raise]
     (if-not (premium-features/enable-sandboxes?)
       (raise (ex-info (str (tru "Error: sandboxing is not enabled for this instance.")
                            " "
                            (tru "Please check you have set a valid Enterprise token and try again."))
                       {:status-code 403}))
       (handler request respond raise)))
   (fn [prefix]
     (open-api/open-api-spec handler prefix))))

;; All endpoints in this namespace require superuser perms to view
;;
;; TODO - does it make sense to have this middleware
;; here? Or should we just wrap `routes` in the `metabase-enterprise.sandbox.api.routes/routes` table like we do for everything else?
;;
;; TODO - defining the `check-superuser` check *here* means the API documentation function won't pick up on the "this
;; requires a superuser" stuff since it parses the `defendpoint` body to look for a call to `check-superuser`. I
;; suppose this doesn't matter (much) body since this is an enterprise endpoint and won't go in the dox anyway.
(def ^{:arglists '([request respond raise])} routes
  "`/api/mt/gtap` routes."
  (api.macros/ns-handler *ns* api/+check-superuser +check-sandboxes-enabled))
