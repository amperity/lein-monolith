Monolith Configuration
======================

This document lays out the various configuration options available in
`lein-monolith`.

## Subproject Locations

The `:project-dirs` key tells monolith where to find the projects inside the
repo by giving a vector of relative paths. Each entry should point to either a
direct subproject directory (containing a `project.clj` file) such as
`apps/app-a`, or end with a wildcard `*` to indicate that all child directories
should be searched for projects, like `libs/*`. Note that this only works with a
single level of wildcard matching at the end of the path.
If you would like to search recursively you can indicate that using `lib/**`
and it will search for all subdirectories containing a `project.clj` file.

## Config Inheritance

In order to share common project definition entries, you can also set the
`:inherit` key to a vector of attributes which should be inherited by
subprojects. In each subproject where you want this behavior, add a
`:monolith/inherit` key.

A value of `true` will merge in a profile with the attributes set in the
metaproject. Alternately, you can provide a vector of additional keys to merge
from the metaproject. Attaching `^:replace` metadata will cause the vector to
override the attributes set in the metaproject.

Some metaproject settings are only useful if they are still present in the
generated build artifacts for the subprojects. The primary examples of these are
`:repositories` and `:managed-dependencies`. For these, you can specify a second
key in the metaproject called `:inherit-leaky`, which follows the format of
`:inherit` above. Properties in this profile will be included in the built JAR
and pom files for the subproject.

Lastly, `lein-monolith` supports inheriting unprocessed values, via
`:inherit-raw` and `:inherit-leaky-raw`. These are of particular use when
inheriting paths, as Leiningen absolutizes paths upon processing a project map.
By using raw inheritance, you can safely inherit paths, e.g. `:test-paths` or
`:source-paths`.

## Dependency Sets

The `:dependency-sets` key can be configured in the metaproject to provide child
projects with a curated set of managed dependencies to opt into instead of using
a single list of managed dependencies in the metaproject. This should be a map of
dependency set names and their dependencies.

For example, in the metaproject file we can define a dependency set
called `:set-a` as follows:

```clj
(defproject lein-monolith.example/all "MONOLITH"

...

:monolith
  {:dependency-sets
   {:set-a
    [[amperity/greenlight "0.7.1"]
     [org.clojure/spec.alpha "0.3.218"]]}

...

)
```

The `:monolith/dependency-set` key can then be used to a opt child project into `:set-a` as follows:

```clj
(defproject lein-monolith.example/app-a "MONOLITH-SNAPSHOT"
 
 ...

 :monolith/dependency-set :set-a

 ...

)
```

By selecting a dependency set from the metaproject with `:monolith/dependency-set`,
you will merge in a profile with `:managed-dependencies` set to the dependencies within
the dependency set. If you also configure the child project to use inherited profiles, this profile
will be merged in *before* the inherited profiles. This means that dependency versions in
a dependency set will have precedence over versions in an inherited `:managed-dependencies` key.
