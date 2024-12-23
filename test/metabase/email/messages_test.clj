(ns metabase.email.messages-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.email :as email]
   [metabase.email-test :as et]
   [metabase.email.messages :as messages]
   [metabase.test :as mt]
   [metabase.test.util :as tu]
   [metabase.util.cron :as u.cron]
   [metabase.util.retry :as retry]
   [metabase.util.retry-test :as rt])
  (:import
   (io.github.resilience4j.retry Retry)))

(set! *warn-on-reflection* true)

(deftest password-reset-email
  (testing "password reset email can be sent successfully"
    (et/with-fake-inbox
      (messages/send-password-reset-email! "test@test.com" nil "http://localhost/some/url" true)
      (is (= [{:from    "notifications@metabase.com",
               :to      ["test@test.com"],
               :subject "[Metabase] Password Reset Request",
               :body    [{:type "text/html; charset=utf-8"}]}]
             (-> (@et/inbox "test@test.com")
                 (update-in [0 :body 0] dissoc :content))))))
  ;; Email contents contain randomized elements, so we only check for the inclusion of a single word to verify
  ;; that the contents changed in the tests below.
  (testing "password reset email tells user if they should log in with Google Sign-In"
    (et/with-fake-inbox
      (messages/send-password-reset-email! "test@test.com" "google" "http://localhost/some/url" true)
      (is (-> (@et/inbox "test@test.com")
              (get-in [0 :body 0 :content])
              (str/includes? "Google")))))
  (testing "password reset email tells user if they should log in with (non-Google) SSO"
    (et/with-fake-inbox
      (messages/send-password-reset-email! "test@test.com" "SAML" nil true)
      (is (-> (@et/inbox "test@test.com")
              (get-in [0 :body 0 :content])
              (str/includes? "SSO")))))
  (testing "password reset email tells user if their account is inactive"
    (et/with-fake-inbox
      (messages/send-password-reset-email! "test@test.com" nil "http://localhost/some/url" false)
      (is (-> (@et/inbox "test@test.com")
              (get-in [0 :body 0 :content])
              (str/includes? "deactivated"))))))

(deftest alert-schedule-text-test
  (let [schedule-text (fn [cron-string]
                        (-> cron-string
                            u.cron/schedule-map->cron-string
                            ((fn [cron-string] {:type :notification-subscription/cron
                                                :cron_schedule cron-string}))
                            (@#'messages/notification-card-schedule-text)))]
    (testing "Alert schedules can be described as English strings, with the timezone included"
      (tu/with-temporary-setting-values [report-timezone "America/Pacific"]
        (is (= "Run hourly"
               (schedule-text {:schedule_type "hourly"})))
        (is (= "Run daily at 12 AM America/Pacific"
               (schedule-text {:schedule_type "daily"
                               :schedule_hour 0})))
        (is (= "Run daily at 5 AM America/Pacific"
               (schedule-text {:schedule_type "daily"
                               :schedule_hour 5})))
        (is (= "Run daily at 6 PM America/Pacific"
               (schedule-text {:schedule_type "daily"
                               :schedule_hour 18})))
        (is (= "Run weekly on Monday at 8 AM America/Pacific"
               (schedule-text {:schedule_type "weekly"
                               :schedule_day  "mon"
                               :schedule_hour 8})))))
    (testing "If report-timezone is not set, falls back to UTC"
      (tu/with-temporary-setting-values [report-timezone nil]
        (is (= "Run daily at 12 AM UTC"
               (schedule-text {:schedule_type "daily"
                               :schedule_hour 0})))))))

#_(deftest render-pulse-email-test
    (testing "Email with few rows and columns can be rendered when tracing (#21166)"
      (mt/with-log-level [metabase.email :trace]
        (let [part {:card   {:id   1
                             :name "card-name"
                             :visualization_settings
                             {:table.column_formatting []}}
                    :result {:data {:cols [{:name "x"} {:name "y"}]
                                    :rows [[0 0]
                                           [1 1]]}}
                    :type :card}
              emails (messages/render-pulse-email "America/Pacific" {} {} [part] nil)]
          (is (vector? emails))
          (is (map? (first emails)))))))

(defn- get-positive-retry-metrics [^Retry retry]
  (let [metrics (bean (.getMetrics retry))]
    (into {}
          (map (fn [field]
                 (let [n (metrics field)]
                   (when (pos? n)
                     [field n]))))
          [:numberOfFailedCallsWithRetryAttempt
           :numberOfFailedCallsWithoutRetryAttempt
           :numberOfSuccessfulCallsWithRetryAttempt
           :numberOfSuccessfulCallsWithoutRetryAttempt])))

(def test-email {:subject      "Test email subject"
                 :recipients   ["test@test.com"]
                 :message-type :html
                 :message      "test mmail body"})

(deftest send-email-retrying-test
  (testing "send email succeeds w/o retry"
    (let [test-retry (retry/random-exponential-backoff-retry "test-retry" (#'retry/retry-configuration))]
      (with-redefs [email/send-email! mt/fake-inbox-email-fn
                    retry/decorate    (rt/test-retry-decorate-fn test-retry)]
        (mt/with-temporary-setting-values [email-smtp-host "fake_smtp_host"
                                           email-smtp-port 587]
          (mt/reset-inbox!)
          (email/send-email-retrying! test-email)
          (is (= {:numberOfSuccessfulCallsWithoutRetryAttempt 1}
                 (get-positive-retry-metrics test-retry)))
          (is (= 1 (count @mt/inbox)))))))
  (testing "send email fails b/c retry limit"
    (let [retry-config (assoc (#'retry/retry-configuration)
                              :max-attempts 1
                              :initial-interval-millis 1)
          test-retry   (retry/random-exponential-backoff-retry "test-retry" retry-config)]
      (with-redefs [email/send-email! (tu/works-after 1 mt/fake-inbox-email-fn)
                    retry/decorate    (rt/test-retry-decorate-fn test-retry)]
        (mt/with-temporary-setting-values [email-smtp-host "fake_smtp_host"
                                           email-smtp-port 587]
          (mt/reset-inbox!)
          (try (#'email/send-email-retrying! test-email)
               (catch Exception _))
          (is (= {:numberOfFailedCallsWithRetryAttempt 1}
                 (get-positive-retry-metrics test-retry)))
          (is (= 0 (count @mt/inbox)))))))
  (testing "send email succeeds w/ retry"
    (let [retry-config (assoc (#'retry/retry-configuration)
                              :max-attempts 2
                              :initial-interval-millis 1)
          test-retry   (retry/random-exponential-backoff-retry "test-retry" retry-config)]
      (with-redefs [email/send-email! (tu/works-after 1 mt/fake-inbox-email-fn)
                    retry/decorate    (rt/test-retry-decorate-fn test-retry)]
        (mt/with-temporary-setting-values [email-smtp-host "fake_smtp_host"
                                           email-smtp-port 587]
          (mt/reset-inbox!)
          (#'email/send-email-retrying! test-email)
          (is (= {:numberOfSuccessfulCallsWithRetryAttempt 1}
                 (get-positive-retry-metrics test-retry)))
          (is (= 1 (count @mt/inbox))))))))
