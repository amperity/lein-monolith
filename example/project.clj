(defproject lein-monolith.example/all "MONOLITH"
  :description "Overarching example project."

  :aliases
  {"version+" ["version"]
   "version++" ["version+"]}

  :plugins
  [[lein-monolith "1.9.0"]
   [lein-pprint "1.2.0"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]]

  :managed-dependencies
  [[amperity/greenlight "0.6.0"]
   [com.amperity/vault-clj "2.1.583"]]

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
    :test-selectors
    :env]

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
   ["apps/*"
    "libs/**"
    "not-found"]

   :dependency-sets
   {:set-a
    [[amperity/greenlight "0.7.1"]
     [org.clojure/spec.alpha "0.3.218"]]}}

  :env
  {:foo "bar"})
