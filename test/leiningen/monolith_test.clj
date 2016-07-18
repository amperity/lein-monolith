(ns leiningen.monolith-test
  (:require
    [clojure.test :refer :all]
    [leiningen.monolith :as monolith]))


(deftest kw-arg-parsing
  (let [parse @#'monolith/parse-kw-args]
    (is (= [{} []]
           (parse {} [])))
    (is (= [{} []]
           (parse {:foo 0} [])))
    (is (= [{:foo true} []]
           (parse {:foo 0} [":foo"])))
    (is (= [{:foo true} ["bar"]]
           (parse {:foo 0} [":foo" "bar"])))
    (is (= [{:abc [["xyz"]]} ["123"]]
           (parse {:abc 1} [":abc" "xyz" "123"])))
    (is (= [{} ["%" ":abc" "123"]]
           (parse {:abc 1} ["%" ":abc" "123"])))
    (is (= [{:missing [["abc"]]} []]
           (parse {:missing 2} [":missing" "abc"])))
    (is (= [{:foo [["1"]], :bar true} ["xyz"]]
           (parse {:foo 1, :bar 0} [":foo" "1" ":bar" "xyz"])))))
