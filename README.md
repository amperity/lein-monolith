lein-monolith
=============

[![CircleCI](https://circleci.com/gh/amperity/lein-monolith.svg?style=shield&circle-token=e57a92e79aa9113f1950498cbeeb0880c3f587d3)](https://circleci.com/gh/amperity/lein-monolith/tree/main)

`lein-monolith` is a Leiningen plugin to work with multiple projects inside a
monorepo. At a high level, the plugin gives you a way to:
- Share configuration across subprojects, such as `:repositories`,
  `:managed-dependencies`, `:env`, etc.
- Run tasks across a multiple projects matching sophisticated selection
  criteria.
- Run tasks across a globally-merged view of multiple projects.
- Query dependencies, generate graphs, and other utilities.

For a more detailed introduction to the project and some motivation, see this
[2016 Seajure presentation](https://docs.google.com/presentation/d/1jqYG2N2YalWdVG4oDqs1mua4hOyxVD_nejANrg6h8to/present).

## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following plugin to your user profile or project
definitions:

[![Clojars Project](http://clojars.org/lein-monolith/lein-monolith/latest-version.svg)](http://clojars.org/lein-monolith/lein-monolith)

## Configuration

The `monolith` task provides several commands to make working with monorepos
easier. In order to use them, you'll need to create some configuration telling
the plugin where your subprojects are.

The configuration is provided by a _metaproject_, which lives in the repository
root and must contain a value for the `:monolith` project key. Create a
top-level [`project.clj`](example/project.clj) file and add the plugin and
monolith entries.

See the [configuration docs](doc/config.md) for more details about the available
options.

## Usage

`lein-monolith` can be used inside the individual projects within the monorepo,
or you can use it from the repository root to operate on all the subprojects
together.

### Targeting Options

Many tasks which operate on multiple projects accept targeting options, which
generally select or filter the command to a subset of the subprojects.

- `:in <names>`             Add the named projects directly to the targets.
- `:upstream`               Add the transitive dependencies of the current project to the targets.
- `:upstream-of <names>`    Add the transitive dependencies of the named projects to the targets.
- `:downstream`             Add the transitive consumers of the current project to the targets.
- `:downstream-of <names>`  Add the transitive consumers of the named projects to the targets.
- `:select <key>`           Use a selector from the config to filter target projects.
- `:skip <names>`           Exclude one or more projects from the target set.

Each `<names>` argument can contain multiple comma-separated project names, and
all the targeting options may be provided multiple times.

### Subproject Info

To see a list of all the projects that lein-monolith knows about, you can use
the `info` task:

```
lein monolith info [:bare] [targets]
```

This will print out the config file location, coordinates of every subproject
found, and a relative path to their location within the repo. For scripting, you
can pass the `:bare` flag, which will restrict the output to just the project
name and path.

The plugin also provides the `deps-on` task to query which subprojects have a
certain dependency:

```
lein monolith deps-on lib-b
```

Or you can go the other way with `deps-of` to find the subprojects which a
certain project depends on:

```
lein monolith deps-of app-a
```

### Subproject Iteration

A useful higher-order task is `each`, which will run the following commands on
every subproject in the repo, in dependency order. That means that projects
which don't depend on any other internal projects will run first, letting you do
things like:

```
lein monolith each check
lein monolith each :upstream :parallel 4 install
lein monolith each :start my-lib/foo do check, test
lein monolith each :select :deployable uberjar
```

In addition to targeting options, `each` accepts:

- `:start <name>` provide a starting point for the subproject iteration.
- `:parallel` run subproject tasks concurrently, up to the number of specified
  threads.
- `:endure` continue applying the task to every subproject, even if one fails.
  If any projects fail, the command will still exit with a failure status. This
  is useful in situations such as CI tests, where you don't want a failure to
  halt iteration.
- `:report` show a detailed timing report after the tasks finish executing.
- `:silent` suppress task output for successful projects.
- `:output` path to a directory to save individual build output in.

#### Incremental Builds

The `:refresh` option only visits projects that have changed since the last
`:refresh`. This allows incrementally building your projects:

```
lein monolith each :refresh ci/build install
```

The project is only considered refreshed if the task is successful. This means
you can run tests over the projects that have changed since the last
_successful_ test run:

```
lein monolith each :refresh ci/test test
```

Behind the scenes, lein-monolith is storing hashed fingerprints of each project,
which you can inspect and manually manipulate:

```
lein monolith changed
lein monolith mark-fresh :upstream ci/build
lein monolith clear-fingerprints ci/test
```

### Merged Source Profile

The plugin can create a profile with `:resource-paths`, `:source-paths` and
`:test-paths` updated to include the source and test files from multiple
projects in the monorepo. The profile also sets `:dependencies` on each internal
project, giving you a closure of all dependencies across all the subprojects.

This can be useful for running lint and tests on multiple subprojects at once:

```
lein monolith with-all [targeting] test
```

### Checkout Links

The `link` task creates
[checkout](https://github.com/technomancy/leiningen/blob/stable/doc/TUTORIAL.md#checkout-dependencies)
symlinks to all the internal packages that this project depends on. The plugin
also includes an `unlink` task as a convenience method for removing checkout
dependencies.

To link checkouts for all transitive dependencies, you can pass the `:deep` option.

If you have existing checkout links which conflict, you'll get warnings. To
override them, you can pass the `:force` option.

```
lein monolith link [:deep :force] [project...]
lein monolith unlink [:all] [project...]
```

In general, it's recommended to only link between the projects you're actually
actively working on, otherwise Leiningen has to recursively trace the full tree
of checkouts before running things.


## License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file
for rights and restrictions.
