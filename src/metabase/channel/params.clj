(ns metabase.channel.params
  (:require
   [clojure.string :as str]
   [metabase.driver.common.parameters :as params]
   [metabase.driver.common.parameters.parse :as params.parse]))

(defn param-name->path
  [param-name]
  (->> (str/split param-name #"\.")
       (mapv keyword)))

(defn substitute-params
  [text context]
  (let [components (params.parse/parse text)]
    (str/join ""
              (for [c components]
                (if (params/Param? c)
                  (or (get-in context (param-name->path (:k c)))
                      (throw (ex-info (str "Missing parameter: " (:k c)) {:param (:k c)})))
                  c)))))

(comment
  (substitute-params "Hello {{user.email}}!" {:user {:email "ngoc@metabase.com"}}))
