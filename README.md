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

```bash
lein monolith checkouts
```

### Check External Dependency Versions

This task loads the list of approved versions for external dependencies and
warns if the current project depends on an incorrect version. It will also warn
if any dependencies are neither internal projects nor have specified versions.

```bash
lein monolith deps
```

### Merged Source Profile

The plugin also creates a profile with `:src-paths` and `:test-paths` updated
to include the source and test files from all projects in the monorepo. The
`:dependencies` vector will also be merged across all projects.

This can be useful for running lint and tests on all the projects at once:

```bash
lein monolith with-all test
```
