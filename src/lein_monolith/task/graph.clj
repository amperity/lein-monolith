(ns lein-monolith.task.graph
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [lein-monolith.dependency :as dep]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein])
  (:import
    java.io.File))


(def default-image-name
  "project-hierarchy.png")


(defn- mkparent!
  "Ensure the parent directory exists for the given file."
  [^File file]
  (.. file getCanonicalFile getParentFile mkdir))


(defn cluster->descriptor
  [monolith-root subdirectory]
  (when (not= monolith-root subdirectory)
    {:label (subs (str subdirectory)
                  (inc (count monolith-root)))}))


(defn graph
  "Generate a graph of subprojects and their interdependencies."
  [project opts]
  ;; NOTE: This is pulled in on-demand here because Rhizome needs to load the
  ;; JVM's graphical context in order to render the hierarchy images. This has
  ;; the unfortunate side-effect of popping up a Java applet in most OS's task
  ;; bars, which can then steal focus away from the terminal. To keep this from
  ;; happening on _every_ invocation of lein-monolith, only load it when then
  ;; user actually wants to make graphs.
  (require 'rhizome.viz)
  (let [graph->dot (ns-resolve 'rhizome.dot 'graph->dot)
        dot->image (ns-resolve 'rhizome.viz 'dot->image)
        save-image (ns-resolve 'rhizome.viz 'save-image)
        [monolith subprojects] (u/load-monolith! project)
        targets (target/select monolith subprojects opts)
        dependencies (dep/dependency-map subprojects)
        dot-str (graph->dot
                  targets
                  dependencies
                  :vertical? false
                  :node->descriptor #(array-map :label (name %))
                  :node->cluster (fn [id]
                                   (when-let [root (get-in subprojects [id :root])]
                                     (str/join "/" (butlast (str/split root #"/")))))
                  :cluster->descriptor (partial cluster->descriptor (:root monolith)))]
    (when (empty? targets)
      (lein/abort "No targets selected to graph!"))
    (when-let [dot-file (some-> (:dot-path opts) io/file)]
      (mkparent! dot-file)
      (spit dot-file dot-str)
      (lein/info "Wrote dependency graph data to" (str dot-file)))
    (when-let [graph-file (or (some-> (:image-path opts) io/file)
                              (io/file (:target-path monolith) default-image-name))]
      (mkparent! graph-file)
      (save-image (dot->image dot-str) (str graph-file))
      (lein/info "Generated dependency graph image at" (str graph-file)))))
