(ns lein-monolith.plugin-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.plugin :as sut]))


(deftest inherited-profile-test
  (testing "relativizing matching absolute paths"
    (let [monolith {:root "/project/root"
                    :test-paths ["/project/root/relative/path" "/absolute/path"]
                    :target-path "/project/root/relative/path"
                    :compile-path "/absolute/path"
                    :pedantic? :abort
                    :monolith {:inherit [:test-paths
                                         :target-path
                                         :compile-path]}}
          profile (sut/inherited-profile monolith :inherit true)]
      (is (= {:test-paths ["relative/path" "/absolute/path"]
              :target-path "relative/path"
              :compile-path "/absolute/path"}
             profile)))))
