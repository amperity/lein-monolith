(ns lein-monolith.task.with-dependency-set-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.task.with-dependency-set :as wds]
    [lein-monolith.test-utils :refer [use-example-project read-example-project]]
    [leiningen.core.project :as project]))


(use-example-project)


(defn- remove-unstable
  [project]
  (assoc-in project [:monolith :project-selectors :unstable] "non-deterministic"))


(deftest setup-test
  (let [project (remove-unstable (read-example-project))]
    (testing "Root Project"
      (testing "without :only"
        (is (= (assoc project
                      :managed-dependencies
                      '([amperity/greenlight "0.7.1"]
                        [org.clojure/spec.alpha "0.3.218"]))
               (remove-unstable (#'wds/setup project {} :set-a)))
            "For root project, without :only, should have managed dependencies from :set-a"))
      (testing "with :only"
        (is (= (assoc project :managed-dependencies
                      '([amperity/greenlight "0.7.1"]
                        [org.clojure/spec.alpha "0.3.218"])
                      :dependencies '())
               (remove-unstable (#'wds/setup project {:only true} :set-a)))
            "For root project, with :only, should have no dependencies"))))
  (let [project (project/read "example/apps/app-a/project.clj")]
    (testing "Subproject"
      (testing "without :only"
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
                 (#'wds/setup project {} :set-outdated))
              "For subproject, should have managed dependencies from :set-outdated")))
      (testing "with :only"
        (let [expected (-> project
                           (assoc :monolith/dependency-set :set-outdated
                                  :managed-dependencies '([amperity/greenlight "0.7.0"]
                                                          [org.clojure/spec.alpha "0.2.194"]
                                                          [amperity/greenlight "0.6.0"]
                                                          [com.amperity/vault-clj "2.1.583"])
                                  :dependencies '())
                           (assoc-in [:profiles :monolith/dependency-set :managed-dependencies]
                                     '[[amperity/greenlight "0.7.0"]
                                       [org.clojure/spec.alpha "0.2.194"]]))]
          (is (= expected
                 (#'wds/setup project {:only true} :set-outdated))
              "For subproject, with :only, should have no dependencies"))))))
