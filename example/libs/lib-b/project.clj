(defproject example/lib-b "0.2.0"
  :description "Example lib depending on lib-a."
  :monolith/inherit [:aliases]

  :dependencies
  [[example/lib-a "1.0.0"]
   [org.clojure/clojure "1.8.0"]])
