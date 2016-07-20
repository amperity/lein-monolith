(ns lein-monolith.dependency-test
  (:require
    [clojure.test :refer :all]
    [lein-monolith.dependency :as dep]))


(deftest coordinate-utilities
  (testing "condense-name"
    (is (nil? (dep/condense-name nil)))
    (is (= 'lein-monolith (dep/condense-name 'lein-monolith/lein-monolith)))
    (is (= 'example/foo (dep/condense-name 'example/foo))))
  (testing "project-name"
    (is (nil? (dep/project-name nil)))
    (is (= 'foo (dep/project-name {:group "foo", :name "foo"})))
    (is (= 'example/bar (dep/project-name {:group "example", :name "bar"}))))
  (testing "unscope-coord"
    (is (= '[example/foo "1.0"] (dep/unscope-coord '[example/foo "1.0"])))
    (is (= '[example/bar "0.5.0" :exclusions [foo]]
           (dep/unscope-coord '[example/bar "0.5.0" :exclusions [foo]])))
    (is (= '[example/bar "0.5.0" :exclusions [foo]]
           (dep/unscope-coord '[example/bar "0.5.0" :exclusions [foo] :scope :test])))
    (is (= '[example/baz "0.1.0-SNAPSHOT"]
           (dep/unscope-coord '[example/baz "0.1.0-SNAPSHOT" :scope :test]))))
  (testing "source-metadata"
    (is (nil? (dep/dep-source [:foo "123"])))
    (is (= [:foo "123"] (dep/with-source [:foo "123"] 'example/bar)))
    (is (= 'example/bar (dep/dep-source (dep/with-source [:foo "123"] 'example/bar))))))


(deftest subtree-resolution
  (is (= {}
         (dep/subtree-from {} nil)))
  (is (= {}
         (dep/subtree-from {:a [], :b [:a]} nil)))
  (is (= {:a #{}}
         (dep/subtree-from {:a [], :b [:a]} :a)))
  (is (= {:a #{}, :b #{:a}}
         (dep/subtree-from {:a [], :b [:a], :c [:a]} :b)))
  (is (= {:a #{}, :b #{:a :x}}
         (dep/subtree-from {:a [], :b [:a :x], :c [:a]} :b))))
