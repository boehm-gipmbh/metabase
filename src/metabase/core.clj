(ns metabase.core
  (:require
   [clojure.string :as str]
   [clojure.tools.trace :as trace]
   [environ.core :as env]
   [java-time.api :as t]
   [metabase.analytics.prometheus :as prometheus]
   [metabase.config :as config]
   [metabase.core.config-from-file :as config-from-file]
   [metabase.core.init]
   [metabase.core.initialization-status :as init-status]
   [metabase.db :as mdb]
   [metabase.driver.h2]
   [metabase.driver.mysql]
   [metabase.driver.postgres]
   [metabase.embed.settings :as embed.settings]
   [metabase.events :as events]
   [metabase.logger :as logger]
   [metabase.models.cloud-migration :as cloud-migration]
   [metabase.models.database :as database]
   [metabase.models.setting :as settings]
   [metabase.notification.core :as notification]
   [metabase.plugins :as plugins]
   [metabase.plugins.classloader :as classloader]
   [metabase.premium-features.core :as premium-features :refer [defenterprise]]
   [metabase.public-settings :as public-settings]
   [metabase.sample-data :as sample-data]
   [metabase.server.core :as server]
   [metabase.setup :as setup]
   [metabase.task :as task]
   [metabase.troubleshooting :as troubleshooting]
   [metabase.util :as u]
   [metabase.util.log :as log])
  (:import
   (java.lang.management ManagementFactory)))

(set! *warn-on-reflection* true)

(comment
  metabase.core.init/keep-me
  ;; Load up the drivers shipped as part of the main codebase, so they will show up in the list of available DB types
  metabase.driver.h2/keep-me
  metabase.driver.mysql/keep-me
  metabase.driver.postgres/keep-me
  ;; Make sure the custom Metabase logger code gets loaded up so we use our custom logger for performance reasons.
  logger/keep-me)

;; don't i18n this, it's legalese
(log/info
 (format "\nMetabase %s" config/mb-version-string)
 (format "\n\nCopyright © %d Metabase, Inc." (.getYear (java.time.LocalDate/now)))
 (str "\n\n"
      (if config/ee-available?
        (str "Metabase Enterprise Edition extensions are PRESENT."
             "\n\n"
             "Usage of Metabase Enterprise Edition features are subject to the Metabase Commercial License."
             "See https://www.metabase.com/license/commercial/ for details.")
        "Metabase Enterprise Edition extensions are NOT PRESENT.")))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn- print-setup-url
  "Print the setup url during instance initialization."
  []
  (let [hostname  (or (config/config-str :mb-jetty-host) "localhost")
        port      (config/config-int :mb-jetty-port)
        site-url  (or (public-settings/site-url)
                      (str "http://"
                           hostname
                           (when-not (= 80 port) (str ":" port))))
        setup-url (str site-url "/setup/")]
    (log/info (u/format-color 'green
                              (str "Please use the following URL to setup your Metabase installation:"
                                   "\n\n"
                                   setup-url
                                   "\n\n")))))

(defn- create-setup-token-and-log-setup-url!
  "Create and set a new setup token and log it."
  []
  (setup/create-token!)   ; we need this here to create the initial token
  (print-setup-url))

(defn- destroy!
  "General application shutdown function which should be called once at application shutdown."
  []
  (log/info "Metabase Shutting Down ...")
  (task/stop-scheduler!)
  (server/stop-web-server!)
  (prometheus/shutdown!)
  ;; This timeout was chosen based on a 30s default termination grace period in Kubernetes.
  (let [timeout-seconds 20]
    (mdb/release-migration-locks! timeout-seconds))
  (log/info "Metabase Shutdown COMPLETE"))

(defenterprise ensure-audit-db-installed!
  "OSS implementation of `audit-db/ensure-db-installed!`, which is an enterprise feature, so does nothing in the OSS
  version."
  metabase-enterprise.audit-app.audit [] ::noop)

(defn- init!*
  "General application initialization function which should be run once at application startup."
  []
  (log/infof "Starting Metabase version %s ..." config/mb-version-string)
  (log/infof "System info:\n %s" (u/pprint-to-str (troubleshooting/system-info)))
  (init-status/set-progress! 0.1)
  ;; First of all, lets register a shutdown hook that will tidy things up for us on app exit
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable destroy!))
  (init-status/set-progress! 0.2)
  ;; load any plugins as needed
  (plugins/load-plugins!)
  (init-status/set-progress! 0.3)
  (settings/validate-settings-formatting!)
  ;; startup database.  validates connection & runs any necessary migrations
  (log/info "Setting up and migrating Metabase DB. Please sit tight, this may take a minute...")
  ;; Cal 2024-04-03:
  ;; we have to skip creating sample content if we're running tests, because it causes some tests to timeout
  ;; and the test suite can take 2x longer. this is really unfortunate because it could lead to some false
  ;; negatives, but for now there's not much we can do
  (mdb/setup-db! :create-sample-content? (not config/is-test?))

  ;; Disable read-only mode if its on during startup.
  ;; This can happen if a cloud migration process dies during h2 dump.
  (when (cloud-migration/read-only-mode)
    (cloud-migration/read-only-mode! false))

  (init-status/set-progress! 0.4)
  ;; Set up Prometheus
  (log/info "Setting up prometheus metrics")
  (prometheus/setup!)
  (init-status/set-progress! 0.5)

  (premium-features/airgap-check-user-count)
  (init-status/set-progress! 0.55)
  ;; run a very quick check to see if we are doing a first time installation
  ;; the test we are using is if there is at least 1 User in the database
  (let [new-install? (not (setup/has-user-setup))]
    ;; initialize Metabase from an `config.yml` file if present (Enterprise Edition™ only)
    (config-from-file/init-from-file-if-code-available!)
    (init-status/set-progress! 0.6)
    (when new-install?
      (log/info "Looks like this is a new installation ... preparing setup wizard")
      ;; create setup token
      (create-setup-token-and-log-setup-url!)
      ;; publish install event
      (events/publish-event! :event/install {}))
    (init-status/set-progress! 0.7)
    ;; deal with our sample database as needed
    (when (config/load-sample-content?)
      (if new-install?
        ;; add the sample database DB for fresh installs
        (sample-data/extract-and-sync-sample-database!)
        ;; otherwise update if appropriate
        (sample-data/update-sample-database-if-needed!)))
    (init-status/set-progress! 0.8))

  (ensure-audit-db-installed!)
  (notification/seed-notification!)
  (init-status/set-progress! 0.9)

  (embed.settings/check-and-sync-settings-on-startup! env/env)
  (init-status/set-progress! 0.95)

  (settings/migrate-encrypted-settings!)
   ;; start scheduler at end of init!
  (task/start-scheduler!)
   ;; In case we could not do this earlier (e.g. for DBs added via config file), because the scheduler was not up yet:
  (database/check-and-schedule-tasks!)
  (init-status/set-complete!)
  (let [start-time (.getStartTime (ManagementFactory/getRuntimeMXBean))
        duration   (- (System/currentTimeMillis) start-time)]
    (log/infof "Metabase Initialization COMPLETE in %s" (u/format-milliseconds duration))))

(defn init!
  "General application initialization function which should be run once at application startup. Calls `[[init!*]] and
  records the duration of startup."
  []
  (let [start-time (t/zoned-date-time)]
    (init!*)
    (public-settings/startup-time-millis!
     (.toMillis (t/duration start-time (t/zoned-date-time))))))

;;; -------------------------------------------------- Normal Start --------------------------------------------------

(defn- start-normally []
  (log/info "Starting Metabase in STANDALONE mode")
  (try
    ;; launch embedded webserver async
    (server/start-web-server! (server/handler))
    ;; run our initialization process
    (init!)
    ;; Ok, now block forever while Jetty does its thing
    (when (config/config-bool :mb-jetty-join)
      (.join (server/instance)))
    (catch Throwable e
      (log/error e "Metabase Initialization FAILED")
      (System/exit 1))))

(defn- run-cmd [cmd init-fn args]
  (classloader/require 'metabase.cmd)
  ((resolve 'metabase.cmd/run-cmd) cmd init-fn args))

;;; -------------------------------------------------- Tracing -------------------------------------------------------

(defn- maybe-enable-tracing
  []
  (let [mb-trace-str (config/config-str :mb-ns-trace)]
    (when (not-empty mb-trace-str)
      (log/warn "WARNING: You have enabled namespace tracing, which could log sensitive information like db passwords.")
      (doseq [namespace (map symbol (str/split mb-trace-str #",\s*"))]
        (try (require namespace)
             (catch Throwable _
               (throw (ex-info "A namespace you specified with MB_NS_TRACE could not be required" {:namespace namespace}))))
        (trace/trace-ns namespace)))))

;;; ------------------------------------------------ App Entry Point -------------------------------------------------

(defn entrypoint
  "Launch Metabase in standalone mode. (Main application entrypoint is [[metabase.bootstrap/-main]].)"
  [& [cmd & args]]
  (maybe-enable-tracing)
  (if cmd
    ;; run a command like `java --add-opens java.base/java.nio=ALL-UNNAMED -jar metabase.jar migrate release-locks` or
    ;; `clojure -M:run migrate release-locks`
    (run-cmd cmd init! args)
    ;; with no command line args just start Metabase normally
    (start-normally)))
