(ns lein-monolith.dependency-test
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [lein-monolith.dependency :as dep])
  (:import [clojure.lang IExceptionInfo]))


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
      (is (= '[foo/baz bar/baz] (dep/resolve-name '[foo/baz bar/baz bar/qux] 'baz)))
      (is (= 'foo (dep/resolve-name projects 'foo)))
      (is (= 'foo (dep/resolve-name projects 'foo/foo)))
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


(deftest dependency-mapping
  (let [projects '{foo/a {:dependencies []}
                   foo/b {:dependencies [[foo/a "1.0.0"]]}
                   foo/c {:dependencies [[foo/a "1.0.0"]]}
                   foo/d {:dependencies [[foo/b "1.0.0"]
                                         [foo/c "1.0.0"]]}}]
    (is (= '{foo/a #{}, foo/b #{foo/a}, foo/c #{foo/a}, foo/d #{foo/b foo/c}}
           (dep/dependency-map projects))))
  (let [projects '{foo/a {:dependencies []}
                   foo/b {:dependencies [] :profiles {:test {:dependencies [[foo/a]]}}}}]
    (is (= `{foo/a #{}, foo/b #{foo/a}}
           (dep/dependency-map projects)))))


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
    (is (= #{:y} (dep/downstream-keys deps :y))))
  (let [deps {:a #{}, :b #{:a}, :c #{:a}, :d #{:b :c}}]
    (is (= #{:a :b :c :d} (dep/downstream-keys deps :a)))))

(defn maps-like [n m]
  (map #(into (array-map) (shuffle (seq %))) (repeat n m)))

; Int -> [Deps SmallestCycles]
(defn gen-dep-cycle
  "Create a dependency cycle of the specified size
  that also includes a cycle of length 3 (ie. between two deps).
  Returns a vector of the dependencies and a set of its
  smallest dependency cycles."
  [size]
  {:pre [(<= 5 size)]}
  ;comments assume size == 50
  (let [[cstart cend] ((juxt identity inc) (quot size 2))]
    [(into {}
           (map (fn [a]
                  [a
                   (condp = a
                     (dec size) #{0} ; 0->1-*>49->0
                     cend #{cstart} ; 24->25->24
                     (into #{} (range (inc a) size)))]))
           (range size))
     #{[cstart cend cstart]
       [cend cstart cend]}]))

(defn cycle-actually-occurs [deps c]
  {:pre [(vector? c)
         (seq c)
         (map? deps)
         (= (first c) (peek c))]}
  (boolean
    (reduce (fn [downstream el]
              (or (some-> el downstream deps)
                  (reduced nil)))
            (deps (first c))
            (next c))))

(deftest cycle-actually-occurs-test
  (is (cycle-actually-occurs {1 #{2} 2 #{1}} [1 2 1]))
  (is (not (cycle-actually-occurs {1 #{2} 2 #{3} 3 #{}} [1 2 1]))))

(deftest unique-cycles-test
  (is (= #{} (dep/unique-cycles {})))
  (is (= #{[2 2]} (dep/unique-cycles {2 #{2}})))
  (is (= #{} (dep/unique-cycles {1 #{2}})))
  (doseq [size [5 10 #_15]] ;gen cycles of these sizes (higher is very slow)
    (let [[deps smallest-cycles] (gen-dep-cycle size)]
      (doseq [c (maps-like 10 deps)] ;shuffle deps order <..> times
        (let [actual (dep/unique-cycles c)]
          (every? #(is (cycle-actually-occurs deps %)
                       (str "Cycle doesn't occur:\n"
                            "deps: " deps \newline
                            "claimed cycle: " %))
                  actual)
          (is (seq (set/intersection smallest-cycles actual))
              (str "Missing smallest cycle(s) for size " size ": "
                   (pr-str actual))))))))

(defn check-cycle-error [deps smlest-cycles]
  (doseq [deps (maps-like 10 deps)]
    (let [^Exception e (try (dep/topological-sort deps)
                            (catch Exception e e))]
      (is (instance? IExceptionInfo e)
          (str "Didn't throw an exception\ndeps: " deps))
      (is (re-find #"Dependency cycles? detected" (.getMessage e)))
      ; pretty printed dependency cycle appears in msg
      (is (->> e ex-data :cycles (some smlest-cycles))
          (str "Didn't include smallest cycle:\n"
               "deps: " deps "\n"
               "smallest-cycles: " smlest-cycles "\n"
               "actual cycles: " (->> e ex-data :cycles) "\n"
               "actual message: " (.getMessage e))))))

(deftest topological-sorting
  (let [deps {:a #{}, :b #{:a}, :c #{:a :b} :x #{:b} :y #{:c}}]
    (is (= [:a :b :c :x :y] (dep/topological-sort deps)))
    (is (= [:b :c :x] (dep/topological-sort deps [:x :c :b]))))
  (check-cycle-error {:a #{:b}, :b #{:c}, :c #{:a}}
                     #{[:a :b :c :a]
                       [:b :c :a :b]
                       [:c :a :b :c]})
  (doseq [size [5 10]] ;gen cycles of these sizes (higher is very slow)
    (let [[deps smallest-cycles] (gen-dep-cycle size)]
      (doseq [c (maps-like 5 deps)] ;shuffle deps order <..> times
        (check-cycle-error c smallest-cycles)))))

(deftest pretty-cycle-test
  (let [cycle+pretty
        {[1 1]
         ["+ 1"
          "^\\"
          "|_|"]

         [1 2 1]
         ["+ 1"
          "^ + 2"
          "|_/"]

         [1 2 3 1]
         ["+ 1"
          "^ + 2"
          "|  + 3"
          "|_/"]

         [1 2 3 4 1]
         ["+ 1"
          "^ + 2"
          "|  + 3"
          "|   + 4"
          "|__/"]
         
         [1 2 3 4 5 1]
         ["+ 1"
          "^ + 2"
          "|  + 3"
          "|   + 4"
          "|    + 5"
          "|___/"]}]
    (every? (fn [[c strs]]
              (let [expected (str/join \newline strs)
                    actual (dep/pretty-cycle c)]
                (testing (str "Pretty representation of cycle " c)
                  (is (= expected actual)))))
            cycle+pretty)))
