(ns lein-monolith.task.with-dependency-set
  (:require
    [lein-monolith.plugin :as plugin]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein]))


(defn run-task
  "Runs the given task on the project with the given dependency set by reloading
   the project, changing the dependencies, re-initializing the project, and then
   running the task."
  [project dependency-set task]
  (let [[monolith _] (u/load-monolith! project)
        dependencies (or (get-in monolith [:monolith :dependency-sets dependency-set])
                         (lein/abort (format "Unknown dependency set %s" dependency-set)))
        managed-deps {:managed-dependencies (with-meta dependencies {:replace true})}
        profile (merge managed-deps
                       (when-not (:monolith project)
                         {:monolith/dependency-set dependency-set}))
        project (plugin/add-active-profile project :monolith/dependency-override profile)]
    (lein/resolve-and-apply project task)))
