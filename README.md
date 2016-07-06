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
should point to either a direct subproject directory (containing a `project.clj`
file) such as `apps/app-a`, or end with a `*` to indicate that all child
directories should be searched for projects, like `libs/*`. Note that this only
works with a single level of wildcard matching at the end of the path.

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

The plugin also provides the `depends` task to query which subprojects have a
certain dependency:

```
lein monolith depends example/lib-b
```

### Subproject Iteration

A useful higher-order task is `each`, which will run the following commands on
every subproject in the repo, in dependency order. That means that projects
which don't depend on any other internal projects will run first, letting you do
things like:

```
lein monolith each install
lein monolith each :start my-lib/foo do check, test
```

### Merged Source Profile

The plugin also creates a profile with `:source-paths` and `:test-paths` updated
to include the source and test files from all projects in the monorepo. The
`:dependencies` vector will also be merged across all projects.

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

## Tips

It is often convenient to create a 'top-level'
[`project.clj`](example/project.clj) file representing the entire monolith. By
setting the `:monolith` key, the plugin will automatically merge the full list
of source paths, test paths, and dependencies into the top-level project. This
is the same as running your commands using the `with-all` task above.

## License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file
for rights and restrictions.
