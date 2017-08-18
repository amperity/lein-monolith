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

Some metaproject settings are only useful if they are still present in the
generated build artifacts for the subprojects. The primary examples of these are
`:repositories` and `:managed-dependencies`. For these, you can specify a second
key in the metaproject called `:inherit-leaky`, which follows the format of
`:inherit` above. Properties in this profile will be included in the built JAR
and pom files for the subproject.

## Controlling color output

monolith uses colored text to highlight important information. This
can be disabled by adding `:print-color false` in the `:monolith`
section of your `project.clj`.

You can conditionally enable/disable color output by using a profile
like this:

```clojure
:profiles {:ci {:monolith {:print-color false}}}
```

And then using `lein with-profile +ci monolith ...` when you want to
disable color printing.
