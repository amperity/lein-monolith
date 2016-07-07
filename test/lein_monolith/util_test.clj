(ns lein-monolith.util-test
  (:require
    [clojure.test :refer :all]
    [lein-monolith.util :as u]))


(deftest kw-arg-parsing
  (is (= [{} []]
         (u/parse-kw-args {} [])))
  (is (= [{} []]
         (u/parse-kw-args {:foo 0} [])))
  (is (= [{:foo true} []]
         (u/parse-kw-args {:foo 0} [":foo"])))
  (is (= [{:foo true} ["bar"]]
         (u/parse-kw-args {:foo 0} [":foo" "bar"])))
  (is (= [{:abc [["xyz"]]} ["123"]]
         (u/parse-kw-args {:abc 1} [":abc" "xyz" "123"])))
  (is (= [{} ["%" ":abc" "123"]]
         (u/parse-kw-args {:abc 1} ["%" ":abc" "123"])))
  (is (= [{:missing [["abc"]]} []]
         (u/parse-kw-args {:missing 2} [":missing" "abc"])))
  (is (= [{:foo [["1"]], :bar true} ["xyz"]]
         (u/parse-kw-args {:foo 1, :bar 0} [":foo" "1" ":bar" "xyz"]))))


(deftest subtree-resolution
  (is (= {}
         (u/subtree-from {} nil)))
  (is (= {}
         (u/subtree-from {:a [], :b [:a]} nil)))
  (is (= {:a #{}}
         (u/subtree-from {:a [], :b [:a]} :a)))
  (is (= {:a #{}, :b #{:a}}
         (u/subtree-from {:a [], :b [:a], :c [:a]} :b)))
  (is (= {:a #{}, :b #{:a :x}}
         (u/subtree-from {:a [], :b [:a :x], :c [:a]} :b))))
