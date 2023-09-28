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
        profiles (into {} (plugin/build-inherited-profiles monolith subproject))]
    (is (= #{:monolith/inherited
             :monolith/inherited-raw
             :monolith/leaky
             :monolith/leaky-raw}
           (set (keys profiles))))
    (is (= {:test-paths ["test/unit" "test/integration"]}
           (:monolith/inherited-raw profiles)))
    (is (= {:repositories
            [["central"
              {:url "https://repo1.maven.org/maven2/"
               :snapshots false}]
             ["clojars" {:url "https://repo.clojars.org/"}]]
            :managed-dependencies [['amperity/greenlight "0.6.0"] ['com.amperity/vault-clj "2.1.583"]]}
           (:monolith/leaky profiles)))
    (is (= {:compile-path "%s/compiled"}
           (:monolith/leaky-raw profiles)))))


(deftest build-dependency-set-profile-test
  (let [monolith (config/find-monolith! (read-example-project))
        subproject (project/read "example/apps/app-a/project.clj")
        profile (into {} (plugin/build-dependency-profiles monolith subproject))]
    (is (= :set-a (:monolith/dependency-set subproject)))
    (is (= [['amperity/greenlight "0.7.1"] ['org.clojure/spec.alpha "0.3.218"]]
           (get-in monolith [:monolith :dependency-sets :set-a])))
    (is (= [['amperity/greenlight "0.7.1"] ['org.clojure/spec.alpha "0.3.218"]]
           (get-in profile [:monolith/dependency-set :managed-dependencies])))))


(deftest managed-dependencies-order
  (let [monolith (config/find-monolith! (read-example-project))
        subproject (-> (project/read "example/apps/app-a/project.clj")
                       (plugin/middleware monolith))]
    (is (= '([amperity/greenlight "0.7.1"] ; This version of greenlight should come first so it'll take precedence over any other versions
             [org.clojure/spec.alpha "0.3.218"]
             [amperity/greenlight "0.6.0"]
             [com.amperity/vault-clj "2.1.583"])
           (:managed-dependencies subproject)))))
