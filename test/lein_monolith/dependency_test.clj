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
  (testing "resolve-name"
    (let [projects '[foo baz/foo example/bar example/baz]]
      (is (nil? (dep/resolve-name projects 'qux)))
      (is (nil? (dep/resolve-name projects 'example/qux)))
      (is (= 'foo (dep/resolve-name projects 'foo)))
      (is (= 'example/bar (dep/resolve-name projects 'bar)))
      (is (= 'baz/foo (dep/resolve-name projects 'baz/foo)))
      (is (= 'example/baz (dep/resolve-name projects 'baz)))))
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


(deftest upstream-dependency-closure
  (let [deps {:a #{}, :b #{:a}, :c #{:a :b} :x #{:b} :y #{:c}}]
    (is (= #{:a} (dep/upstream-keys deps :a)))
    (is (= #{:a :b} (dep/upstream-keys deps :b)))
    (is (= #{:a :b :c} (dep/upstream-keys deps :c)))
    (is (= #{:a :b :x} (dep/upstream-keys deps :x)))
    (is (= #{:a :b :c :y} (dep/upstream-keys deps :y)))))


(deftest downstream-dependency-closure
  (let [deps {:a #{}, :b #{:a}, :c #{:a :b} :x #{:b} :y #{:c}}]
    (is (= #{:a :b :c :x :y} (dep/downstream-keys deps :a)))
    (is (= #{:b :c :x :y} (dep/downstream-keys deps :b)))
    (is (= #{:c :y} (dep/downstream-keys deps :c)))
    (is (= #{:x} (dep/downstream-keys deps :x)))
    (is (= #{:y} (dep/downstream-keys deps :y)))))
