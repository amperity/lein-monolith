(ns lein-monolith.task.graph
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [lein-monolith.dependency :as dep]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]))


(def image-name "project-hierarchy.png")


(defn cluster->descriptor
  [monolith-root subdirectory]
  (when (not= monolith-root subdirectory)
    {:label (subs (str subdirectory)
                  (inc (count monolith-root)))}))


(defn graph
  "Generate a graph of subprojects and their interdependencies."
  [project opts]
  (require 'rhizome.viz)
  (let [visualize! (ns-resolve 'rhizome.viz 'save-graph)
        [monolith subprojects] (u/load-monolith! project)
        targets (target/select monolith subprojects opts)
        dependencies (dep/dependency-map subprojects)
        graph-file (io/file (:target-path monolith) image-name)]
    (.mkdir (.getParentFile graph-file))
    (visualize!
      (or (seq targets) (keys dependencies))
      dependencies
      :vertical? false
      :node->descriptor #(array-map :label (name %))
      :node->cluster (fn [id]
                       (when-let [root (get-in subprojects [id :root])]
                         (str/join "/" (butlast (str/split root #"/")))))
      :cluster->descriptor (partial cluster->descriptor (:root monolith))
      :filename (str graph-file))
    (lein/info "Generated dependency graph in" (str graph-file))))
