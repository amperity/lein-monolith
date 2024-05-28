(ns lein-monolith.task.with-dependency-set-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.task.with-dependency-set :as wds]
    [lein-monolith.test-utils :refer [use-example-project]]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [leiningen.monolith :as monolith]))


(use-example-project)


(deftest run-task-test
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
        (is (= :set-outdated (:monolith/dependency-set actual)))))
    (testing "Unknown dependency set"
      (let [project (project/read "example/project.clj")]
        (is (thrown? Exception (wds/run-task project :unknown nil)))))))


(deftest monolith-task-test
  (testing "Parent subtask throws"
    (let [project (project/read "example/project.clj")]
      (is (thrown? Exception (monolith/monolith project
                                                "with-dependency-set"
                                                [":foo" "monolith" "each" "clean"]))))))
