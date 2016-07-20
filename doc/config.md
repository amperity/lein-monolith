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

## Config Inheritance

In order to share common project definition entries, you can also set the
`:inherit` key to a vector of attributes which should be inherited by
subprojects. In each subproject where you want this behavior, add a
`:monolith/inherit` key.

A value of `true` will merge in a profile with the attributes set in the
metaproject. Alternately, you can provide a vector of additional keys to merge
from the metaproject. Attaching `^:replace` metadata will cause the vector to
override the attributes set in the metaproject.
