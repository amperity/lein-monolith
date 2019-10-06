(ns lein-monolith.color
  "Coloring functions which apply ANSI color codes to color terminal output."
  (:require
    [clojure.string :as str]))


(def ^:private sgr-code
  "Map of symbols to numeric SGR (select graphic rendition) codes."
  {:none        0
   :bold        1
   :underline   3
   :blink       5
   :reverse     7
   :hidden      8
   :strike      9
   :black      30
   :red        31
   :green      32
   :yellow     33
   :blue       34
   :magenta    35
   :cyan       36
   :white      37
   :fg-256     38
   :fg-reset   39
   :bg-black   40
   :bg-red     41
   :bg-green   42
   :bg-yellow  43
   :bg-blue    44
   :bg-magenta 45
   :bg-cyan    46
   :bg-white   47
   :bg-256     48
   :bg-reset   49})


(def ^:dynamic *enabled*
  "Whether to render text with color."
  true)


(defn- sgr
  "Returns an ANSI escope string which will apply the given collection of SGR
  codes."
  [codes]
  (let [codes (map sgr-code codes codes)
        codes (str/join \; codes)]
    (str \u001b \[ codes \m)))


(defn colorize
  "Wraps the given string with SGR escapes to apply the given codes, then reset
  the graphics. If `*enabled*` is not truthy, returns the string unaltered."
  [codes string]
  (if *enabled*
    (let [codes (if (keyword? codes)
                  [codes]
                  (vec codes))]
      (str (sgr codes) string (sgr [:none])))
    string))
