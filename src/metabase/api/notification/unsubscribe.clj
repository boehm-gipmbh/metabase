(ns metabase.api.notification.unsubscribe
  "Unauthenticated `/api/notification/unsubscribe` endpoints to allow non-logged-in people to unsubscribe from
  Alerts/DashboardNotifications."
  (:require
   [compojure.core :refer [POST]]
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.config :as config]
   [metabase.email.messages :as messages]
   [metabase.events :as events]
   [metabase.request.core :as request]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli.schema :as ms]
   [throttle.core :as throttle]
   [toucan2.core :as t2]))

(def ^:private throttling-disabled? (config/config-bool :mb-disable-session-throttle))

(def ^:private unsubscribe-throttler (throttle/make-throttler :notification-unsubscribe, :attempts-threshold 50))

(defn- check-hash [notification-handler-id email hash ip-address]
  (when-not throttling-disabled?
    (throttle/check unsubscribe-throttler ip-address))
  (when (not= hash (messages/generate-notification-unsubscribe-hash notification-handler-id email))
    (throw (ex-info (tru "Invalid hash.")
                    {:status-code 400}))))

(defn- notification-name-by-handler-id
  [notification-handler-id]
  (let [notification (t2/hydrate (t2/select-one :model/Notification
                                                :id [:in {:select [:notification_id]
                                                          :from  :notification_handler
                                                          :where [:= :id notification-handler-id]}])
                                 :payload)]
    (case (:payload_type notification)
      ;; use the card name
      :notification/card (->> notification :payload :card_id (t2/select-one-fn :name :model/Card))
      ;; use the dashboard name
      :notification/dashboard (->> notification :payload :dashboard_id (t2/select-one-fn :name :model/Dashboard))
      (name (:payload_type notification)))))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint POST "/"
  "Allow non-users to unsubscribe from notifications, with the hash given through email."
  [:as {{:keys [email hash notification-handler-id]} :body, :as request}]
  {notification-handler-id ms/PositiveInt
   email                   :string
   hash                    :string}
  (check-hash notification-handler-id email hash (request/ip-address request))
  (t2/with-transaction [_conn]
    (let [recipients (t2/select :model/NotificationRecipient
                                :notification_handler_id notification-handler-id
                                :type :notification-recipient/raw-value)
          matching-recipient (m/find-first #(= email (-> % :details :value)) recipients)]
      (if matching-recipient
        (t2/delete! :model/NotificationRecipient (:id matching-recipient))
        (throw (ex-info (tru "Email doesn''t exist.") {:status-code 400})))))
  (events/publish-event! :event/notification-unsubscribe-ex {:object {:email email}})
  {:status :success :title (notification-name-by-handler-id notification-handler-id)})

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint POST "/undo"
  "Allow non-users to undo an unsubscribe from notifications, with the hash given through email."
  [:as {{:keys [email hash notification-handler-id]} :body, :as request}]
  {notification-handler-id ms/PositiveInt
   email                   :string
   hash                    :string}
  (check-hash notification-handler-id email hash (request/ip-address request))
  (t2/with-transaction [_conn]
    (let [recipients         (t2/select :model/NotificationRecipient :notification_handler_id notification-handler-id
                                        :type :notification-recipient/raw-value)
          matching-recipient (m/find-first #(= email (-> % :details :value)) recipients)]
      (if-not matching-recipient
        (t2/insert! :model/NotificationRecipient {:type                    :notification-recipient/raw-value
                                                  :details                 {:value email}
                                                  :notification_handler_id notification-handler-id})
        (throw (ex-info (tru "Email already exist.") {:status-code 400})))))
  (events/publish-event! :event/notification-unsubscribe-undo-ex {:object {:email email}})
  {:status :success :title (notification-name-by-handler-id notification-handler-id)})

(api/define-routes)
