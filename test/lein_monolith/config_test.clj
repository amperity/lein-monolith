(ns lein-monolith.config-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [lein-monolith.config :as config]
    [lein-monolith.test-utils :refer [read-example-project]]))


(deftest read-subprojects
  (testing "subproject clean targets use absolute paths"
    (let [monolith (read-example-project)
          subprojects (config/read-subprojects! monolith)
          test-project (get subprojects 'lein-monolith.example/lib-b)
          clean-targets (:clean-targets test-project)]
      (is (map? test-project)
          "lib-b subproject was loaded")
      (is (= {:protect false} (meta clean-targets))
          "metadata is preserved")
      (is (= (set clean-targets) (set (filter #(.isAbsolute (io/file %)) clean-targets)))
          "All clean target paths are absolute"))))
