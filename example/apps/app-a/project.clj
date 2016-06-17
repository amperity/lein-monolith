(defproject example/app-a "0.1.0-SNAPSHOT"
  :description "Example project with internal and external dependencies."

  :dependencies
  [[commons-io "2.5"]
   [example/lib-a "1.0.0"]
   [example/lib-b "0.2.0"]
   [org.clojure/clojure "1.8.0"]])
