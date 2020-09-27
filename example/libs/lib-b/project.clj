(defproject lein-monolith/example.lib-b "0.2.0"
  :description "Example lib depending on lib-a."

  :dependencies
  [[lein-monolith/example.lib-a "1.0.0"]
   [org.clojure/clojure "1.8.0"]])
