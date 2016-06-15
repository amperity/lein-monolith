lein-monolith
=============

Monolith is a Leiningen plugin to work with multiple projects inside a monorepo.

## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following plugin to your user profile or project
definitions:

[![Clojars Project](http://clojars.org/lein-monolith/lein-monolith/latest-version.svg)](http://clojars.org/lein-monolith/lein-monolith)

## Configuration

Monolith offers a number of tasks to make working with monorepos easier. To get
started, create a coniguration file named [`monolith.clj`](example/monolith.clj)
at the root of the monorepo.

The config file tells monolith where to find the projects inside the repo by
giving a vector of relative paths in the the `:project-dirs` key. Each entry
should point to a directory _containing_ the project dirs in question - you
don't need to specify every individual project location.

You can also specify a set of external dependency versions which should be used
by the projects inside the monorepo. This is a vector of vectors defined the
same way it is in a `project.clj` file.

## Usage

`lein-monolith` can be used inside the individual projects within the monorepo,
or you can use it from the repository root to operate on all the subprojects
together.

### Set Up Checkout Links

The `checkouts` task creates checkout symlinks to all the internal packages that
this project depends on.

```
lein monolith checkout
```

If you have existing checkout links which conflict, you'll get warnings. To
override them, you can pass the `--force` option.

### Check External Dependency Versions

This task loads the list of approved versions for external dependencies and
warns if the current project depends on an incorrect version.

```
lein monolith deps
```

To get warnings about external dependencies with no defined version, use the
`--unlocked` option. The `--strict` option will cause the task to exit with a
failure code if any dependencies don't match the expected spec.

### Merged Source Profile

The plugin also creates a profile with `:source-paths` and `:test-paths` updated
to include the source and test files from all projects in the monorepo. The
`:dependencies` vector will also be merged across all projects.

This can be useful for running lint and tests on all the projects at once:

```
lein monolith with-all test
```

## Tips

It is often convenient to create a 'top-level'
[`project.clj`](example/project.clj) file representing the entire monolith. By
setting the `:monolith` key, the plugin will automatically merge the full list
of source paths, test paths, and dependencies into the top-level project. This
is the same as running your commands using the `with-all` task above.
