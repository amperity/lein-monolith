(ns lein-monolith.plugin-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.plugin :as sut]))


(deftest inherited-profile-test
  (testing "providing literal inherited values"
    (let [monolith {:root "/project/root"
                    :test-paths ["/absolute/path"]
                    :monolith {:inherit [{:test-paths ["relative/path"]}
                                         :test-paths]}}
          profile (sut/inherited-profile monolith :inherit true)]
      (is (= {:test-paths ["relative/path" "/absolute/path"]}
             profile)))))
