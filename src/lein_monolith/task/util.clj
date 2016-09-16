(ns lein-monolith.task.util
  "Utility functions for task code."
  (:require
    [lein-monolith.config :as config]))


(defn parse-kw-args
  "Given a sequence of string arguments, parse out expected keywords. Returns
  a vector with a map of keywords to values (or `true` for flags) followed by
  a sequence the remaining unparsed arguments."
  [expected args]
  (loop [opts {}
         args args]
    (let [kw (and (first args)
                  (.startsWith ^String (first args) ":")
                  (keyword (subs (first args) 1)))
          arg-count (get expected kw)]
      (cond
        ; Not an expected kw arg
        (nil? arg-count)
          [opts args]

        ; Flag keyword
        (zero? arg-count)
          (recur (assoc opts kw true) (rest args))

        ; Multi-arg keyword
        :else
          (recur
            (update opts kw (fnil conj []) (vec (take arg-count (rest args))))
            (drop (inc arg-count) args))))))


(defn human-duration
  "Renders a duration in milliseconds in hour:minute:second.ms format."
  [duration]
  (if duration
    (let [hours (int (/ duration 1000.0 60 60))
          minutes (- (int (/ duration 1000.0 60))
                     (* hours 60))
          seconds (- (int (/ duration 1000.0))
                     (* minutes 60)
                     (* hours 60 60))
          milliseconds (int (rem duration 1000))]
      (if (pos? hours)
        (format "%d:%02d:%02d.%03d" hours minutes seconds milliseconds)
        (format "%d:%02d.%03d" minutes seconds milliseconds)))
    "--:--"))


(defn load-monolith!
  "Helper function to make a common pattern more succinct."
  [project]
  (let [monolith (config/find-monolith! project)
        subprojects (config/read-subprojects! monolith)]
    [monolith subprojects]))
