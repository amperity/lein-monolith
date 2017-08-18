(defproject example/all "MONOLITH"
  :description "Overarching example project."

  :plugins
  [[lein-monolith "1.0.2-SNAPSHOT"]
   [lein-cprint "1.2.0"]]

  :dependencies
  [[org.clojure/clojure "1.8.0"]]

  :test-selectors
  {:unit (complement :integration)
   :integration :integration}

  :monolith
  {:inherit
   [:test-selectors
    :env]

   :inherit-leaky
   [:repositories
    :managed-dependencies]

   :project-selectors
   {:deployable :deployable
    :unstable #(= (first (:version %)) \0)}

   :project-dirs
   ["apps/app-a"
    "libs/*"
    "not-found"]}

  :profiles {:ci {:monolith {:print-color false}}}

  :env
  {:foo "bar"})
