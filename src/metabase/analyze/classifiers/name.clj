(ns metabase.analyze.classifiers.name
  "Classifier that infers the semantic type of a Field based on its name and base type."
  (:require
   [clojure.string :as str]
   [metabase.analyze.schema :as analyze.schema]
   [metabase.config.core :as config]
   [metabase.driver.util :as driver.u]
   [metabase.lib.schema.metadata.fingerprint :as lib.schema.metadata.fingerprint]
   [metabase.sync.util :as sync-util]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]))

(def ^:private float-type       #{:type/Float})
(def ^:private int-type         #{:type/Integer})
(def ^:private int-or-text-type #{:type/Integer :type/Text})
(def ^:private text-type        #{:type/Text})
(def ^:private timestamp-type   #{:type/DateTime})
(def ^:private time-type        #{:type/Time})
(def ^:private date-type        #{:type/Date})
(def ^:private number-type      #{:type/Number})
(def ^:private any-type         #{:type/*})

(def ^:private pattern+base-types+semantic-type
  "Tuples of `[name-pattern set-of-valid-base-types semantic-type]`.
   Fields whose name matches the pattern and one of the base types should be given the semantic type.
   Be mindful that patterns are tried top to bottom when matching derived types (eg. Date should be
   before DateTime).

   *  Convert field name to lowercase before matching against a pattern
   *  Consider a nil set-of-valid-base-types to mean \"match any base type\""
  [[#"^id$"                        any-type         :type/PK]
   [#"^lon$"                       float-type       :type/Longitude]
   [#"^.*_lon$"                    float-type       :type/Longitude]
   [#"^.*_lng$"                    float-type       :type/Longitude]
   [#"^.*_long$"                   float-type       :type/Longitude]
   [#"^.*_longitude$"              float-type       :type/Longitude]
   [#"^lng$"                       float-type       :type/Longitude]
   [#"^long$"                      float-type       :type/Longitude]
   [#"^longitude$"                 float-type       :type/Longitude]
   [#"^lat$"                       float-type       :type/Latitude]
   [#"^.*_lat$"                    float-type       :type/Latitude]
   [#"^latitude$"                  float-type       :type/Latitude]
   [#"^.*_latitude$"               float-type       :type/Latitude]
   [#"^.*_type$"                   text-type        :type/Category]
   [#"^.*_url$"                    text-type        :type/URL]
   [#"^city$"                      text-type        :type/City]
   [#"^country"                    text-type        :type/Country]
   [#"_country$"                   text-type        :type/Country]
   [#"^currency$"                  text-type        :type/Category]
   [#"^first(?:_?)name$"           text-type        :type/Name]
   [#"^full(?:_?)name$"            text-type        :type/Name]
   [#"^gender$"                    text-type        :type/Category]
   [#"^last(?:_?)name$"            text-type        :type/Name]
   [#"^name$"                      text-type        :type/Name]
   [#"^postal(?:_?)code$"          int-or-text-type :type/ZipCode]
   [#"^role$"                      text-type        :type/Category]
   [#"^sex$"                       text-type        :type/Category]
   [#"^status$"                    text-type        :type/Category]
   [#"^type$"                      text-type        :type/Category]
   [#"^url$"                       text-type        :type/URL]
   [#"^zip(?:_?)code$"             int-or-text-type :type/ZipCode]
   [#"discount"                    number-type      :type/Discount]
   [#"income"                      number-type      :type/Income]
   [#"quantity"                    int-type         :type/Quantity]
   [#"count$"                      int-type         :type/Quantity]
   [#"number"                      int-type         :type/Quantity]
   [#"^num_"                       int-type         :type/Quantity]
   [#"join"                        date-type        :type/JoinDate]
   [#"join"                        time-type        :type/JoinTime]
   [#"join"                        timestamp-type   :type/JoinTimestamp]
   [#"create"                      date-type        :type/CreationDate]
   [#"create"                      time-type        :type/CreationTime]
   [#"create"                      timestamp-type   :type/CreationTimestamp]
   [#"start"                       date-type        :type/CreationDate]
   [#"start"                       time-type        :type/CreationTime]
   [#"start"                       timestamp-type   :type/CreationTimestamp]
   [#"cancel"                      date-type        :type/CancelationDate]
   [#"cancel"                      time-type        :type/CancelationTime]
   [#"cancel"                      timestamp-type   :type/CancelationTimestamp]
   [#"delet(?:e|i)"                date-type        :type/DeletionDate]
   [#"delet(?:e|i)"                time-type        :type/DeletionTime]
   [#"delet(?:e|i)"                timestamp-type   :type/DeletionTimestamp]
   [#"update"                      date-type        :type/UpdatedDate]
   [#"update"                      time-type        :type/UpdatedTime]
   [#"update"                      timestamp-type   :type/UpdatedTimestamp]
   [#"source"                      int-or-text-type :type/Source]
   [#"channel"                     int-or-text-type :type/Source]
   [#"share"                       float-type       :type/Share]
   [#"percent"                     float-type       :type/Share]
   [#"rate$"                       float-type       :type/Share]
   [#"margin"                      number-type      :type/GrossMargin]
   [#"cost"                        number-type      :type/Cost]
   [#"duration"                    number-type      :type/Duration]
   [#"author"                      int-or-text-type :type/Author]
   [#"creator"                     int-or-text-type :type/Author]
   [#"created(?:_?)by"             int-or-text-type :type/Author]
   [#"owner"                       int-or-text-type :type/Owner]
   [#"company"                     int-or-text-type :type/Company]
   [#"vendor"                      int-or-text-type :type/Company]
   [#"subscription"                int-or-text-type :type/Subscription]
   [#"score"                       number-type      :type/Score]
   [#"rating"                      number-type      :type/Score]
   [#"stars"                       number-type      :type/Score]
   [#"description"                 text-type        :type/Description]
   [#"title"                       text-type        :type/Title]
   [#"comment"                     text-type        :type/Comment]
   [#"birthda(?:te|y)"             date-type        :type/Birthdate]
   [#"birthda(?:te|y)"             timestamp-type   :type/Birthdate]
   [#"(?:te|y)(?:_?)of(?:_?)birth" date-type        :type/Birthdate]
   [#"(?:te|y)(?:_?)of(?:_?)birth" timestamp-type   :type/Birthdate]])

;; Check that all the pattern tuples are valid
(when-not config/is-prod?
  (doseq [[name-pattern base-types semantic-type] pattern+base-types+semantic-type]
    (assert (instance? java.util.regex.Pattern name-pattern))
    (assert (every? #(isa? % :type/*) base-types))
    (assert (or (isa? semantic-type :Semantic/*)
                (isa? semantic-type :Relation/*)))))

(mu/defn- semantic-type-for-name-and-base-type :- [:maybe ms/FieldSemanticOrRelationType]
  "If `name` and `base-type` matches a known pattern, return the `semantic-type` we should assign to it."
  [field-name :- ms/NonBlankString
   base-type  :- ms/FieldType]
  (let [field-name (u/lower-case-en field-name)]
    (some (fn [[name-pattern valid-base-types semantic-type]]
            (when (and (some (partial isa? base-type) valid-base-types)
                       (re-find name-pattern field-name))
              semantic-type))
          pattern+base-types+semantic-type)))

(def ^:private FieldOrColumn
  "Schema that allows a `:model/Field` or a column from a query resultset"
  [:and
   [:map
    ;; Some DBs such as MSSQL can return columns with blank name
    [:name      :string]
    [:base_type :keyword]
    [:semantic_type {:optional true} [:maybe :keyword]]]
   ::analyze.schema/qp-results-cased-map])

(mu/defn infer-semantic-type-by-name :- [:maybe :keyword]
  "Classifer that infers the semantic type of a `field` based on its name and base type."
  [field-or-column :- FieldOrColumn]
  ;; Don't overwrite keys, else we're ok with overwriting as a new more precise type might have
  ;; been added.
  (when-not (or (some (partial isa? (:semantic_type field-or-column)) [:type/PK :type/FK])
                (str/blank? (:name field-or-column)))
    (semantic-type-for-name-and-base-type (:name field-or-column) (:base_type field-or-column))))

(mu/defn infer-and-assoc-semantic-type-by-name :- [:maybe FieldOrColumn]
  "Returns `field-or-column` with a computed semantic type based on the name and base type of the `field-or-column`"
  [field-or-column :- FieldOrColumn
   _fingerprint    :- [:maybe ::lib.schema.metadata.fingerprint/fingerprint]]
  (when-let [inferred-semantic-type (infer-semantic-type-by-name field-or-column)]
    (log/debugf "Based on the name of %s, we're giving it a semantic type of %s."
                (sync-util/name-for-logging field-or-column)
                inferred-semantic-type)
    (assoc field-or-column :semantic_type inferred-semantic-type)))

(defn- prefix-or-postfix
  [s]
  (re-pattern (format "(?:^%s)|(?:%ss?$)" s s)))

(def ^:private entity-types-patterns
  [[(prefix-or-postfix "order")        :entity/TransactionTable]
   [(prefix-or-postfix "transaction")  :entity/TransactionTable]
   [(prefix-or-postfix "sale")         :entity/TransactionTable]
   [(prefix-or-postfix "product")      :entity/ProductTable]
   [(prefix-or-postfix "user")         :entity/UserTable]
   [(prefix-or-postfix "account")      :entity/UserTable]
   [(prefix-or-postfix "people")       :entity/UserTable]
   [(prefix-or-postfix "person")       :entity/UserTable]
   [(prefix-or-postfix "employee")     :entity/UserTable]
   [(prefix-or-postfix "event")        :entity/EventTable]
   [(prefix-or-postfix "checkin")      :entity/EventTable]
   [(prefix-or-postfix "log")          :entity/EventTable]
   [(prefix-or-postfix "subscription") :entity/SubscriptionTable]
   [(prefix-or-postfix "company")      :entity/CompanyTable]
   [(prefix-or-postfix "companies")    :entity/CompanyTable]
   [(prefix-or-postfix "vendor")       :entity/CompanyTable]])

(mu/defn infer-entity-type-by-name :- analyze.schema/Table
  "Classifer that infers the semantic type of a `table` based on its name."
  [table :- analyze.schema/Table]
  (let [table-name (-> table :name u/lower-case-en)]
    (assoc table :entity_type (or (some (fn [[pattern type]]
                                          (when (re-find pattern table-name)
                                            type))
                                        entity-types-patterns)
                                  (case (some-> (:db_id table) driver.u/database->driver)
                                    :druid :entity/EventTable
                                    nil)
                                  :entity/GenericTable))))
