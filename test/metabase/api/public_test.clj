(ns ^:mb/driver-tests metabase.api.public-test
  "Tests for `api/public/` (public links) endpoints."
  (:require
   [clojure.data.csv :as csv]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [dk.ative.docjure.spreadsheet :as spreadsheet]
   [metabase.analytics.snowplow-test :as snowplow-test]
   [metabase.analytics.stats :as stats]
   [metabase.api.card-test :as api.card-test]
   [metabase.api.dashboard-test :as api.dashboard-test]
   [metabase.api.pivots :as api.pivots]
   [metabase.api.public :as api.public]
   [metabase.config :as config]
   [metabase.http-client :as client]
   [metabase.models.interface :as mi]
   [metabase.models.params :as params]
   [metabase.models.params.chain-filter-test :as chain-filter-test]
   [metabase.models.permissions :as perms]
   [metabase.models.permissions-group :as perms-group]
   [metabase.query-processor :as qp]
   [metabase.query-processor.middleware.process-userland-query-test :as process-userland-query-test]
   [metabase.test :as mt]
   [metabase.test.util :as tu]
   [metabase.util :as u]
   [metabase.util.json :as json]
   [throttle.core :as throttle]
   [toucan2.core :as t2])
  (:import
   (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

;;; --------------------------------------------------- Helper Fns ---------------------------------------------------

(defn- shared-obj []
  {:public_uuid       (str (random-uuid))
   :made_public_by_id (mt/user->id :crowberto)})

(defn- native-query-with-template-tag []
  {:database (mt/id)
   :type     :native
   :native   {:query         "SELECT count(*) AS Count FROM venues [[WHERE id = {{venue_id}}]]"
              :template-tags {"venue_id" {:name         "venue_id"
                                          :display-name "Venue ID"
                                          :type         :number
                                          :required     false}}}})

(defn do-with-temp-public-card [m f]
  (let [m (merge (when-not (:dataset_query m)
                   {:dataset_query (mt/mbql-query venues {:aggregation [[:count]]})})
                 (when-not (:parameters m)
                   {:parameters [{:name                 "Static Category",
                                  :slug                 "static_category"
                                  :id                   "_STATIC_CATEGORY_",
                                  :type                 "category",
                                  :values_source_type   "static-list"
                                  :values_source_config {:values ["African" "American" "Asian"]}}]})
                 (shared-obj)
                 m)]
    (mt/with-temp [:model/Card card m]
      ;; add :public_uuid back in to the value that gets bound because it might not come back from post-select if
      ;; public sharing is disabled; but we still want to test it
      (f (assoc card :public_uuid (:public_uuid m))))))

(defmacro with-temp-public-card {:style/indent 1} [[binding & [card]] & body]
  `(do-with-temp-public-card
    ~card
    (fn [~binding]
      ~@body)))

(defn do-with-temp-public-dashboard
  [m f]
  (let [m (merge
           (when-not (:parameters m)
             {:parameters [{:id      "_VENUE_ID_"
                            :name    "Venue ID"
                            :slug    "venue_id"
                            :type    "id"
                            :target  [:dimension (mt/id :venues :id)]
                            :default nil}]})
           (shared-obj)
           m)]
    (mt/with-temp [:model/Dashboard dashboard m]
      (f (assoc dashboard :public_uuid (:public_uuid m))))))

(defmacro with-temp-public-dashboard {:style/indent 1} [[binding & [dashboard]] & body]
  `(do-with-temp-public-dashboard
    ~dashboard
    (fn [~binding]
      ~@body)))

(defn add-card-to-dashboard! [card dashboard & {parameter-mappings :parameter_mappings, :as kvs}]
  (first (t2/insert-returning-instances! :model/DashboardCard (merge {:dashboard_id       (u/the-id dashboard)
                                                                      :card_id            (u/the-id card)
                                                                      :row                0
                                                                      :col                0
                                                                      :size_x             4
                                                                      :size_y             4
                                                                      :parameter_mappings (or parameter-mappings
                                                                                              [{:parameter_id "_VENUE_ID_"
                                                                                                :card_id      (u/the-id card)
                                                                                                :target       [:dimension [:field (mt/id :venues :id) nil]]}])}
                                                                     kvs))))

;; TODO -- we can probably use [[metabase.api.dashboard-test/with-chain-filter-fixtures]] for mocking this stuff
;; instead since it does mostly the same stuff anyway
(defmacro with-temp-public-dashboard-and-card
  {:style/indent 1}
  [[dashboard-binding card-binding & [dashcard-binding]] & body]
  (let [dashcard-binding (or dashcard-binding (gensym "dashcard"))]
    `(with-temp-public-dashboard [dash#]
       (with-temp-public-card [card#]
         (let [~dashboard-binding dash#
               ~card-binding      card#
               ~dashcard-binding  (add-card-to-dashboard! card# dash#)]
           ~@body)))))

;;; ------------------------------------------- GET /api/public/card/:uuid -------------------------------------------

(deftest fetch-card-test
  (testing "GET /api/public/card/:uuid"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      ;; TODO -- shouldn't this return a 404? I guess it's because we're 'genericizing' all the errors in public
      ;; endpoints in [[metabase.server.middleware.exceptions/genericize-exceptions]]
      (testing "should return 400 if Card doesn't exist"
        (is (= "Not found."
               (client/client :get 404 (str "public/card/" (random-uuid))))))

      (with-temp-public-card [{uuid :public_uuid, card-id :id}]
        (testing "Happy path -- should be able to fetch the Card"
          (client/client :get 200 (str "public/card/" uuid)))

        (testing "Check that we cannot fetch a public Card if public sharing is disabled"
          (mt/with-temporary-setting-values [enable-public-sharing false]
            (is (= "An error occurred."
                   (client/client :get 400 (str "public/card/" uuid))))))

        (testing "Check that we cannot fetch a public Card that has been archived"
          (mt/with-temp-vals-in-db :model/Card card-id {:archived true}
            (is (= "Not found."
                   (client/client :get 404 (str "public/card/" uuid))))))))))

(deftest public-queries-are-counted-test
  (testing "GET /api/public/card/:uuid/query coutns as a public query"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-card [{uuid :public_uuid}]
        (testing "should increment the public link query count when fetching a public Card"
          (let [get-qe-count (fn get-qe-count [] (get-in (#'stats/->snowplow-grouped-metric-info)
                                                         [:query-executions "public_link"]))
                qe-count-before (get-qe-count)]
            (client/client :get 202 (str "public/card/" uuid "/query"))
            ;; The qe-count gets incremented asynchronously, so we need to poll until it's updated.
            ;; We poll for 300ms, which should be enough time for the count to be updated.
            ;; Once the qe-count is updated the test will pass, and stop polling.
            ;; If it's not updated within 300ms, the test will fail.
            (testing "the count should be incremented within 300 ms:"
              (is (tu/poll-until 300 (> (get-qe-count) qe-count-before))))))))))

(deftest make-sure-param-values-get-returned-as-expected
  (let [category-name-id (mt/id :categories :name)]
    (mt/with-temp [:model/Card card {:dataset_query
                                     {:database (mt/id)
                                      :type     :native
                                      :native   {:query         (str "SELECT COUNT(*) "
                                                                     "FROM venues "
                                                                     "LEFT JOIN categories ON venues.category_id = categories.id "
                                                                     "WHERE {{category}}")
                                                 :collection    "CATEGORIES"
                                                 :template-tags {:category {:name         "category"
                                                                            :display-name "Category"
                                                                            :type         "dimension"
                                                                            :dimension    ["field" category-name-id nil]
                                                                            :widget-type  "category"
                                                                            :required     true}}}}}]
      (is (= {(mt/id :categories :name) {:values                (t2/select-one-fn (comp count :values)
                                                                                  :model/FieldValues :field_id category-name-id
                                                                                  :type :full)
                                         :human_readable_values []
                                         :field_id              category-name-id}}
             (-> (:param_values (#'api.public/public-card :id (u/the-id card)))
                 (update-in [category-name-id :values] count)
                 (update category-name-id #(into {} %))))))))

(defn card-with-snippet-and-card-template-tags []
  {:enable_embedding true
   :dataset_query
   {:database (mt/id)
    :type     :native
    :native   {:query         "select {{snippet: all}} from {{#1-card}}"
               :template-tags {:a           {:type         "date"
                                             :name         "a"
                                             :display-name "a"
                                             :id           "a"
                                             :default      "A TAG"}
                               "snippet: all" {:type         :snippet,
                                               :name         "snippet: all",
                                               :id           (str (random-uuid)),
                                               :snippet-name "all",
                                               :display-name "Snippet: All",
                                               :snippet-id   1}
                               "1-card"       {:type         :card,
                                               :name         "1-card",
                                               :id           (str (random-uuid)),
                                               :display-name "1 Card",
                                               :card-id      1}}}}
   :embedding_params {:a "enabled"}})

(defn card-with-embedded-params []
  {:enable_embedding true
   :dataset_query    {:database (mt/id)
                      :type     :native
                      :native   {:template-tags {:a {:type "date", :name "a", :display_name "a" :id "a" :default "A TAG"}
                                                 :b {:type "date", :name "b", :display_name "b" :id "b" :default "B TAG"}
                                                 :c {:type "date", :name "c", :display_name "c" :id "c" :default "C TAG"}
                                                 :d {:type "date", :name "d", :display_name "d" :id "d" :default "D TAG"}}}}
   :parameters       [{:type "date", :name "a", :display_name "a" :id "a" :default "A param"}
                      {:type "date", :name "b", :display_name "b" :id "b" :default "B param"}
                      {:type "date", :name "c", :display_name "c" :id "c" :default "C param"
                       :values_source_type "static-list" :values_source_config {:values ["BBQ" "Bakery" "Bar"]}}]
   :embedding_params {:a "locked", :b "disabled", :c "enabled", :d "enabled"}})

(deftest get-card-parameters-should-work-with-legacy-template-tags
  ;; in 44 we added card.parameters but we didn't migrate template-tags to parameters
  ;; because doing such migration is costly.
  ;; so there are cards where some parameters in template-tags does not exist in card.parameters
  ;; that why we need to keep concat both of them then dedupe by id
  (testing "parameters should get from both template-tags and card.parameters"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp [:model/Card card (assoc (card-with-embedded-params) :public_uuid (str (random-uuid)))]
        (is (= [{:type         "date/single",
                 :name         "a",
                 :display_name "a",
                 :id           "a",
                 :default      "A TAG",
                 :target       ["variable" ["template-tag" "a"]],
                 :slug         "a"
                 :required     false}
                {:type         "date/single",
                 :name         "b",
                 :display_name "b",
                 :id           "b",
                 :default      "B TAG",
                 :target       ["variable" ["template-tag" "b"]],
                 :slug         "b"
                 :required     false}
                ;; the parameter with id = "c" exists in both card.parameters and tempalte-tags should have info
                ;; merge of both places
                {:type                 "date/single",
                 :name                 "c",
                 :display_name         "c",
                 :slug                 "c",
                 ;; order importance: the default from template-tag is in the final result
                 :default              "C TAG",
                 :required             false
                 :values_source_type   "static-list",
                 :id                   "c",
                 :target               ["variable" ["template-tag" "c"]],
                 :values_source_config {:values ["BBQ" "Bakery" "Bar"]}}
                ;; the parameter id = "d" is in template-tags, but not card.parameters,
                ;; when fetching card we should get it returned
                {:id       "d",
                 :type     "date/single",
                 :target   ["variable" ["template-tag" "d"]],
                 :name     "d",
                 :slug     "d",
                 :default  "D TAG"
                 :required false}]
               (:parameters (client/client :get 200 (str "public/card/" (:public_uuid card))))))))))

(deftest get-card-parameters-should-exclude-non-parameter-template-tags
  ;; in 44 we added card.parameters but we didn't migrate template-tags to parameters
  ;; because doing such migration is costly.
  ;; so there are cards where some parameters in template-tags does not exist in card.parameters
  ;; that why we need to keep concat both of them, but exclude non-parameter template-tags
  (testing "parameters should exclude non-parameter template-tags"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp [:model/Card card (assoc (card-with-snippet-and-card-template-tags) :public_uuid (str (random-uuid)))]
        (is (= [; this corresponds to the only template tag that is a parameter
                {:type         "date/single",
                 :name         "a",
                 :id           "a",
                 :default      "A TAG",
                 :target       ["variable" ["template-tag" "a"]],
                 :slug         "a"
                 :required     false}]
               (:parameters (client/client :get 200 (str "public/card/" (:public_uuid card))))))))))

;;; ------------------------- GET /api/public/card/:uuid/query (and JSON/CSV/XSLX versions) --------------------------

(defn card-query-url
  "Generate a query URL for an public Card"
  [card response-format-route-suffix]
  {:pre [(#{"" "/json" "/csv" "/xlsx"} response-format-route-suffix)]}
  (str "public/card/" (:public_uuid card) "/query" response-format-route-suffix))

(deftest check-that-we--cannot--execute-a-publiccard-if-the-setting-is-disabled
  (mt/with-temporary-setting-values [enable-public-sharing false]
    (with-temp-public-card [{uuid :public_uuid}]
      (is (= "An error occurred."
             (client/client :get 400 (str "public/card/" uuid "/query")))))))

(deftest check-that-we-get-a-404-if-the-publiccard-doesn-t-exist-query
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (is (= "Not found."
           (client/client :get 404 (str "public/card/" (random-uuid) "/query"))))))

(deftest check-that-we--cannot--execute-a-publiccard-if-the-card-has-been-archived
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid} {:archived true}]
      (is (= "Not found."
             (client/client :get 404 (str "public/card/" uuid "/query")))))))

(defn- parse-xlsx-response [response]
  (->> (ByteArrayInputStream. response)
       spreadsheet/load-workbook
       (spreadsheet/select-sheet "Query result")
       (spreadsheet/select-columns {:A :col})))

(deftest execute-public-card-test
  (testing "GET /api/public/card/:uuid/query"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-card [{uuid :public_uuid}]
        (testing "Default :api response format"
          (is (= [[100]]
                 (mt/rows (client/client :get 202 (str "public/card/" uuid "/query"))))))

        (testing ":json download response format"
          (is (= [{:Count "100"}]
                 (client/client :get 200 (str "public/card/" uuid "/query/json?format_rows=true")))))

        (testing ":csv download response format"
          (is (= "Count\n100\n"
                 (client/client :get 200 (str "public/card/" uuid "/query/csv?format_rows=true"), :format :csv))))

        (testing ":xlsx download response format"
          (is (= [{:col "Count"} {:col 100.0}]
                 (parse-xlsx-response
                  (client/client :get 200 (str "public/card/" uuid "/query/xlsx?format_rows=true"))))))))))

(deftest execute-public-card-as-user-without-perms-test
  (testing "A user that doesn't have permissions to run the query normally should still be able to run a public Card as if they weren't logged in"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp [:model/Collection {collection-id :id}]
        (perms/revoke-collection-permissions! (perms-group/all-users) collection-id)
        (with-temp-public-card [{card-id :id, uuid :public_uuid} {:collection_id collection-id}]
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :post 403 (format "card/%d/query" card-id)))
              "Sanity check: shouldn't be allowed to run the query normally")
          (is (= [[100]]
                 (mt/rows
                  (mt/user-http-request :rasta :get 202 (str "public/card/" uuid "/query"))))))))))

(deftest execute-public-card-with-parameters-test
  (testing "JSON-encoded MBQL parameters passed as a query parameter should work (#17019)"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-card [{uuid :public_uuid} {:dataset_query (native-query-with-template-tag)}]
        (is (=? {:status     "completed"
                 :json_query {:parameters [{:id    "_VENUE_ID_"
                                            :name  "venue_id"
                                            :slug  "venue_id"
                                            :type  "number"
                                            :value 2}]}}
                (client/client :get 202 (str "public/card/" uuid "/query")
                               :parameters (json/encode [{:id    "_VENUE_ID_"
                                                          :name  "venue_id"
                                                          :slug  "venue_id"
                                                          :type  "number"
                                                          :value 2}])))))

      ;; see longer explanation in [[metabase.legacy-mbql.schema/parameter-types]]
      (testing "If the FE client is incorrectly passing in the parameter as a `:category` type, allow it for now"
        (with-temp-public-card [{uuid :public_uuid} {:dataset_query {:database (mt/id)
                                                                     :type     :native
                                                                     :native   {:query "SELECT {{foo}}"
                                                                                :template-tags
                                                                                {"foo"
                                                                                 {:id           "abc123"
                                                                                  :name         "foo"
                                                                                  :display-name "Filter"
                                                                                  :type         :text}}}}}]
          (is (=? {:status "completed"
                   :data   {:rows [["456"]]}}
                  (client/client :get 202 (format "public/card/%s/query" uuid)
                                 :parameters (json/encode [{:type   "category"
                                                            :value  "456"
                                                            :target ["variable" ["template-tag" "foo"]]
                                                            :id     "ed1fd39e-2e35-636f-ec44-8bf226cca5b0"}])))))))))

(deftest execute-public-card-with-default-parameters-test
  (testing "GET /api/public/card/:uuid/query with parameters with default values"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp
        [:model/Card card (merge {:enable_embedding true
                                  :dataset_query
                                  {:database (mt/id)
                                   :type     :native
                                   :native   {:query         "SELECT count(*) AS Count FROM venues where {{venue_id}}"
                                              :template-tags {"venue_id" {:dimension    [:field (mt/id :venues :id) nil],
                                                                          :display-name "Venue ID",
                                                                          :id           "_VENUE_ID_",
                                                                          :name         "venue_id",
                                                                          :required     false,
                                                                          :default      1
                                                                          :type         :dimension,
                                                                          :widget-type  :id}}}}}
                                 (shared-obj))]
        (is (=? {:status     "completed"
                 :json_query {:parameters [{:id    "_VENUE_ID_"
                                            :name  "venue_id"
                                            :slug  "venue_id"
                                            :type  "number"
                                            :value 2}]}}
                (client/client :get 202 (card-query-url card "")
                               :parameters (json/encode [{:id    "_VENUE_ID_"
                                                          :name  "venue_id"
                                                          :slug  "venue_id"
                                                          :type  "number"
                                                          :value 2}]))))
        (testing "the default should apply if no param value is provided"
          (is (= [[1]]
                 (mt/rows (client/client :get 202 (card-query-url card "")
                                         :parameters (json/encode [])))))
          (testing "check this is the same result as when the default value is provided"
            (is (= [[1]]
                   (mt/rows (client/client :get 202 (card-query-url card "")
                                           :parameters (json/encode [{:id "_VENUE_ID_",
                                                                      :target ["dimension" ["template-tag" "venue_id"]],
                                                                      :type "id",
                                                                      :value 1}])))))))
        (testing "the field filter should not apply if the parameter has a nil value"
          (is (= [[100]]
                 (mt/rows (client/client :get 202 (card-query-url card "")
                                         :parameters (json/encode [{:id "_VENUE_ID_",
                                                                    :target ["dimension" ["template-tag" "venue_id"]],
                                                                    :type "id",
                                                                    :value nil}]))))))))))

;; Cards with required params
(defn- do-with-required-param-card! [f]
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}
                            {:dataset_query
                             {:database (mt/id)
                              :type     :native
                              :native   {:query         "SELECT count(*) FROM venues v WHERE price = {{price}}"
                                         :template-tags {"price" {:name         "price"
                                                                  :display-name "Price"
                                                                  :type         :number
                                                                  :required     true}}}}}]
      (f uuid))))

(defmacro ^:private with-required-param-card! [[uuid-binding] & body]
  `(do-with-required-param-card! (fn [~uuid-binding] ~@body)))

(deftest should-be-able-to-run-a-card-with-a-required-param
  (with-required-param-card! [uuid]
    (is (= [[22]]
           (mt/rows
            (client/client :get 202 (str "public/card/" uuid "/query")
                           :parameters (json/encode [{:type   :number
                                                      :target [:variable [:template-tag "price"]]
                                                      :value  1}])))))))

(deftest missing-required-param-error-message-test
  (testing (str "If you're missing a required param, the error message should get passed thru, rather than the normal "
                "generic 'Query Failed' message that we show for most embedding errors")
    (with-required-param-card! [uuid]
      (is (= {:status     "failed"
              :error      "You'll need to pick a value for 'Price' before this query can run."
              :error_type "missing-required-parameter"}
             (client/client :get 202 (str "public/card/" uuid "/query")))))))

(defn- card-with-date-field-filter []
  (assoc (shared-obj)
         :dataset_query {:database (mt/id)
                         :type     :native
                         :native   {:query         "SELECT COUNT(*) AS \"count\" FROM CHECKINS WHERE {{date}}"
                                    :template-tags {:date {:name         "date"
                                                           :display-name "Date"
                                                           :type         "dimension"
                                                           :dimension    [:field (mt/id :checkins :date) nil]
                                                           :widget-type  "date/quarter-year"}}}}))

(deftest make-sure-csv--etc---downloads-take-editable-params-into-account---6407----
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (mt/with-temp [:model/Card {uuid :public_uuid} (card-with-date-field-filter)]
      (is (= "count\n107\n"
             (client/client :get 200 (str "public/card/" uuid "/query/csv")
                            :parameters (json/encode [{:id     "_DATE_"
                                                       :type   :date/quarter-year
                                                       :target [:dimension [:template-tag :date]]
                                                       :value  "Q1-2014"}])))))))

(deftest make-sure-it-also-works-with-the-forwarded-url
  (mt/test-helpers-set-global-values!
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp [:model/Card {uuid :public_uuid} (card-with-date-field-filter)]
        ;; make sure the URL doesn't include /api/ at the beginning like it normally would
        (binding [client/*url-prefix* ""]
          (mt/with-temporary-setting-values [site-url (str "http://localhost:" (config/config-str :mb-jetty-port) client/*url-prefix*)]
            (is (= "count\n107\n"
                   (client/real-client :get 200 (str "public/question/" uuid ".csv")
                                       :parameters (json/encode [{:id     "_DATE_"
                                                                  :type   :date/quarter-year
                                                                  :target [:dimension [:template-tag :date]]
                                                                  :value  "Q1-2014"}]))))))))))

(defn- card-with-trendline []
  (assoc (shared-obj)
         :dataset_query (mt/mbql-query checkins
                          {:breakout    [!month.date]
                           :aggregation [[:count]]})))

(deftest make-sure-we-include-all-the-relevant-fields-like-insights
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (mt/with-temp [:model/Card {uuid :public_uuid} (card-with-trendline)]
      (is (= #{:cols :rows :insights :results_timezone}
             (-> (client/client :get 202 (str "public/card/" uuid "/query"))
                 :data
                 keys
                 set))))))

(deftest query-execution-context test
  (testing "Make sure we record the correct context for each export format (#45147)"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (let [query (merge (mt/mbql-query venues)
                         ;; Add these constraints for the API query so that the query hash matches in `with-query-execution!`
                         {:constraints {:max-results 10000, :max-results-bare-rows 2000}})]
        (with-temp-public-card [{uuid :public_uuid} {:dataset_query query}]
          (testing "Default :api response format"
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 202 (str "public/card/" uuid "/query"))
              (is (= :public-question (:context (qe))))))))

      (let [query (mt/mbql-query venues)]
        (with-temp-public-card [{uuid :public_uuid} {:dataset_query query}]
          (testing ":json download response format"
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 200 (str "public/card/" uuid "/query/json"))
              (is (= :public-json-download (:context (qe))))))

          (testing ":xlsx download response format"
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 200 (str "public/card/" uuid "/query/xlsx"))
              (is (= :public-xlsx-download (:context (qe))))))

          (testing ":csv download response format"
            (process-userland-query-test/with-query-execution! [qe query]
              (client/client :get 200 (str "public/card/" uuid "/query/csv"), :format :csv)
              (is (= :public-csv-download (:context (qe)))))))))))

;;; ---------------------------------------- GET /api/public/dashboard/:uuid -----------------------------------------

(deftest get-public-dashboard-errors-test
  (testing "GET /api/public/dashboard/:uuid"
    (testing "Shouldn't be able to fetch a public Dashboard if public sharing is disabled"
      (mt/with-temporary-setting-values [enable-public-sharing false]
        (with-temp-public-dashboard [{uuid :public_uuid}]
          (is (= "An error occurred."
                 (client/client :get 400 (str "public/dashboard/" uuid)))))))

    (testing "Should get a 400 if the Dashboard doesn't exist"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (is (= "Not found."
               (client/client :get 404 (str "public/dashboard/" (random-uuid)))))))))

(defn fetch-public-dashboard
  "Fetch a public dashboard by it's public UUID."
  [{uuid :public_uuid}]
  (-> (client/client :get 200 (str "public/dashboard/" uuid))
      (select-keys [:name :dashcards :tabs])
      (update :name boolean)
      (update :dashcards count)
      (update :tabs count)))

(deftest get-public-dashboard-test
  (testing "GET /api/public/dashboard/:uuid"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-dashboard-and-card [dash card]
        (is (= {:name true, :dashcards 1, :tabs 0}
               (fetch-public-dashboard dash)))
        (testing "We shouldn't see Cards that have been archived"
          (t2/update! :model/Card (u/the-id card) {:archived true})
          (is (= {:name true, :dashcards 0, :tabs 0}
                 (fetch-public-dashboard dash)))))
      (testing "dashboard with tabs should return tabs"
        (api.dashboard-test/with-simple-dashboard-with-tabs [{:keys [dashboard-id]}]
          (t2/update! :model/Dashboard :id dashboard-id (shared-obj))
          (is (= {:name true, :dashcards 2, :tabs 2}
                 (fetch-public-dashboard (t2/select-one :model/Dashboard :id dashboard-id)))))))))

(deftest public-dashboard-with-implicit-action-only-expose-unhidden-fields
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (mt/test-drivers (mt/normal-drivers-with-feature :actions)
      (mt/with-actions-test-data-tables #{"venues" "categories"}
        (mt/with-actions-test-data-and-actions-enabled
          (mt/with-actions [{card-id :id} {:type :model, :dataset_query (mt/mbql-query venues {:fields [$id $name $price]})}
                            {:keys [action-id]} {:type :implicit
                                                 :kind "row/update"
                                                 :visualization_settings {:fields {"id"    {:id     "id"
                                                                                            :hidden false}
                                                                                   "name"  {:id     "name"
                                                                                            :hidden false}
                                                                                   "price" {:id     "price"
                                                                                            :hidden true}}}}]
            (let [dashboard-uuid (str (random-uuid))]
              (mt/with-temp [:model/Dashboard {dashboard-id :id} {:public_uuid dashboard-uuid}
                             :model/DashboardCard dashcard {:dashboard_id dashboard-id
                                                            :action_id    action-id
                                                            :card_id      card-id}]
                (testing "Dashcard should only have id and name params"
                  (is (partial= {:dashcards [{:action {:parameters [{:id "id"} {:id "name"}]}}]}
                                (mt/user-http-request :crowberto :get 200 (format "public/dashboard/%s" dashboard-uuid)))))
                (let [execute-path (format "public/dashboard/%s/dashcard/%s/execute" dashboard-uuid (:id dashcard))]
                  (testing "Prefetch should only return non-hidden fields"
                    (is (= {:id 1 :name "Red Medicine"} ; price is hidden
                           (mt/user-http-request :crowberto :get 200 execute-path :parameters (json/encode {:id 1})))))
                  (testing "Update should not allow hidden fields to be updated"
                    (is (= {:rows-updated [1]}
                           (mt/user-http-request :crowberto :post 200 execute-path {:parameters {"id" 1 "name" "Blueberries"}})))
                    (is (= "An error occurred."
                           (mt/user-http-request :crowberto :post 400 execute-path {:parameters {"id" 1 "name" "Blueberries" "price" 1234}})))))))))))))

(deftest get-public-dashboard-actions-test
  (testing "GET /api/public/dashboard/:uuid"
    (mt/with-actions-test-data-and-actions-enabled
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (with-temp-public-dashboard [dash {:parameters []}]
          (mt/with-actions [{:keys [action-id model-id]} {:visualization_settings {:fields {"id"   {:id     "id"
                                                                                                    :hidden true}
                                                                                            "name" {:id     "name"
                                                                                                    :hidden false}}}}]
            (mt/with-temp [:model/DashboardCard _ {:dashboard_id (:id dash)
                                                   :action_id action-id
                                                   :card_id model-id}]
              (let [public-action (-> (client/client :get 200 (format "public/dashboard/%s" (:public_uuid dash)))
                                      :dashcards first :action)]
                (testing "hidden action fields should not be included in the response"
                  (is (partial= [:name] ; id is hidden
                                (-> public-action :visualization_settings :fields keys))))
                (testing "the action should only include the columns shown for public actions"
                  (= #{:name
                       :id
                       :database_id
                       :visualization_settings
                       :parameters}
                     (set (keys public-action))))))))))))

;;; --------------------------------- GET /api/public/dashboard/:uuid/card/:card-id ----------------------------------

(defn dashcard-url
  "URL for fetching results of a public DashCard."
  [dash card dashcard]
  (format "public/dashboard/%s/dashcard/%d/card/%d" (:public_uuid dash) (u/the-id dashcard) (u/the-id card)))

(deftest execute-public-dashcard-errors-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing "Shouldn't be able to execute a public DashCard if public sharing is disabled"
      (mt/with-temporary-setting-values [enable-public-sharing false]
        (with-temp-public-dashboard-and-card [dash card dashcard]
          (is (= "An error occurred."
                 (client/client :get 400 (dashcard-url dash card dashcard)))))))

    (testing "Should get a 404"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (with-temp-public-dashboard-and-card [dash card dashcard]
          (testing "if the Dashboard doesn't exist"
            (is (= "Not found."
                   (client/client :get 404 (dashcard-url {:public_uuid (random-uuid)} card dashcard)))))

          (testing "if the Card doesn't exist"
            (is (= "Not found."
                   (client/client :get 404 (dashcard-url dash Integer/MAX_VALUE dashcard)))))

          (testing "if the Card exists, but it's not part of this Dashboard"
            (mt/with-temp [:model/Card card]
              (is (= "Not found."
                     (client/client :get 404 (dashcard-url dash card dashcard))))))

          (testing "if the Card has been archived."
            (t2/update! :model/Card (u/the-id card) {:archived true})
            (is (= "Not found."
                   (client/client :get 404 (dashcard-url dash card dashcard))))))))))

(deftest execute-public-dashcard-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-dashboard-and-card [dash card dashcard]
        (is (= [[100]]
               (mt/rows (client/client :get 202 (dashcard-url dash card dashcard)))))

        (testing "with parameters"
          (is (=? {:json_query {:parameters [{:id     "_VENUE_ID_"
                                              :name   "Venue ID"
                                              :slug   "venue_id"
                                              :target ["dimension" ["field" (mt/id :venues :id) nil]]
                                              :value  [10]
                                              :type   "id"}]}
                   :data       {:rows [[1]]}}
                  (client/client :get 202 (dashcard-url dash card dashcard)
                                 :parameters (json/encode [{:name   "Venue ID"
                                                            :slug   :venue_id
                                                            :target [:dimension (mt/id :venues :id)]
                                                            :value  [10]
                                                            :id     "_VENUE_ID_"}])))))))))

(deftest execute-public-dashcard-with-default-parameters-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id with parameters with default values"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-dashboard [dash]
        (mt/with-temp
          [:model/Card card (merge {:enable_embedding true
                                    :dataset_query
                                    {:database (mt/id)
                                     :type     :native
                                     :native   {:query         "SELECT count(*) AS Count FROM venues where {{venue_id}}"
                                                :template-tags {"venue_id" {:dimension    [:field (mt/id :venues :id) nil]
                                                                            :display-name "Venue ID"
                                                                            :id           "_VENUE_ID_"
                                                                            :name         "venue_id"
                                                                            :required     false
                                                                            :default      1
                                                                            :type         :dimension
                                                                            :widget-type  :id}}}}}
                                   (shared-obj))]
          (let [dashcard (add-card-to-dashboard! card dash {:parameter_mappings [{:parameter_id "_VENUE_ID_"
                                                                                  :card_id      (u/the-id card)
                                                                                  :target       ["dimension" ["template-tag" "venue_id"]]}]})]
            (testing "the default should apply if no param value is provided"
              (is (= [[1]]
                     (mt/rows (client/client :get 202 (dashcard-url dash card dashcard))))))
            (testing "the field filter should not apply if the provided param value is nil"
              (is (= [[100]]
                     (mt/rows (client/client :get 202 (dashcard-url dash card dashcard)
                                             :parameters (json/encode [{:name   "Venue ID"
                                                                        :target ["dimension" ["template-tag" "venue_id"]]
                                                                        :value  nil
                                                                        :id     "_VENUE_ID_"}]))))))))))))

(deftest execute-public-dashcard-as-user-without-perms-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing "A user that doesn't have permissions to run the query normally should still be able to run a public DashCard"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (mt/with-temp [:model/Collection {collection-id :id}]
          (perms/revoke-collection-permissions! (perms-group/all-users) collection-id)
          (with-temp-public-dashboard-and-card [dash {card-id :id, :as card} dashcard]
            (t2/update! :model/Card card-id {:collection_id collection-id})
            (is (= "You don't have permissions to do that."
                   (mt/user-http-request :rasta :post 403 (format "card/%d/query" card-id)))
                "Sanity check: shouldn't be allowed to run the query normally")
            (is (= [[100]]
                   (mt/rows
                    (mt/user-http-request :rasta :get 202 (dashcard-url dash card dashcard)))))))))))

(deftest execute-public-dashcard-updates-last-viewed-at
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing "Dashboard's last_viewed_at should be updated when a public DashCard is viewed"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (mt/test-helpers-set-global-values!
          (mt/with-temp [:model/Collection {collection-id :id}]
            (perms/revoke-collection-permissions! (perms-group/all-users) collection-id)
            (with-temp-public-dashboard-and-card [dash {card-id :id, :as card} dashcard]
              (let [original-last-viewed-at (t2/select-one-fn :last_viewed_at :model/Dashboard (:id dash))]
                (t2/update! :model/Card card-id {:collection_id collection-id})
                (mt/with-temporary-setting-values [synchronous-batch-updates true]
                  (mt/user-http-request :rasta :get 202 (dashcard-url dash card dashcard)))
                (is (not= original-last-viewed-at (t2/select-one-fn :last_viewed_at :model/Dashboard :id (:id dash))))))))))))

(deftest execute-public-dashcard-params-validation-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing "Make sure params are validated"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (with-temp-public-dashboard-and-card [dash card dashcard]
          (testing "Should work correctly with a valid parameter"
            (is (= [[1]]
                   (mt/rows (client/client :get 202 (dashcard-url dash card dashcard)
                                           :parameters (json/encode [{:name   "Venue ID"
                                                                      :target [:dimension (mt/id :venues :id)]
                                                                      :value  [10]
                                                                      :id     "_VENUE_ID_"}]))))
                "This should pass because venue_id *is* one of the Dashboard's :parameters"))

          (testing "should fail if"
            (testing "a parameter is passed that is not one of the Dashboard's parameters"
              (is (= "An error occurred."
                     (client/client :get 400 (dashcard-url dash card dashcard)
                                    :parameters (json/encode [{:name   "Venue Name"
                                                               :target [:dimension (mt/id :venues :name)]
                                                               :value  ["PizzaHacker"]
                                                               :id     "_VENUE_NAME_"}])))))))))))

(deftest execute-public-dashcard-additional-series-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing "should work with an additional Card series"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (with-temp-public-dashboard-and-card [dash card dashcard]
          (with-temp-public-card [card-2]
            (mt/with-temp [:model/DashboardCardSeries _ {:dashboardcard_id (t2/select-one-pk :model/DashboardCard
                                                                                             :card_id      (u/the-id card)
                                                                                             :dashboard_id (u/the-id dash))
                                                         :card_id          (u/the-id card-2)}]
              (is (= [[100]]
                     (mt/rows (client/client :get 202 (dashcard-url dash card-2 dashcard))))))))))))

(deftest execute-public-dashcard-parameters-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing "Make sure parameters work correctly (#7212)"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (testing "with native queries and template tag params"
          (mt/with-temp [:model/Card card {:dataset_query {:database (mt/id)
                                                           :type     :native
                                                           :native   {:query         "SELECT {{num}} AS num"
                                                                      :template-tags {:num {:name         "num"
                                                                                            :display-name "Num"
                                                                                            :type         "number"
                                                                                            :required     true
                                                                                            :default      "1"}}}}}]
            (with-temp-public-dashboard [dash {:parameters [{:name "Num"
                                                             :slug "num"
                                                             :id   "_NUM_"
                                                             :type "category"}]}]
              (let [dashcard (add-card-to-dashboard!
                              card
                              dash
                              :parameter_mappings [{:card_id      (u/the-id card)
                                                    :target       [:variable [:template-tag :num]]
                                                    :parameter_id "_NUM_"}])]
                (is (= [[50]]
                       (-> (mt/user-http-request :crowberto
                                                 :get (dashcard-url dash card dashcard)
                                                 :parameters (json/encode
                                                              [{:type   :category
                                                                :target [:variable [:template-tag :num]]
                                                                :value  "50"
                                                                :id     "_NUM_"}]))
                           mt/rows)))))))

        (testing "with MBQL queries"
          (testing "`:id` parameters"
            (mt/with-temp [:model/Card card {:dataset_query (mt/mbql-query venues
                                                              {:aggregation [:count]})}]
              (with-temp-public-dashboard [dash {:parameters [{:name "venue_id"
                                                               :slug "venue_id"
                                                               :id   "_VENUE_ID_"
                                                               :type "id"}]}]
                (let [dashcard (add-card-to-dashboard!
                                card
                                dash
                                :parameter_mappings [{:parameter_id "_VENUE_ID_"
                                                      :card_id      (u/the-id card)
                                                      :target       [:dimension [:field (mt/id :venues :id) nil]]}])]
                  (is (= [[1]]
                         (-> (mt/user-http-request :crowberto
                                                   :get (dashcard-url dash card dashcard)
                                                   :parameters (json/encode
                                                                [{:type   :id
                                                                  :target [:dimension [:field (mt/id :venues :id) nil]]
                                                                  :value  "50"
                                                                  :id     "_VENUE_ID_"}]))
                             mt/rows)))))))

          (testing "temporal parameters"
            (mt/with-temporary-setting-values [enable-public-sharing true]
              (mt/with-temp [:model/Card card {:dataset_query (mt/mbql-query checkins
                                                                {:aggregation [:count]})}]
                (with-temp-public-dashboard [dash {:parameters [{:name "Date Filter"
                                                                 :slug "date_filter"
                                                                 :id   "_DATE_"
                                                                 :type "date/all-options"}]}]
                  (let [dashcard (add-card-to-dashboard!
                                  card
                                  dash
                                  :parameter_mappings [{:parameter_id "_DATE_"
                                                        :card_id      (u/the-id card)
                                                        :target       [:dimension
                                                                       [:field
                                                                        (mt/id :checkins :date) nil]]}])]
                    (is (= [[733]]
                           (-> (mt/user-http-request :crowberto
                                                     :get (dashcard-url dash card dashcard)
                                                     :parameters (json/encode
                                                                  [{:type   "date/all-options"
                                                                    :target [:dimension [:field (mt/id :checkins :date) nil]]
                                                                    :value  "~2015-01-01"
                                                                    :id     "_DATE_"}]))
                               mt/rows)))))))))))))

(deftest execute-public-dashcard-dimension-value-params-test
  (testing "GET /api/public/dashboard/:uuid/card/:card-id"
    (testing (str "make sure DimensionValue params also work if they have a default value, even if some is passed in "
                  "for some reason as part of the query (#7253)")
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (mt/with-temp [:model/Card card {:dataset_query {:database (mt/id)
                                                         :type     :native
                                                         :native   {:query         "SELECT {{msg}} AS message"
                                                                    :template-tags {:msg {:id           "_MSG_
"
                                                                                          :name         "msg"
                                                                                          :display-name "Message"
                                                                                          :type         "text"
                                                                                          :required     true
                                                                                          :default      "Wow"}}}}}]
          (with-temp-public-dashboard [dash {:parameters [{:name "Message"
                                                           :slug "msg"
                                                           :id   "_MSG_"
                                                           :type "category"}]}]
            (let [dashcard (add-card-to-dashboard! card dash
                                                   :parameter_mappings [{:card_id      (u/the-id card)
                                                                         :target       [:variable [:template-tag :msg]]
                                                                         :parameter_id "_MSG_"}])]
              (is (= [["World"]]
                     (-> (mt/user-http-request :crowberto
                                               :get (dashcard-url dash card dashcard)
                                               :parameters (json/encode
                                                            [{:type    :category
                                                              :target  [:variable [:template-tag :msg]]
                                                              :value   "World"
                                                              :default "Hello"
                                                              :id      "_MSG_"}]))
                         mt/rows))))))))))

;;; --------------------------- Check that parameter information comes back with Dashboard ---------------------------

(deftest double-check-that-the-field-has-fieldvalues
  (is (= [1 2 3 4]
         (t2/select-one-fn :values :model/FieldValues :field_id (mt/id :venues :price)))))

(defn- price-param-values []
  {(mt/id :venues :price) {:values                [1 2 3 4]
                           :human_readable_values []
                           :field_id              (mt/id :venues :price)}})

(defn- add-price-param-to-dashboard! [dashboard]
  (t2/update! :model/Dashboard (u/the-id dashboard) {:parameters [{:name "Price", :type "category", :slug "price", :id "_PRICE_"}]}))

(defn- add-dimension-param-mapping-to-dashcard! [dashcard card dimension]
  (t2/update! :model/DashboardCard (u/the-id dashcard) {:parameter_mappings [{:card_id (u/the-id card)
                                                                              :target  ["dimension" dimension]}]}))

(defn- GET-param-values! [dashboard]
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (:param_values (client/client :get 200 (str "public/dashboard/" (:public_uuid dashboard))))))

(deftest check-that-param-info-comes-back-for-sql-cards
  (with-temp-public-dashboard-and-card [dash card dashcard]
    (t2/update! :model/Card (u/the-id card)
                {:dataset_query {:database (mt/id)
                                 :type     :native
                                 :native   {:template-tags {:price {:name         "price"
                                                                    :display-name "Price"
                                                                    :type         "dimension"
                                                                    :dimension    ["field" (mt/id :venues :price) nil]}}}}})
    (add-price-param-to-dashboard! dash)
    (add-dimension-param-mapping-to-dashcard! dashcard card ["template-tag" "price"])
    (is (= (price-param-values)
           (GET-param-values! dash)))))

(deftest check-that-param-info-comes-back-for-mbql-cards--field-id-
  (with-temp-public-dashboard-and-card [dash card dashcard]
    (add-price-param-to-dashboard! dash)
    (add-dimension-param-mapping-to-dashcard! dashcard card ["field" (mt/id :venues :price) nil])
    (is (= (price-param-values)
           (GET-param-values! dash)))))

(deftest check-that-param-info-comes-back-for-mbql-cards--fk---
  (with-temp-public-dashboard-and-card [dash card dashcard]
    (add-price-param-to-dashboard! dash)
    (add-dimension-param-mapping-to-dashcard! dashcard card [:field (mt/id :venues :price) {:source-field (mt/id :checkins :venue_id)}])
    (is (= (price-param-values)
           (GET-param-values! dash)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                        New FieldValues search endpoints                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- mbql-card-referencing-nothing []
  {:dataset_query {:database (mt/id)
                   :type     :query
                   :query    {:source-table (mt/id :venues)}}})

(defn mbql-card-referencing [table-kw field-kw]
  {:dataset_query
   {:database (mt/id)
    :type     :query
    :query    {:source-table (mt/id table-kw)
               :filter       [:= [:field (mt/id table-kw field-kw) nil] "Krua Siri"]}}})

(defn- mbql-card-referencing-venue-name []
  (mbql-card-referencing :venues :name))

(defn- sql-card-referencing-venue-name []
  {:dataset_query
   {:database (mt/id)
    :type     :native
    :native   {:query         "SELECT COUNT(*) FROM VENUES WHERE {{x}}"
               :template-tags {:x {:name         :x
                                   :display-name "X"
                                   :type         :dimension
                                   :dimension    [:field (mt/id :venues :name) nil]}}}}})

;;; ------------------------------------------- card->referenced-field-ids -------------------------------------------

(deftest card-referencing-nothing
  (mt/with-temp [:model/Card card (mbql-card-referencing-nothing)]
    (is (= #{}
           (#'api.public/card->referenced-field-ids card)))))

(deftest it-should-pick-up-on-fields-referenced-in-the-mbql-query-itself
  (mt/with-temp [:model/Card card (mbql-card-referencing-venue-name)]
    (is (= #{(mt/id :venues :name)}
           (#'api.public/card->referenced-field-ids card)))))

(deftest ---as-well-as-template-tag--implict--params-for-sql-queries
  (mt/with-temp [:model/Card card (sql-card-referencing-venue-name)]
    (is (= #{(mt/id :venues :name)}
           (#'api.public/card->referenced-field-ids card)))))

;;; --------------------------------------- check-field-is-referenced-by-card ----------------------------------------

(deftest check-that-the-check-succeeds-when-field-is-referenced
  (mt/with-temp [:model/Card card (mbql-card-referencing-venue-name)]
    (#'api.public/check-field-is-referenced-by-card (mt/id :venues :name) (u/the-id card))))

(deftest check-that-exception-is-thrown-if-the-field-isn-t-referenced
  (is (thrown?
       Exception
       (mt/with-temp [:model/Card card (mbql-card-referencing-venue-name)]
         (#'api.public/check-field-is-referenced-by-card (mt/id :venues :category_id) (u/the-id card))))))

;;; ----------------------------------------- check-search-field-is-allowed ------------------------------------------

;; search field is allowed IF:
;; A) search-field is the same field as the other one
(deftest search-field-allowed-if-same-field-as-other-one
  (#'api.public/check-search-field-is-allowed (mt/id :venues :id) (mt/id :venues :id))
  (is (thrown? Exception
               (#'api.public/check-search-field-is-allowed (mt/id :venues :id) (mt/id :venues :category_id)))))

;; B) there's a Dimension that lists search field as the human_readable_field for the other field
(deftest search-field-allowed-with-dimension
  (is (mt/with-temp [:model/Dimension _ {:field_id (mt/id :venues :id), :human_readable_field_id (mt/id :venues :category_id)}]
        (#'api.public/check-search-field-is-allowed (mt/id :venues :id) (mt/id :venues :category_id)))))

;; C) search-field is a Name Field belonging to the same table as the other field, which is a PK
(deftest search-field-allowed-with-name-field
  (is (#'api.public/check-search-field-is-allowed (mt/id :venues :id) (mt/id :venues :name))))

;; not allowed if search field isn't a NAME
(deftest search-field-not-allowed-if-search-field-isnt-a-name
  (is (thrown? Exception
               (mt/with-temp-vals-in-db :model/Field (mt/id :venues :name) {:semantic_type "type/Latitude"}
                 (#'api.public/check-search-field-is-allowed (mt/id :venues :id) (mt/id :venues :name))))))

(deftest not-allowed-if-search-field-belongs-to-a-different-table
  (is (thrown? Exception
               (mt/with-temp-vals-in-db :model/Field (mt/id :categories :name) {:semantic_type "type/Name"}
                 (#'api.public/check-search-field-is-allowed (mt/id :venues :id) (mt/id :categories :name))))))

;;; ------------------------------------- check-field-is-referenced-by-dashboard -------------------------------------

(defn- dashcard-with-param-mapping-to-venue-id [dashboard card]
  {:dashboard_id       (u/the-id dashboard)
   :card_id            (u/the-id card)
   :parameter_mappings [{:card_id (u/the-id card)
                         :target  [:dimension [:field (mt/id :venues :id) nil]]}]})

(deftest field-is--referenced--by-dashboard-if-it-s-one-of-the-dashboard-s-params---
  (is (mt/with-temp [:model/Dashboard     dashboard {}
                     :model/Card          card {}
                     :model/DashboardCard _ (dashcard-with-param-mapping-to-venue-id dashboard card)]
        (#'api.public/check-field-is-referenced-by-dashboard (mt/id :venues :id) (u/the-id dashboard)))))

(deftest field-not-found-on-dashcard
  (is (thrown? Exception
               (mt/with-temp [:model/Dashboard     dashboard {}
                              :model/Card          card {}
                              :model/DashboardCard _ (dashcard-with-param-mapping-to-venue-id dashboard card)]
                 (#'api.public/check-field-is-referenced-by-dashboard (mt/id :venues :name) (u/the-id dashboard))))))

;; ...*or* if it's a so-called "implicit" param (a Field Filter Template Tag (FFTT) in a SQL Card)
(deftest implicit-param
  (is (mt/with-temp [:model/Dashboard     dashboard {}
                     :model/Card          card (sql-card-referencing-venue-name)
                     :model/DashboardCard _ {:dashboard_id (u/the-id dashboard), :card_id (u/the-id card)}]
        (#'api.public/check-field-is-referenced-by-dashboard (mt/id :venues :name) (u/the-id dashboard))))

  (is (thrown? Exception
               (mt/with-temp [:model/Dashboard     dashboard {}
                              :model/Card          card (sql-card-referencing-venue-name)
                              :model/DashboardCard _ {:dashboard_id (u/the-id dashboard), :card_id (u/the-id card)}]
                 (#'api.public/check-field-is-referenced-by-dashboard (mt/id :venues :id) (u/the-id dashboard))))))

;;; ------------------------------------------- card-and-field-id->values --------------------------------------------

(deftest we-should-be-able-to-get-values-for-a-field-referenced-by-a-card
  (mt/with-temp [:model/Card card (mbql-card-referencing :venues :name)]
    (is (= {:values          [["20th Century Cafe"]
                              ["25°"]
                              ["33 Taps"]
                              ["800 Degrees Neapolitan Pizzeria"]
                              ["BCD Tofu House"]]
            :field_id        (mt/id :venues :name)
            :has_more_values false}
           (mt/derecordize (-> (api.public/card-and-field-id->values (u/the-id card) (mt/id :venues :name))
                               (update :values (partial take 5))))))))

(deftest sql-param-field-references-should-work-just-as-well-as-mbql-field-referenced
  (mt/with-temp [:model/Card card (sql-card-referencing-venue-name)]
    (is (= {:values          [["20th Century Cafe"]
                              ["25°"]
                              ["33 Taps"]
                              ["800 Degrees Neapolitan Pizzeria"]
                              ["BCD Tofu House"]]
            :field_id        (mt/id :venues :name)
            :has_more_values false}
           (mt/derecordize (-> (api.public/card-and-field-id->values (u/the-id card) (mt/id :venues :name))
                               (update :values (partial take 5))))))))

(deftest but-if-the-field-is-not-referenced-we-should-get-an-exception
  (mt/with-temp [:model/Card card (mbql-card-referencing :venues :price)]
    (is (thrown?
         Exception
         (api.public/card-and-field-id->values (u/the-id card) (mt/id :venues :name))))))

;;; ------------------------------------------- GET /api/public/action/:uuid -------------------------------------------

(deftest fetch-action-test
  (testing "GET /api/public/action/:uuid"
    (mt/with-actions-enabled
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (testing "should return 404 if Action doesn't exist"
          (is (= "Not found."
                 (client/client :get 404 (str "public/action/" (random-uuid))))))
        (let [action-opts (assoc-in (shared-obj) [:visualization_settings :fields] {"id" {:id "id"
                                                                                          :hidden true}
                                                                                    "name" {:id "name"
                                                                                            :hidden false}})
              uuid        (:public_uuid action-opts)]
          (testing "should return 404 if Action is archived"
            (mt/with-actions [{} (assoc action-opts :archived true)]
              (is (= "Not found."
                     (client/client :get 404 (str "public/action/" uuid))))))
          (mt/with-actions [{} action-opts]
            (let [public-action (client/client :get 200 (str "public/action/" uuid))]
              (testing "Happy path -- should be able to fetch the Action"
                (is (= #{:name
                         :id
                         :database_id
                         :visualization_settings
                         :parameters}
                       (set (keys public-action)))))
              (testing "parameters should not contain hidden fields"
                (is (= ["name"] ; "id" is hidden
                       (map :id (get public-action :parameters)))))
              (testing "visualization_settings.fields should not contain hidden fields"
                (is (= [:name] ; these keys are keywordized by client/client
                       (keys (get-in public-action [:visualization_settings :fields]))))))
            (testing "Check that we cannot fetch a public Action if public sharing is disabled"
              (mt/with-temporary-setting-values [enable-public-sharing false]
                (is (= "An error occurred."
                       (client/client :get 400 (str "public/action/" (:public_uuid action-opts)))))))
            (testing "Check that we cannot fetch a public Action if actions are disabled on the database"
              (mt/with-actions-disabled
                (is (= "An error occurred."
                       (client/client :get 400 (str "public/action/" (:public_uuid action-opts)))))))))))))

;;; ------------------------------- GET /api/public/card/:uuid/field/:field/values nil --------------------------------

(defn- field-values-url [card-or-dashboard field-or-id]
  (str "public/"
       (condp mi/instance-of? card-or-dashboard
         :model/Card      "card"
         :model/Dashboard "dashboard")
       "/" (or (:public_uuid card-or-dashboard)
               (throw (Exception. (str "Missing public UUID: " card-or-dashboard))))
       "/field/" (u/the-id field-or-id)
       "/values"))

(defn- do-with-sharing-enabled-and-temp-card-referencing! [table-kw field-kw f]
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (mt/with-temp [:model/Card card (merge (shared-obj) (mbql-card-referencing table-kw field-kw))]
      (f card))))

(defmacro ^:private with-sharing-enabled-and-temp-card-referencing!
  {:style/indent 3}
  [table-kw field-kw [card-binding] & body]
  `(do-with-sharing-enabled-and-temp-card-referencing!
    ~table-kw ~field-kw
    (fn [~card-binding]
      ~@body)))

(deftest should-be-able-to-fetch-values-for-a-field-referenced-by-a-public-card
  (is (= {:values          [["20th Century Cafe"]
                            ["25°"]
                            ["33 Taps"]
                            ["800 Degrees Neapolitan Pizzeria"]
                            ["BCD Tofu House"]]
          :field_id        (mt/id :venues :name)
          :has_more_values false}
         (with-sharing-enabled-and-temp-card-referencing! :venues :name [card]
           (-> (client/client :get 200 (field-values-url card (mt/id :venues :name)))
               (update :values (partial take 5)))))))

(deftest but-for-fields-that-are-not-referenced-we-should-get-an-exception
  (is (= "Not found."
         (with-sharing-enabled-and-temp-card-referencing! :venues :name [card]
           (client/client :get 404 (field-values-url card (mt/id :venues :price)))))))

(deftest field-value-endpoint-should-fail-if-public-sharing-is-disabled
  (is (= "An error occurred."
         (with-sharing-enabled-and-temp-card-referencing! :venues :name [card]
           (mt/with-temporary-setting-values [enable-public-sharing false]
             (client/client :get 400 (field-values-url card (mt/id :venues :name))))))))

;;; ----------------------------- GET /api/public/dashboard/:uuid/field/:field/values nil -----------------------------

(defn do-with-sharing-enabled-and-temp-dashcard-referencing! [table-kw field-kw f]
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (mt/with-temp [:model/Dashboard     dashboard (shared-obj)
                   :model/Card          card      (mbql-card-referencing table-kw field-kw)
                   :model/DashboardCard dashcard  {:dashboard_id       (u/the-id dashboard)
                                                   :card_id            (u/the-id card)
                                                   :parameter_mappings [{:card_id (u/the-id card)
                                                                         :target  [:dimension
                                                                                   [:field
                                                                                    (mt/id table-kw field-kw) nil]]}]}]
      (f dashboard card dashcard))))

(defmacro with-sharing-enabled-and-temp-dashcard-referencing!
  {:style/indent 3}
  [table-kw field-kw [dashboard-binding card-binding dashcard-binding] & body]
  `(do-with-sharing-enabled-and-temp-dashcard-referencing!
    ~table-kw ~field-kw
    (fn [~(or dashboard-binding '_) ~(or card-binding '_) ~(or dashcard-binding '_)]
      ~@body)))

(deftest should-be-able-to-use-it-when-everything-is-g2g
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :name [dashboard]
    (is (= {:values          [["20th Century Cafe"]
                              ["25°"]
                              ["33 Taps"]
                              ["800 Degrees Neapolitan Pizzeria"]
                              ["BCD Tofu House"]]
            :field_id        (mt/id :venues :name)
            :has_more_values false}
           (-> (client/client :get 200 (field-values-url dashboard (mt/id :venues :name)))
               (update :values (partial take 5)))))))

(deftest shound-not-be-able-to-use-the-endpoint-with-a-field-not-referenced-by-the-dashboard
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :name [dashboard]
    (is (= "Not found."
           (client/client :get 404 (field-values-url dashboard (mt/id :venues :price)))))))

(deftest endpoint-should-fail-if-public-sharing-is-disabled
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :name [dashboard]
    (mt/with-temporary-setting-values [enable-public-sharing false]
      (is (= "An error occurred."
             (client/client :get 400 (field-values-url dashboard (mt/id :venues :name))))))))

;;; ----------------------------------------------- search-card-fields -----------------------------------------------

(deftest search-card-fields
  (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
    (is (= [[93 "33 Taps"]]
           (api.public/search-card-fields (u/the-id card) (mt/id :venues :id) (mt/id :venues :name) "33 T" 10)))))

(deftest shouldn-t-work-if-the-search-field-isn-t-allowed-to-be-used-in-combination-with-the-other-field
  (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
    (is (thrown?
         Exception
         (api.public/search-card-fields (u/the-id card) (mt/id :venues :id) (mt/id :venues :price) "33 T" 10)))))

(deftest shouldn-t-work-if-the-field-isn-t-referenced-by-card
  (with-sharing-enabled-and-temp-card-referencing! :venues :name [card]
    (is (thrown?
         Exception
         (api.public/search-card-fields (u/the-id card) (mt/id :venues :id) (mt/id :venues :id) "33 T" 10)))))

;;; ----------------------- GET /api/public/card/:uuid/field/:field/search/:search-field-id nil -----------------------

(defn- field-search-url [card-or-dashboard field-or-id search-field-or-id]
  (str "public/"
       (condp mi/instance-of? card-or-dashboard
         :model/Card      "card"
         :model/Dashboard "dashboard")
       "/" (:public_uuid card-or-dashboard)
       "/field/" (u/the-id field-or-id)
       "/search/" (u/the-id search-field-or-id)))

(deftest field-search-with-venue
  (is (= [[93 "33 Taps"]]
         (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
           (client/client :get 200 (field-search-url card (mt/id :venues :id) (mt/id :venues :name))
                          :value "33 T")))))

(deftest if-search-field-isn-t-allowed-to-be-used-with-the-other-field-endpoint-should-return-exception
  (is (= "An error occurred."
         (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
           (client/client :get 400 (field-search-url card (mt/id :venues :id) (mt/id :venues :price))
                          :value "33 T")))))

(deftest search-endpoint-should-fail-if-public-sharing-is-disabled
  (is (= "An error occurred."
         (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
           (mt/with-temporary-setting-values [enable-public-sharing false]
             (client/client :get 400 (field-search-url card (mt/id :venues :id) (mt/id :venues :name))
                            :value "33 T"))))))

;;; -------------------- GET /api/public/dashboard/:uuid/field/:field/search/:search-field-id nil ---------------------

(deftest dashboard
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :id [dashboard]
    (is (= [[93 "33 Taps"]]
           (client/client :get (field-search-url dashboard (mt/id :venues :id) (mt/id :venues :name))
                          :value "33 T")))))

(deftest dashboard-if-search-field-isn-t-allowed-to-be-used-with-the-other-field-endpoint-should-return-exception
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :id [dashboard]
    (is (= "An error occurred."
           (client/client :get 400 (field-search-url dashboard (mt/id :venues :id) (mt/id :venues :price))
                          :value "33 T")))))

(deftest dashboard-endpoint-should-fail-if-public-sharing-is-disabled
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :id [dashboard]
    (mt/with-temporary-setting-values [enable-public-sharing false]
      (is (= "An error occurred."
             (client/client :get 400 (field-search-url dashboard (mt/id :venues :name) (mt/id :venues :name))
                            :value "33 T"))))))

;;; --------------------------------------------- field-remapped-values ----------------------------------------------

;; `field-remapped-values` should return remappings in the expected format when the combination of Fields is allowed.
;; It should parse the value string (it comes back from the API as a string since it is a query param)

(deftest should-parse-string
  (is (= [10 "Fred 62"]
         (#'api.public/field-remapped-values (mt/id :venues :id) (mt/id :venues :name) "10"))))

(deftest if-the-field-isn-t-allowed
  (is (thrown?
       Exception
       (#'api.public/field-remapped-values (mt/id :venues :id) (mt/id :venues :price) "10"))))

;;; ----------------------- GET /api/public/card/:uuid/field/:field/remapping/:remapped-id nil ------------------------

(defn- field-remapping-url [card-or-dashboard field-or-id remapped-field-or-id]
  (str "public/"
       (condp mi/instance-of? card-or-dashboard
         :model/Card      "card"
         :model/Dashboard "dashboard")
       "/" (:public_uuid card-or-dashboard)
       "/field/" (u/the-id field-or-id)
       "/remapping/" (u/the-id remapped-field-or-id)))

(deftest we-should-be-able-to-use-the-api-endpoint-and-get-the-same-results-we-get-by-calling-the-function-above-directly
  (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
    (is (= [10 "Fred 62"]
           (client/client :get 200 (field-remapping-url card (mt/id :venues :id) (mt/id :venues :name))
                          :value "10")))))

(deftest shouldn-t-work-if-card-doesn-t-reference-the-field-in-question
  (with-sharing-enabled-and-temp-card-referencing! :venues :price [card]
    (is (= "Not found."
           (client/client :get 404 (field-remapping-url card (mt/id :venues :id) (mt/id :venues :name))
                          :value "10")))))

(deftest ---or-if-the-remapping-field-isn-t-allowed-to-be-used-with-the-other-field
  (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
    (is (= "An error occurred."
           (client/client :get 400 (field-remapping-url card (mt/id :venues :id) (mt/id :venues :price))
                          :value "10")))))

(deftest ---or-if-public-sharing-is-disabled
  (with-sharing-enabled-and-temp-card-referencing! :venues :id [card]
    (mt/with-temporary-setting-values [enable-public-sharing false]
      (is (= "An error occurred."
             (client/client :get 400 (field-remapping-url card (mt/id :venues :id) (mt/id :venues :name))
                            :value "10"))))))

;;; --------------------- GET /api/public/dashboard/:uuid/field/:field/remapping/:remapped-id nil ---------------------

(deftest api-endpoint-should-return-same-results-as-function
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :id [dashboard]
    (is (= [10 "Fred 62"]
           (client/client :get 200 (field-remapping-url dashboard (mt/id :venues :id) (mt/id :venues :name))
                          :value "10")))))

(deftest field-remapping-shouldn-t-work-if-card-doesn-t-reference-the-field-in-question
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :price [dashboard]
    (is (= "Not found."
           (client/client :get 404 (field-remapping-url dashboard (mt/id :venues :id) (mt/id :venues :name))
                          :value "10")))))

(deftest remapping-or-if-the-remapping-field-isn-t-allowed-to-be-used-with-the-other-field
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :id [dashboard]
    (is (= "An error occurred."
           (client/client :get 400 (field-remapping-url dashboard (mt/id :venues :id) (mt/id :venues :price))
                          :value "10")))))

(deftest remapping-or-if-public-sharing-is-disabled
  (with-sharing-enabled-and-temp-dashcard-referencing! :venues :id [dashboard]
    (mt/with-temporary-setting-values [enable-public-sharing false]
      (is (= "An error occurred."
             (client/client :get 400 (field-remapping-url dashboard (mt/id :venues :id) (mt/id :venues :name))
                            :value "10"))))))

;;; --------------------------------------------- Param values endpoints ---------------------------------------------

(defn- param-values-url
  ([card-or-dashboard uuid param-key]
   (param-values-url card-or-dashboard uuid param-key nil))
  ([card-or-dashboard uuid param-key query]
   (str "public/"
        (name card-or-dashboard)
        "/" uuid
        "/params/" param-key
        (if query
          (str "/search/" query)
          "/values"))))

(deftest param-values-test
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (testing "with dashboard"
      (api.dashboard-test/with-chain-filter-fixtures [{:keys [dashboard param-keys]}]
        (let [uuid (str (random-uuid))]
          (is (= 1
                 (t2/update! :model/Dashboard (u/the-id dashboard) {:public_uuid uuid})))
          (testing "GET /api/public/dashboard/:uuid/params/:param-key/values"
            (testing "parameter with source is a static list"
              (is (= {:values          [["African"] ["American"] ["Asian"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :dashboard uuid (:static-category param-keys))))))

            (testing "parameter with source is card"
              (is (= {:values          [["African"] ["American"] ["Artisan"] ["Asian"] ["BBQ"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :dashboard uuid (:card param-keys))))))

            (testing "parameter with source is chain filter"
              (is (= {:values          [[2] [3] [4] [5] [6]]
                      :has_more_values false}
                     (->> (client/client :get 200 (param-values-url :dashboard uuid (:category-id param-keys)))
                          (chain-filter-test/take-n-values 5))))))

          (testing "GET /api/public/dashboard/:uuid/params/:param-key/search/:query"
            (testing "parameter with source is a static list"
              (is (= {:values          [["African"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :dashboard uuid (:static-category param-keys) "af")))))

            (testing "parameter with source is card"
              (is (= {:values          [["African"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :dashboard uuid (:card param-keys) "afr")))))

            (testing "parameter with source is a chain filter"
              (is (= {:values          [["Fast Food"] ["Food Truck"] ["Seafood"]]
                      :has_more_values false}
                     (->> (client/client :get 200 (param-values-url :dashboard uuid (:category-name param-keys) "food"))
                          (chain-filter-test/take-n-values 3)))))))))

    (testing "with card"
      (api.card-test/with-card-param-values-fixtures [{:keys [card field-filter-card param-keys]}]
        (let [card-uuid (str (random-uuid))
              field-filter-uuid (str (random-uuid))]
          (is (= 1
                 (t2/update! :model/Card (u/the-id card) {:public_uuid card-uuid}))
              "Enabled public setting on card")
          (is (= 1
                 (t2/update! :model/Card (u/the-id field-filter-card) {:public_uuid field-filter-uuid}))
              "Enabled public setting on field-filter-card")
          (testing "GET /api/public/card/:uuid/params/:param-key/values"
            (testing "parameter with source is a static list"
              (is (= {:values          [["African"] ["American"] ["Asian"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :card card-uuid (:static-list param-keys))))))

            (testing "parameter with source is a card"
              (is (= {:values          [["20th Century Cafe"] ["25°"] ["33 Taps"]
                                        ["800 Degrees Neapolitan Pizzeria"] ["BCD Tofu House"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :card card-uuid (:card param-keys))))))

            (testing "parameter with source is a field filter"
              (testing "parameter with source is a card"
                (let [resp (client/client
                            :get 200
                            (param-values-url :card field-filter-uuid
                                              (:field-values param-keys)))]
                  (is (false? (:has_more_values resp)))
                  (is (set/subset? #{["20th Century Cafe"] ["33 Taps"]}
                                   (-> resp :values set)))))))

          (testing "GET /api/public/card/:uuid/params/:param-key/search/:query"
            (testing "parameter with source is a static list"
              (is (= {:values          [["African"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :card card-uuid (:static-list param-keys) "af")))))

            (testing "parameter with source is a card"
              (is (= {:values          [["Fred 62"] ["Red Medicine"]]
                      :has_more_values false}
                     (client/client :get 200 (param-values-url :card card-uuid (:card param-keys) "red")))))

            (testing "parameter with source is a field-filter"
              (is (partial= {:values
                             [["Barney's Beanery"]
                              ["My Brother's Bar-B-Q"]
                              ["Tanoshi Sushi & Sake Bar"]
                              ["The Misfit Restaurant + Bar"]
                              ["Two Sisters Bar & Books"]
                              ["bigmista's barbecue"]]
                             :has_more_values true}
                            (client/client
                             :get 200
                             (param-values-url :card field-filter-uuid
                                               (:field-values param-keys) "bar")))))))))))

(deftest dashboard-field-params-field-names-test
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (mt/with-temp
      [:model/Dashboard     dash      {:parameters [{:name "Category Name"
                                                     :slug "category_name"
                                                     :id   "_CATEGORY_NAME_"
                                                     :type "category"}]
                                       :public_uuid (str (random-uuid))}
       :model/Card          card      {:name "Card attached to dashcard"
                                       :dataset_query {:database (mt/id)
                                                       :type     :query
                                                       :query    {:source-table (mt/id :categories)}}
                                       :type :model}
       :model/DashboardCard _         {:dashboard_id       (:id dash)
                                       :card_id            (:id card)
                                       :parameter_mappings [{:parameter_id "_CATEGORY_NAME_"
                                                             :target       [:dimension (mt/$ids *categories.name)]}]}]
      (is (=? {:param_fields {(mt/id :categories :name)
                              {:semantic_type "type/Name",
                               :table_id (mt/id :categories)
                               :name "NAME",
                               :has_field_values "list",
                               :fk_target_field_id nil,
                               :dimensions (),
                               :id (mt/id :categories :name)
                               :target nil,
                               :display_name "Name",
                               :name_field nil,
                               :base_type "type/Text"}}}
              (client/client :get 200 (format "public/dashboard/%s" (:public_uuid dash)))))
      (is (=? {:values #(set/subset? #{["African"] ["BBQ"]} (set %1))}
              (client/client :get 200 (format "public/dashboard/%s/params/%s/values" (:public_uuid dash) "_CATEGORY_NAME_")))))))

(deftest param-values-ignore-current-user-permissions-test
  (testing "Should not fail if request is authenticated but current user does not have data permissions"
    (mt/with-temp-copy-of-db
      (mt/with-no-data-perms-for-all-users!
        (mt/with-temporary-setting-values [enable-public-sharing true]
          (testing "with dashboard"
            (api.dashboard-test/with-chain-filter-fixtures [{:keys [dashboard param-keys]}]
              (let [uuid (str (random-uuid))]
                (is (= 1
                       (t2/update! :model/Dashboard (u/the-id dashboard) {:public_uuid uuid})))
                (testing "GET /api/public/dashboard/:uuid/params/:param-key/values"
                  (is (= {:values          [[2] [3] [4] [5] [6]]
                          :has_more_values false}
                         (->> (mt/user-http-request :rasta :get 200 (param-values-url :dashboard uuid (:category-id param-keys)))
                              (chain-filter-test/take-n-values 5)))))
                (testing "GET /api/public/dashboard/:uuid/params/:param-key/search/:prefix"
                  (is (= {:values          [["Fast Food"] ["Food Truck"] ["Seafood"]]
                          :has_more_values false}
                         (->> (mt/user-http-request :rasta :get 200 (param-values-url :dashboard uuid (:category-name param-keys) "food"))
                              (chain-filter-test/take-n-values 3))))))))

          (testing "with card"
            (api.card-test/with-card-param-values-fixtures [{:keys [card param-keys]}]
              (let [uuid (str (random-uuid))]
                (is (= 1
                       (t2/update! :model/Card (u/the-id card) {:public_uuid uuid})))
                (testing "GET /api/public/card/:uuid/params/:param-key/values"
                  (is (= {:values          [["African"] ["American"] ["Asian"]]
                          :has_more_values false}
                         (client/client :get 200 (param-values-url :card uuid (:static-list param-keys)))))

                  (is (= {:values          [["20th Century Cafe"] ["25°"] ["33 Taps"]
                                            ["800 Degrees Neapolitan Pizzeria"] ["BCD Tofu House"]]
                          :has_more_values false}
                         (client/client :get 200 (param-values-url :card uuid (:card param-keys))))))

                (testing "GET /api/public/card/:uuid/params/:param-key/search/:query"
                  (is (= {:values          [["African"]]
                          :has_more_values false}
                         (client/client :get 200 (param-values-url :card uuid (:static-list param-keys) "afr"))))

                  (is (= {:values          [["Fred 62"] ["Red Medicine"]]
                          :has_more_values false}
                         (client/client :get 200 (param-values-url :card uuid (:card param-keys) "red")))))))))))))

;;; --------------------------------------------- Pivot tables ---------------------------------------------

(deftest pivot-public-card-test
  (mt/test-drivers (api.pivots/applicable-drivers)
    (mt/dataset test-data
      (testing "GET /api/public/pivot/card/:uuid/query"
        (mt/with-temporary-setting-values [enable-public-sharing true]
          (with-temp-public-card [{uuid :public_uuid} (api.pivots/pivot-card)]
            (let [result (client/client :get 202 (format "public/pivot/card/%s/query" uuid))
                  rows   (mt/rows result)]
              (is (nil? (:row_count result))) ;; row_count isn't included in public endpoints
              (is (= "completed" (:status result)))
              (is (= 6 (count (get-in result [:data :cols]))))
              (is (= 1144 (count rows)))

              (is (= ["AK" "Affiliate" "Doohickey" 0 18 81] (first rows)))
              (is (= ["CO" "Affiliate" "Gadget" 0 62 211] (nth rows 100)))
              (is (= [nil nil nil 7 18760 69540] (last rows))))))))))

(defn- pivot-dashcard-url
  "URL for fetching results of a public DashCard."
  [dash card dashcard]
  (format "public/pivot/dashboard/%s/dashcard/%d/card/%d" (:public_uuid dash) (u/the-id dashcard) (u/the-id card)))

(defn- do-with-temp-dashboard-and-public-pivot-card [f]
  (mt/dataset test-data
    (with-temp-public-dashboard [dash {:parameters [{:id      "_STATE_"
                                                     :name    "State"
                                                     :slug    "state"
                                                     :type    "text"
                                                     :target  [:dimension (mt/$ids $orders.user_id->people.state)]
                                                     :default nil}]}]
      (with-temp-public-card [card (api.pivots/pivot-card)]
        (let [dashcard (add-card-to-dashboard!
                        card
                        dash
                        :parameter_mappings [{:parameter_id "_STATE_"
                                              :card_id      (u/the-id card)
                                              :target       [:dimension (mt/$ids $orders.user_id->people.state)]}])]
          (f dash card dashcard))))))

(deftest pivot-public-dashcard-test
  (testing "GET /api/public/pivot/dashboard/:uuid/dashcard/:dashcard-id/card/:card-id"
    (mt/test-drivers (api.pivots/applicable-drivers)
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (do-with-temp-dashboard-and-public-pivot-card
         (fn [dash card dashcard]
           (letfn [(results [& query-parameters]
                     (apply client/client :get 202 (pivot-dashcard-url dash card dashcard) query-parameters))]
             (testing "without parameters"
               (let [result (results)]
                 (is (=? {:status "completed"}
                         result))
                      ;; [[metabase.api.public/transform-results]] should remove `row_count`
                 (testing "row_count isn't included in public endpoints"
                   (is (nil? (:row_count result))))
                 (is (= 6 (count (get-in result [:data :cols]))))
                 (let [rows (mt/rows result)]
                   (is (= 1144 (count rows)))
                   (is (= ["AK" "Affiliate" "Doohickey" 0 18 81] (first rows)))
                   (is (= ["CO" "Affiliate" "Gadget" 0 62 211] (nth rows 100)))
                   (is (= [nil nil nil 7 18760 69540] (last rows))))))

             (testing "with parameters"
               (let [result (results :parameters (json/encode [{:name   "State"
                                                                :id     "_STATE_"
                                                                :slug   :state
                                                                :target [:dimension (mt/$ids $orders.user_id->people.state)]
                                                                :value  ["CA" "WA"]}]))]
                 (is (=? {:status "completed"}
                         result))
                 (testing "row_count isn't included in public endpoints"
                   (is (nil? (:row_count result))))
                 (is (= 6 (count (get-in result [:data :cols]))))
                 (let [rows (mt/rows result)]
                   (is (= 80 (count rows)))
                   (is (= ["CA" "Affiliate" "Doohickey" 0 16 48] (first rows)))
                   (is (= [nil "Google" "Gizmo" 1 52 186] (nth rows 50)))
                   (is (= [nil nil nil 7 1015 3758] (last rows)))))))))))))

(deftest public-pivot-dashcard-errors-test
  (testing "GET /api/public/pivot/dashboard/:uuid/dashcard/:dashcard-id/card/:card-id"
    (mt/test-drivers (api.pivots/applicable-drivers)
      (do-with-temp-dashboard-and-public-pivot-card
       (fn [dash card dashcard]
         (testing "Shouldn't be able to execute a public DashCard if public sharing is disabled"
           (mt/with-temporary-setting-values [enable-public-sharing false]
             (is (= "An error occurred."
                    (client/client :get 400 (pivot-dashcard-url dash card dashcard)))))
           (mt/with-temporary-setting-values [enable-public-sharing true]
             (testing "Should get a 404"
               (testing "if the Dashboard doesn't exist"
                 (is (= "Not found."
                        (client/client :get 404 (dashcard-url {:public_uuid (random-uuid)} card dashcard)))))

               (testing "if the Card doesn't exist"
                 (is (= "Not found."
                        (client/client :get 404 (dashcard-url dash Integer/MAX_VALUE dashcard)))))

               (testing "if the Card exists, but it's not part of this Dashboard"
                 (mt/with-temp [:model/Card card]
                   (is (= "Not found."
                          (client/client :get 404 (dashcard-url dash card dashcard))))))

               (testing "if the Card has been archived."
                 (t2/update! :model/Card (u/the-id card) {:archived true})
                 (is (= "Not found."
                        (client/client :get 404 (dashcard-url dash card dashcard)))))))))))))

;;; ------------------------- POST /api/public/dashboard/:dashboard-uuid/dashcard/:uuid/execute ------------------------------

(deftest execute-public-dashcard-action-test
  (mt/with-actions-test-data-and-actions-enabled
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-dashboard [dash {:parameters []}]
        (mt/with-actions [{:keys [action-id model-id]} {}]
          (mt/with-temp [:model/DashboardCard {dashcard-id :id} {:dashboard_id (:id dash)
                                                                 :action_id action-id
                                                                 :card_id model-id}]
            (with-redefs [api.public/dashcard-execution-throttle (throttle/make-throttler :dashcard-id :attempts-threshold 1)]
              (is (partial= {:rows-affected 1}
                            (client/client
                             :post 200
                             (format "public/dashboard/%s/dashcard/%s/execute"
                                     (:public_uuid dash)
                                     dashcard-id)
                             {:parameters {:id 1 :name "European"}})))
              (let [throttled-response (client/client-full-response
                                        :post 429
                                        (format "public/dashboard/%s/dashcard/%s/execute"
                                                (:public_uuid dash)
                                                dashcard-id)
                                        {:parameters {:id 1 :name "European"}})]
                (is (str/starts-with? (:body throttled-response) "Too many attempts!"))
                (is (contains? (:headers throttled-response) "Retry-After"))))))))))

(deftest execute-public-dashcard-custom-action-test
  (mt/with-temp-copy-of-db
    (mt/with-no-data-perms-for-all-users!
      (mt/with-actions-test-data-and-actions-enabled
        (mt/with-temporary-setting-values [enable-public-sharing true]
          (with-temp-public-dashboard [dash {:parameters []}]
            (mt/with-actions [{:keys [action-id model-id]} {}]
              (mt/with-temp [:model/DashboardCard {dashcard-id :id} {:dashboard_id (:id dash)
                                                                     :action_id action-id
                                                                     :card_id model-id}]
                (is (partial= {:rows-affected 1}
                              (client/client
                               :post 200
                               (format "public/dashboard/%s/dashcard/%s/execute"
                                       (:public_uuid dash)
                                       dashcard-id)
                               {:parameters {:id 1 :name "European"}})))))))))))

(deftest fetch-public-dashcard-action-test
  (mt/with-actions-test-data-and-actions-enabled
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (with-temp-public-dashboard [dash {:parameters []}]
        (mt/with-actions [{:keys [action-id model-id]} {:type :implicit}]
          (mt/with-temp [:model/DashboardCard {dashcard-id :id} {:dashboard_id (:id dash)
                                                                 :action_id action-id
                                                                 :card_id model-id}]
            (is (partial= {:id 1 :name "African"}
                          (client/client
                           :get 200
                           (format "public/dashboard/%s/dashcard/%s/execute" (:public_uuid dash) dashcard-id)
                           :parameters (json/encode {:id 1}))))))))))

;;; --------------------------------- POST /api/public/action/:uuid/execute ----------------------------------

(deftest execute-public-action-test
  (mt/with-premium-features #{:advanced-permissions}
    (mt/with-actions-test-data-and-actions-enabled
      (mt/with-no-data-perms-for-all-users!
        (mt/with-temporary-setting-values [enable-public-sharing true]
          (let [{:keys [public_uuid] :as action-opts} (shared-obj)]
            (mt/with-actions [{} action-opts]
              ;; Decrease the throttle threshold to 1 so we can test the throttle,
              ;; and set the throttle delay high enough the throttle will definitely trigger
              (with-redefs [api.public/action-execution-throttle (throttle/make-throttler :action-uuid :attempts-threshold 1 :initial-delay-ms 20000)]
                (testing "Happy path - we can execute a public action"
                  (is (=? {:rows-affected 1}
                          (client/client
                           :post 200
                           (format "public/action/%s/execute" public_uuid)
                           {:parameters {:id 1 :name "European"}}))))
                (testing "Test throttle"
                  (let [throttled-response (client/client-full-response
                                            :post 429
                                            (format "public/action/%s/execute" public_uuid)
                                            {:parameters {:id 1 :name "European"}})]
                    (is (str/starts-with? (:body throttled-response) "Too many attempts!"))
                    (is (contains? (:headers throttled-response) "Retry-After"))))))
            ;; Lift the throttle attempts threshold so we don't have to wait between requests
            (with-redefs [api.public/action-execution-throttle (throttle/make-throttler :action-uuid :attempts-threshold 1000)]
              (mt/with-actions [{} (assoc action-opts :archived true)]
                (testing "Check that we get a 400 if the action is archived"
                  (is (= "Not found."
                         (client/client
                          :post 404
                          (format "public/action/%s/execute" (str (random-uuid)))
                          {:parameters {:id 1 :name "European"}})))))
              (mt/with-actions [{} action-opts]
                (testing "Check that we get a 400 if the action doesn't exist"
                  (is (= "Not found."
                         (client/client
                          :post 404
                          (format "public/action/%s/execute" (str (random-uuid)))
                          {:parameters {:id 1 :name "European"}}))))
                (testing "Check that we get a 400 if sharing is disabled."
                  (mt/with-temporary-setting-values [enable-public-sharing false]
                    (is (= "An error occurred."
                           (client/client
                            :post 400
                            (format "public/action/%s/execute" public_uuid)
                            {:parameters {:id 1 :name "European"}})))))
                (testing "Check that we get a 400 if actions are disabled for the database."
                  (mt/with-temp-vals-in-db :model/Database (mt/id) {:settings {:database-enable-actions false}}
                    (is (= "An error occurred."
                           (client/client
                            :post 400
                            (format "public/action/%s/execute" public_uuid)
                            {:parameters {:id 1 :name "European"}})))))
                (testing "Check that we send a snowplow event when execute an action"
                  (snowplow-test/with-fake-snowplow-collector
                    (client/client
                     :post 200
                     (format "public/action/%s/execute" public_uuid)
                     {:parameters {:id 1 :name "European"}})
                    (is (= {:data   {"action_id" (t2/select-one-pk :model/Action :public_uuid public_uuid)
                                     "event"     "action_executed"
                                     "source"    "public_form"
                                     "type"      "query"}
                            :user-id nil}
                           (last (snowplow-test/pop-event-data-and-user-id!))))))))))))))

(deftest format-export-middleware-test
  (mt/with-temporary-setting-values [enable-public-sharing true]
    (testing "The `:format-export?` query processor middleware has the intended effect on file exports."
      (let [q             {:database (mt/id)
                           :type     :native
                           :native   {:query "SELECT 2000 AS number, '2024-03-26'::DATE AS date;"}}
            output-helper {:csv  (fn [output] (->> output csv/read-csv last))
                           :json (fn [output] (->> output (map (juxt :NUMBER :DATE)) last))}]
        (with-temp-public-card [{uuid :public_uuid} {:display :table :dataset_query q}]
          (doseq [[export-format apply-formatting? expected] [[:csv true ["2,000" "March 26, 2024"]]
                                                              [:csv false ["2000" "2024-03-26"]]
                                                              [:json true ["2,000" "March 26, 2024"]]
                                                              [:json false [2000 "2024-03-26"]]]]
            (testing (format "export_format %s yields expected output for %s exports." apply-formatting? export-format)
              (is (= expected
                     (->> (mt/user-http-request
                           :crowberto :get 200
                           (format "public/card/%s/query/%s?format_rows=%s" uuid (name export-format) apply-formatting?))
                          ((get output-helper export-format))))))))))))

;; This test is same as [[metabase.api.dashboard-test/dashboard-param-values-param-fields-hydration-test]]
;; adjusted to run with PUBLIC dashboard.
(deftest ^:synchronized public-dashboard-param-values-param-fields-hydration-test
  (let [public-dashboard-uuid (str (random-uuid))]
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp
        [:model/Dashboard     d   {:name "D"
                                   :public_uuid public-dashboard-uuid
                                   :parameters [{:name      "State filter param 1"
                                                 :slug      "p1"
                                                 :id        "p1"
                                                 :type      "location"}
                                                {:name      "State filter param 2"
                                                 :slug      "p2"
                                                 :id        "p2"
                                                 :type      "text"}
                                                {:name      "State filter param 3"
                                                 :slug      "p3"
                                                 :id        "p3"
                                                 :type      "text"}]}
         :model/Card          c1  (let [query {:database (mt/id)
                                               :type     :native
                                               :native   {:query "select * from people"}}]
                                    {:name "C1"
                                     :type :model
                                     :database_id (mt/id)
                                     :dataset_query query
                                     :result_metadata
                                     (mapv (fn [{col-name :name :as meta}]
                                             ;; Map model's metadata to corresponding field id
                                             (assoc meta :id  (mt/id :people (keyword (u/lower-case-en col-name)))))
                                           (-> (qp/process-query query)
                                               :data :results_metadata :columns))})

         :model/Card          c2 (let [query (mt/mbql-query nil
                                               {:source-table (str "card__" (:id c1))
                                                :aggregation
                                                [[:distinct [:field "STATE" {:base-type :type/Text}]]]})]
                                   {:name "C2"
                                    :database_id (mt/id)
                                    :dataset_query query
                                    :result_metadata (-> (qp/process-query query)
                                                         :data :results_metadata :columns)})
         :model/DashboardCard _dc1 {:dashboard_id       (:id d)
                                    :card_id            (:id c2)
                                    :parameter_mappings
                                    [{:parameter_id "p1"
                                      :target [:dimension [:field "STATE" {:base-type :type/Text}]]}
                                     {:parameter_id "p2"
                                      :target [:dimension [:field "NAME" {:base-type :type/Text}]]}
                                     {:parameter_id "p3"
                                      :target [:dimension [:field "CITY" {:base-type :type/Text}]]}]}
         :model/DashboardCard _dc2 {:dashboard_id       (:id d)
                                    :card_id            (:id c2)
                                    :parameter_mappings
                                    [{:parameter_id "p1"
                                      :target [:dimension [:field "STATE" {:base-type :type/Text}]]}
                                     {:parameter_id "p2"
                                      :target [:dimension [:field "NAME" {:base-type :type/Text}]]}
                                     {:parameter_id "p3"
                                      :target [:dimension [:field "CITY" {:base-type :type/Text}]]}]}]
        (let [call-count (volatile! 0)
              orig-filterable-columns-for-query params/filterable-columns-for-query]
          (with-redefs [params/filterable-columns-for-query
                        (fn [& args]
                          (vswap! call-count inc)
                          (apply orig-filterable-columns-for-query args))]
            (let [response (client/client :get 200 (format "public/dashboard/%s" (:public_uuid d)))]
              (testing "Baseline: expected :param_fields(#42829)"
                (is (=? {(mt/id :people :name)  {:name "NAME"}
                         (mt/id :people :state) {:name "STATE"}
                         (mt/id :people :city)  {:name "CITY"}}
                        (get response :param_fields))))
              (testing "Baseline: expected :param_values (#42829)"
                (is (=? {(mt/id :people :state) {:values ["AK" "AL"]}}
                        (-> (get response :param_values)
                            ;; Take just first 2 values for testing purposes
                            (update-in [(mt/id :people :state) :values] (partial take 2))))))
              (testing "Reasonable amount of `filterable-columns` calls performed during dashboard load"
                ;; Current implementation of [[metabase.models.params/dashcards->param-field-ids]] is supposed
                ;; to compute `filterable-columns` only once per card per dashboard load, thanks to use of (1) context
                ;; sharing of it between :param_fields and :param_values hydration. This test defines multiple
                ;; dashcards and parameters for each dashcard, linked to a single card. Following is the proof
                ;; of things working as described.
                (is (= 1 @call-count))))))))))
