(ns lein-monolith.task.each-test
  (:require
    [cemerick.pomegranate]
    [clojure.data]
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


(deftest thread-safe-require-resolve-semantics
  (let [tsrr #'each/thread-safe-require-resolve]
    (testing "missing namespace resolves to nil"
      (is (nil? (tsrr 'no.such.namespace/some-fn))))
    (testing "loadable namespace is required and resolved"
      (is (= #'clojure.data/diff (tsrr 'clojure.data/diff))))
    (testing "in-memory namespace falls through to resolve"
      (create-ns 'each-test.mem-only)
      (intern 'each-test.mem-only 'answer 42)
      (is (= 42 @(tsrr 'each-test.mem-only/answer))))
    (testing "unqualified symbol resolves in the current namespace"
      (is (= #'clojure.core/map (tsrr 'map))))
    (testing "load errors propagate instead of resolving to nil"
      (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                             (str "tsrr-test-" (System/nanoTime)))]
        (io/make-parents (io/file tmp-dir "broken_load_for_test.clj"))
        (spit (io/file tmp-dir "broken_load_for_test.clj")
              "(ns broken-load-for-test)\n(defn oops [] (undefined-var))\n")
        (cemerick.pomegranate/add-classpath tmp-dir)
        (is (thrown? Exception (tsrr 'broken-load-for-test/oops)))))))
