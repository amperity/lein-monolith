lein-monolith
=============

[![CircleCI](https://circleci.com/gh/amperity/lein-monolith.svg?style=svg&circle-token=e57a92e79aa9113f1950498cbeeb0880c3f587d3)](https://circleci.com/gh/amperity/lein-monolith/tree/master)

`lein-monolith` is a Leiningen plugin to work with multiple projects inside a monorepo.
For an introduction to the project and some motivation, see this
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

### Subproject Info

To see a list of all the projects that lein-monolith knows about, you can use
the `info` task:

```
lein monolith info [:bare]
```

This will print out the config file location, coordinates of every subproject
found, and a relative path to their location within the repo. For scripting, you
can pass the `:bare` flag, which will restrict the output to just the project
name and path.

The plugin also provides the `deps-on` task to query which subprojects have a
certain dependency:

```
lein monolith deps-on example/lib-b
```

Or you can go the other way with `deps-of` to find the subprojects which a
certain project depends on:

```
lein monolith deps-of example/app-a
```

### Subproject Iteration

A useful higher-order task is `each`, which will run the following commands on
every subproject in the repo, in dependency order. That means that projects
which don't depend on any other internal projects will run first, letting you do
things like:

```
lein monolith each check
lein monolith each :subtree install
lein monolith each :start my-lib/foo do check, test
```

### Merged Source Profile

The plugin also creates a profile with `:resource-paths`, `:source-paths` and
`:test-paths` updated to include the source and test files from all projects in
the monorepo. The profile also sets `:dependencies` on each internal project,
giving you a closure of all dependencies across all the subprojects.

This can be useful for running lint and tests on all the projects at once:

```
lein monolith with-all test
```

### Checkout Links

The `link` task creates
[checkout](https://github.com/technomancy/leiningen/blob/stable/doc/TUTORIAL.md#checkout-dependencies)
symlinks to all the internal packages that this project depends on. The plugin
also includes an `unlink` task as a convenience method for removing checkout
dependencies.

If you have existing checkout links which conflict, you'll get warnings. To
override them, you can pass the `:force` option.

```
lein monolith link [:force]
lein monolith unlink
```

In general, it's recommended to only link between the projects you're actually
actively working on, otherwise Leiningen has to recursively trace the full tree
of checkouts before running things.

## License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file
for rights and restrictions.
