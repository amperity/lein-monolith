(ns lein-monolith.ansi
  (:require
    [puget.color.ansi :as ansi]
    [puget.printer :as puget]))


(defn maybe-sgr
  "If colour printing hasn't been disabled, wraps the given string
  with SGR escapes to apply the given codes, then reset the graphics."
  [string & codes]
  (if (:print-color puget.printer/*options*)
    (apply ansi/sgr string codes)
    string))
