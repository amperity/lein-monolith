lein-monolith
=============

A Leiningen plugin to work with multiple projects inside a monorepo.

## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following plugin to your user profile or project
definitions:

[![Clojars Project](http://clojars.org/mvxcvi/lein-monolith/latest-version.svg)](http://clojars.org/mvxcvi/lein-monolith)

## Usage

Monolith offers a number of tasks to make working with monorepos easier.

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
