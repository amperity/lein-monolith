(ns lein-monolith.task.each-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [lein-monolith.config :as config]
    [lein-monolith.task.each :as each]
    [lein-monolith.test-utils :refer [read-example-project]]))


(defn- test-path
  "Returns the path where the test file should exist for the given target path."
  [subproject target]
  (if (= :target-path target)
    (str (:root subproject) "/target/test.txt")
    (str target "/test.txt")))


(deftest clean-subprojects
  (testing "Verify that the clean targets for each subproject are cleaned up by `lein monolith each clean`."
    (let [monolith (read-example-project)
          subprojects (config/read-subprojects! monolith)]
      (doseq [[_subproject-name subproject] subprojects
              target (:clean-targets subproject)]
        (let [path (test-path subproject target)]
          (is (str/starts-with? path (:root subproject))
              "The test file path should be created within the subproject directory.")
          (io/make-parents path)
          (spit path "test")
          (is (.exists (io/file path)) "The test file should have been created.")))
      (each/run-tasks monolith {} ["clean"]) ; lein monolith each clean
      (doseq [[_subproject-name subproject] subprojects
              target (:clean-targets subproject)]
        (let [path (test-path subproject target)
              test-file (io/file path)
              parent-dir (io/file (.getParent test-file))]
          (is (not (.exists test-file)) "The test file should not exist after a lein clean")
          (is (not (.exists parent-dir)) "The target directory should not exist after a lein clean"))))))
