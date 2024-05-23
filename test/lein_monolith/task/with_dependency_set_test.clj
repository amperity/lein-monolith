(ns lein-monolith.task.with-dependency-set-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.task.with-dependency-set :as wds]
    [lein-monolith.test-utils :refer [use-example-project]]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


(use-example-project)


(defn- remove-unstable
  [project]
  (assoc-in project [:monolith :project-selectors :unstable] "non-deterministic"))


(defn- read-project
  [path]
  (-> (project/read-raw path)
      (plugin/add-middleware)
      (project/init-project)))


(deftest setup-test
  (with-redefs [lein/resolve-and-apply (fn [project & _] project)]
    (let [project (remove-unstable (read-project "example/project.clj"))]
      (testing "Root Project"
        (is (= (assoc project
                      :managed-dependencies
                      '([amperity/greenlight "0.7.1"]
                        [org.clojure/spec.alpha "0.3.218"]))
               (remove-unstable (wds/run-task project :set-a nil)))
            "For root project, without :only, should have managed dependencies from :set-a")))
    (let [project (read-project "example/apps/app-a/project.clj")]
      (testing "Subproject"
        (let [expected (-> project
                           (assoc :monolith/dependency-set :set-outdated
                                  :managed-dependencies '([amperity/greenlight "0.7.0"]
                                                          [org.clojure/spec.alpha "0.2.194"]
                                                          [amperity/greenlight "0.6.0"]
                                                          [com.amperity/vault-clj "2.1.583"]))
                           (assoc-in [:profiles :monolith/dependency-set :managed-dependencies]
                                     '[[amperity/greenlight "0.7.0"]
                                       [org.clojure/spec.alpha "0.2.194"]]))]
          (is (= expected
                 (wds/run-task project :set-outdated nil))
              "For subproject, should have managed dependencies from :set-outdated"))))))
