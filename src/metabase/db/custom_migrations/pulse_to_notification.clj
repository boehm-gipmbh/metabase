(ns metabase.db.custom-migrations.pulse-to-notification
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [toucan2.core :as t2]))

(defn- cron-string
  "Build a cron string from key-value pair parts."
  [{:keys [seconds minutes hours day-of-month month day-of-week year]}]
  (str/join " " [(or seconds      "0")
                 (or minutes      "0")
                 (or hours        "*")
                 (or day-of-month "*")
                 (or month        "*")
                 (or day-of-week  "?")
                 (or year         "*")]))

(def ^:private day-of-week->cron
  {"sun"  1
   "mon"  2
   "tue"  3
   "wed"  4
   "thu"  5
   "fri"  6
   "sat"  7})

(defn- frame->cron [frame day-of-week]
  (if day-of-week
    ;; specific days of week like Mon or Fri
    (assoc {:day-of-month "?"}
           :day-of-week (case frame
                          "first" (str (day-of-week->cron day-of-week) "#1")
                          "last"  (str (day-of-week->cron day-of-week) "L")))
    ;; specific CALENDAR DAYS like 1st or 15th
    (assoc {:day-of-week "?"}
           :day-of-month (case frame
                           "first" "1"
                           "mid"   "15"
                           "last"  "L"))))

(defn- schedule-map->cron-string
  "Convert the frontend schedule map into a cron string."
  [{day-of-week :schedule_day, hour :schedule_hour, minute :schedule_minute,
    frame :schedule_frame,  schedule-type :schedule_type}]
  (cron-string (case (keyword schedule-type)
                 :hourly  {:minutes minute}
                 :daily   {:hours (or hour 0)}
                 :weekly  {:hours       hour
                           :day-of-week  (day-of-week->cron day-of-week)
                           :day-of-month "?"}
                 :monthly (assoc (frame->cron frame day-of-week)
                                 :hours hour))))

(defn- create-notification!
  "Create a new notification with `subsciptions`.
  Return the created notification."
  [notification subscriptions handlers+recipients]
  (let [notification-card-id (t2/insert-returning-pk! :notification_card (:payload notification))
        instance             (t2/insert-returning-instance! :notification (-> notification
                                                                              (dissoc :payload)
                                                                              (assoc :payload_id notification-card-id)))
        notification-id      (:id instance)]
    (when (seq subscriptions)
      (t2/insert! :notification_subscription (map #(assoc % :notification_id notification-id) subscriptions)))
    (doseq [handler handlers+recipients]
      (let [recipients (:recipients handler)
            handler    (-> handler
                           (dissoc :recipients)
                           (assoc :notification_id notification-id))
            handler-id (t2/insert-returning-pk! :notification_handler handler)]
        (t2/insert! :notification_recipient (map #(assoc % :notification_handler_id handler-id) recipients))))
    1))

(defn- hydrate-recipients
  [pcs]
  (let [pc-id->recipients (group-by :pulse_channel_id
                                    (t2/select :pulse_channel_recipient :pulse_channel_id [:in (map :id pcs)]))]
    (map (fn [pc]
           (assoc pc :recipients (get pc-id->recipients (:id pc))))
         pcs)))

(defn- pulse->notification!
  "Create a new notification with `subsciptions`.
  Return the created notification."
  [pulse alert-template-id]
  (let [pulse-id   (:id pulse)
        pcs        (hydrate-recipients (t2/select :pulse_channel :pulse_id pulse-id))
        ;; alerts have one pulse-card, but to be safe we select the latest one by id
        pulse-card (t2/select-one :pulse_card :pulse_id pulse-id {:order-by [[:id :desc]]})]
    ;; the old schema allow one alert to have multiple pulse-channels. Practically they all have the same schedule
    ;; but to be safe we group them by schedule and create a notification for each group
    (doseq [pcs (vals (group-by (juxt :schedule_type :schedule_hour :schedule_day :schedule_frame) pcs))]
      (let [notification  {:payload_type "notification/alert"
                           :payload      {:card_id        (:id pulse-card)
                                          :run_once       (:alert_first_only pulse)
                                          :skip_if_empty  (:skip_if_empty pulse)
                                          :send_condition (if (= "goal" (:alert_condition pulse))
                                                            (if (:alert_above_goal pulse)
                                                              "above_goal"
                                                              "below_goal")
                                                            "has_result")
                                          :created_at     :%now
                                          :updated_at     :%now}
                           :active       (not (:archived pulse))
                           :creator_id   (:creator_id pulse)
                           :created_at   :%now
                           :updated_at   :%now}
            subscriptions [{:type          "notification-subscription/cron"
                            :cron_schedule (schedule-map->cron-string (first pcs))
                            :created_at    :%now}]
            handlers      (map (fn [pc]
                                 (merge
                                  {:active (:enabled pc)}
                                  (case (:channel_type pc)
                                    "email"
                                    {:channel_type "channel/email"
                                     :template_id  alert-template-id
                                     :recipients   (concat
                                                    (map (fn [recipient]
                                                           {:type "notification-recipient/user"
                                                            :user_id (:user_id recipient)})
                                                         (:recipients pc))
                                                    [])}
                                    "slack"
                                    {:channel_type "channel/slack"
                                     :template_id  nil
                                     :recipients   [{:type "notification-recipient/slack-channel"
                                                     :details {:channel (get-in pc [:details :channel])}}]}
                                    "http"
                                    nil)))
                               pcs)]
        (create-notification! notification subscriptions handlers)))))

(defn migrate-alerts!
  "Migrate alerts from `pulse` to `notification`."
  []
  (let [alert-template-id (t2/insert-returning-pk! :channel_template
                                                   {:name         "Alert"
                                                    :channel_type "channel/email"
                                                    :details      (json/generate-string
                                                                   {:type    :email/handlebars-resource
                                                                    :subject "{{computed.subject}}"
                                                                    :path    "metabase/email/alert.hbs"})
                                                    :created_at   :%now
                                                    :updated_at   :%now})]
    (run! #(pulse->notification! % alert-template-id)
          (t2/reducible-query {:select [:*]
                               :from   [:pulse]
                               :where  [:in :alert_condition ["rows" "goal"]]}))))

(comment
  (t2/delete! :model/Notification)
  (migrate-alerts!)
  (t2/hydrate (t2/select :model/Notification) :subscriptions
              [:handlers :recipients :template]))
