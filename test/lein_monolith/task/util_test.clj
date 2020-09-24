(ns lein-monolith.task.util-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [lein-monolith.task.util :as u]))


(deftest shell-escaping
  (is (= "nil" (u/shell-escape nil)))
  (is (= "123" (u/shell-escape 123)))
  (is (= "foo" (u/shell-escape "foo")))
  (is (= ":abc" (u/shell-escape :abc)))
  (is (= "'[123 true]'" (u/shell-escape [123 true])))
  (is (= "'\\'foo'" (u/shell-escape "'foo")))
  (is (= "'\"xyz\"'" (u/shell-escape "\"xyz\""))))


(deftest kw-arg-parsing
  (testing "empty arguments"
    (is (= [{} []]
           (u/parse-kw-args {} [])))
    (is (= [{} []]
           (u/parse-kw-args {:foo 0} []))))
  (testing "flag options"
    (is (= [{:foo true} []]
           (u/parse-kw-args {:foo 0} [":foo"])))
    (is (= [{:foo true} ["bar"]]
           (u/parse-kw-args {:foo 0} [":foo" "bar"]))))
  (testing "single-value options"
    (is (= [{:abc "xyz"} ["123"]]
           (u/parse-kw-args {:abc 1} [":abc" "xyz" "123"])))
    (is (= [{} ["%" ":abc" "123"]]
           (u/parse-kw-args {:abc 1} ["%" ":abc" "123"]))))
  (testing "multi-arg options"
    (is (= [{:tri-arg ["1" "2" "3"]} ["abc"]]
           (u/parse-kw-args {:tri-arg 3} [":tri-arg" "1" "2" "3" "abc"])))
    (is (= [{:missing ["abc"]} []]
           (u/parse-kw-args {:missing 2} [":missing" "abc"])))
    (is (= [{:foo ["x" "y"]} ["bar"]]
           (u/parse-kw-args {:foo 2} [":foo" "a" "b" ":foo" "x" "y" "bar"]))
        "should overwrite prior option"))
  (testing "multi-value options"
    (is (= [{:foo ["a"]} []]
           (u/parse-kw-args {:foo* 1} [":foo" "a"])))
    (is (= [{:foo ["a" "b"], :bar true} []]
           (u/parse-kw-args {:foo* 1, :bar 0} [":foo" "a" ":bar" ":foo" "b"]))))
  (testing "combo args"
    (is (= [{:foo "1", :bar true} ["xyz"]]
           (u/parse-kw-args {:foo 1, :bar 0} [":foo" "1" ":bar" "xyz"])))
    (is (= [{:foo "x"} [":bar" "123" ":foo" "y"]]
           (u/parse-kw-args {:foo 1} [":foo" "x" ":bar" "123" ":foo" "y"]))
        "unknown arg should halt parsing")))
