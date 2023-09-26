(ns lein-monolith.plugin-test
  (:require
    [clojure.test :refer [deftest is]]
    [lein-monolith.config :as config]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.test-utils :refer [use-example-project read-example-project]]
    [leiningen.core.project :as project]))


(use-example-project)


(deftest build-inherited-profiles-test
  (let [monolith (config/find-monolith! (read-example-project))
        subproject (project/read "example/apps/app-a/project.clj")
        profiles (plugin/build-profiles monolith subproject)]
    (is (= #{:monolith/inherited
             :monolith/inherited-raw
             :monolith/leaky
             :monolith/leaky-raw
             :monolith/dependency-set}
           (set (keys profiles))))
    (is (= {:test-paths ["test/unit" "test/integration"]}
           (:monolith/inherited-raw profiles)))
    (is (= {:repositories
            [["central"
              {:url "https://repo1.maven.org/maven2/"
               :snapshots false}]
             ["clojars" {:url "https://repo.clojars.org/"}]]
            :managed-dependencies [['amperity/greenlight "0.6.0"]]}
           (:monolith/leaky profiles)))
    (is (= {:compile-path "%s/compiled"}
           (:monolith/leaky-raw profiles)))
    (is (= {:managed-dependencies [['amperity/greenlight "0.7.1"]]}
           (:monolith/dependency-set profiles)))))
