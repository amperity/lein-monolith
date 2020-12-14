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
