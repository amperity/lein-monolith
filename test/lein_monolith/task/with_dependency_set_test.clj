(ns lein-monolith.task.with-dependency-set-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.task.with-dependency-set :as wds]
    [lein-monolith.test-utils :refer [use-example-project]]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


(use-example-project)


(deftest setup-test
  (with-redefs [lein/resolve-and-apply (fn [project & _] project)]
    (testing "Root Project"
      (let [project (project/read "example/project.clj")
            deps [['amperity/greenlight "0.7.1"]
                  ['org.clojure/spec.alpha "0.3.218"]]
            actual (wds/run-task project :set-a nil)]
        (is (= deps (:managed-dependencies actual)))
        (is (= deps
               (get-in actual [:profiles :monolith/dependency-override :managed-dependencies])))))
    (testing "Subproject"
      (let [project (project/read "example/apps/app-a/project.clj")
            replaced-deps [['amperity/greenlight "0.7.0"]
                           ['org.clojure/spec.alpha "0.2.194"]]
            actual (wds/run-task project :set-outdated nil)]
        (is (= replaced-deps (:managed-dependencies actual)))
        (is (= replaced-deps
               (get-in actual [:profiles :monolith/dependency-override :managed-dependencies])))
        (is (= :set-outdated (:monolith/dependency-set actual)))))))
