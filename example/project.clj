(defproject lein-monolith.example/all "MONOLITH"
  :description "Overarching example project."

  :aliases
  {"version+" ["version"]
   "version++" ["version+"]}

  :plugins
  [[lein-monolith "1.5.1-SNAPSHOT"]
   [lein-pprint "1.2.0"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]]

  :managed-dependencies
  [[amperity/greenlight "0.6.0"]]

  :test-selectors
  {:unit (complement :integration)
   :integration :integration}

  :test-paths ^:replace
  ["test/unit"
   "test/integration"]

  :compile-path
  "%s/compiled"

  :monolith
  {:inherit
   [:aliases
    :env
    :test-selectors]

   :inherit-raw
   [:test-paths]

   :inherit-leaky
   [:repositories
    :managed-dependencies]

   :inherit-leaky-raw
   [:compile-path]

   :project-selectors
   {:deployable :deployable
    :unstable #(= (first (:version %)) \0)}

   :project-dirs
   ["apps/app-a"
    "libs/*"
    "not-found"]}

  :env
  {:foo "bar"})
