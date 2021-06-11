Change Log
==========

All notable changes to this project will be documented in this file, which
follows the conventions of [keepachangelog.com](http://keepachangelog.com/).
This project adheres to [Semantic Versioning](http://semver.org/).


## [Unreleased]


## [1.7.0] - 2021-06-11

### Added
- The `:project-dirs` pattern can now support recursive subdirectories with
  a double-wildcard `.../**` syntax.
  [#85](https://github.com/amperity/lein-monolith/pull/85)

### Fixed
- Fingerprinting will now correctly track Java files as project source files.
  [#87](https://github.com/amperity/lein-monolith/pull/87)


## [1.6.1] - 2020-10-09

### Changed
- The `lint` task now only considers dependency names and versions for
  detecting conflicts, which should improve the signal-to-noise ratio.
  [#73](https://github.com/amperity/lein-monolith/pull/73)
- The `unlink` task will now only remove internal checkouts by default. It also
  accepts an `:all` option to remove external checkouts, as well as a list of
  project names to specifically unlink.
  [#66](https://github.com/amperity/lein-monolith/issues/66)
  [#80](https://github.com/amperity/lein-monolith/pull/80)

### Fixed
- When the `each` task provides a command to resume execution, the arguments
  will be properly quoted for shells.
  [#27](https://github.com/amperity/lein-monolith/issues/27)
  [#72](https://github.com/amperity/lein-monolith/pull/72)
- The `each` task is now compatible with composite profiles.
  [#29](https://github.com/amperity/lein-monolith/issues/29)
  [#77](https://github.com/amperity/lein-monolith/pull/77)
- When `each` is used with `:parallel`, task aliases are now correctly resolved
  before iteration starts.
  [#36](https://github.com/amperity/lein-monolith/issues/36)
  [#74](https://github.com/amperity/lein-monolith/pull/74)

### Added
- The monolith settings can now use `:inherit-raw` and `:inherit-leaky-raw` to
  list keys which should be inherited without interpretation from the
  metaproject. This is useful for inheriting source paths without them being
  canonicalized.
  [#68](https://github.com/amperity/lein-monolith/issues/68)
  [#75](https://github.com/amperity/lein-monolith/pull/75)
- The `each` task supports a new `:silent` option, which will suppress task
  output for successful projects. This can be useful in large CI builds where
  the output is only consulted in the event of failure.
  [#37](https://github.com/amperity/lein-monolith/issues/37)
  [#81](https://github.com/amperity/lein-monolith/pull/81)


## [1.5.0] - 2020-09-17

### Added
- Subproject fingerprints now includes the Java version in the calculation.
  [#71](https://github.com/amperity/lein-monolith/pull/71)


## [1.4.0] - 2019-11-08

### Fixed
- When an exception is thrown during an `each :endure` iteration, the stack
  trace is printed immediately instead of swallowing the error.
- When an exception is thrown during an `each :output` iteration, the stack
  trace is printed in the output file in addition to the combined output.
  [#56](https://github.com/amperity/lein-monolith/pull/56)
- Subtasks of `do` are resolved before parallel execution, which should ensure
  they are fully loaded before they are called.
- Prevent a potential race condition when combining the `:parallel` and
  `:output` options in the `each` subtask.

### Added
- The `graph` subtask supports an `:image-path` option to explicitly specify the
  graph image output, as well as a `:dot-path` option to also write the raw dot
  definition for the graph.
- New `deps` subtask supports listing all project dependencies in the monorepo.
  The output should be suitable for other tooling to consume.


## [1.3.2] - 2019-10-21

### Fixed
- Subproject dependency calculation now includes dependencies declared in the
  project's profiles.
  [#51](https://github.com/amperity/lein-monolith/pull/51)


## [1.3.1] - 2019-10-14

### Fixed
- Subproject fingerprints now include the project's artifact version in the
  calculation.

### Added
- Subprojects may include a `:monolith/fingerprint-seed` value as a way to force
  fingerprint invalidations when desired.


## [1.3.0] - 2019-10-07

### Changed
- Remove dependency on `puget` for colorized output and canonical printing. This
  avoids pulling in `fipp` which is problematic to use in Leiningen on Java 9+.
  [#49](https://github.com/amperity/lein-monolith/pull/49)

### Added
- Allow ANSI color output to be disabled by setting the `LEIN_MONOLITH_COLOR`
  environment variable to `no`, `false`, or `off`.


## [1.2.2] - 2019-09-11

### Fixed
- Ensure projects are not initialized concurrently to guard against "unbound fn"
  errors.
  [#15](https://github.com/amperity/lein-monolith/issues/15)
  [#48](https://github.com/amperity/lein-monolith/pull/48)

### Changed
- Adopted cljfmt style rules and added CI style checks.


## [1.2.1] - 2019-04-25

### Added
- Allow the `graph` subtask to take specific targeting options.
  [#43](https://github.com/amperity/lein-monolith/pull/43)

### Fixed
- The `each` subtask couldn't be composed with subsequent tasks if it had no
  work to do. [#44](https://github.com/amperity/lein-monolith/pull/44)


## [1.2.0] - 2019-01-08

### Changed
- The `each` subtask no longer fails when zero projects are selected.

### Added
- The `each` subtask supports `:refresh` and `:changed` to perform incremental
  runs over the projects.
- New `changed`, `mark-fresh`, and `clear-fingerprints` subtasks inspect and
  manipulate the underlying fingerprints used to perform incremental runs.

### Fixed
- `link` could try to link a project to itself and fail. [#41](https://github.com/amperity/lein-monolith/pull/41)
- Bumped puget version to 1.0.3 to support JDK 11.


## [1.1.0] - 2018-08-17

### Added
- The `link` subtask accepts a list of projects to target, allowing you to
  select which checkout links get created.

### Fixed
- The `graph` subtask could throw an exception when clusters exist at the root
  of the metaproject. [#31](https://github.com/amperity/lein-monolith/pull/31)


## [1.0.1] - 2017-05-22

### Added
- Metaprojects can specify an `:inherit-leaky` vector to generate a leaky
  profile for inclusion in subprojects' built artifacts.


## [1.0.0]

This release marks the first stable major release of the plugin. Actual feature
changes are small, but `lein-monolith` has seen enough production use to be
considered a mature project.

### Added
- New `:output` option to `each` task allows saving individual subproject output
  to separate files under the given directory.

### Fixed
- Tasks run with `each` now use the subproject's root as the working directory,
  rather than the monolith root. [#21](https://github.com/amperity/lein-monolith/issues/21)


## [0.3.2] - 2017-03-21

### Changed
- Abstracted targeting options to generalize to multiple tasks.

### Added
- The `info` task supports targeting options.
- The `with-all` task supports targeting options.

### Removed
- Drop deprecated `:subtree` targeting option.


## [0.3.1] - 2016-12-14

### Added
- Options taking a subproject name now support omitting the namespace component
  if only one project has that name.
- The `each` task supports additional dependency closure selection options,
  including `:in <projects>`, `:upstream`, `:upstream-of <projects>`,
  `:downstream`, and `:downstream-of <projects>`.
- Multiple `:project-selectors` can be provided to `each` in order to chain the
  filters applied to the targeted projects.

### Changed
- Option parsing is handled more uniformly within the plugin.
- The `:subtree` option to `each` is deprecated.

### Fixed
- Resolved a potential issue where filtering the targeted subprojects could
  result in invalid parallel execution order.


## [0.3.0] - 2016-09-16

### Added
- Add `:report` option to the `each` task to print out detailed timing once
  `each` completes.
- Add `each :parallel <threads>` option to run tasks on subprojects
  concurrently. Tasks still run in dependency order, but mutually independent
  projects are run simultaneously on a fixed-size thread pool.

### Changed
- Modify `each` to print a completion message after subproject tasks finish
  running. This improves output during parallel execution.


## [0.2.3] - 2016-08-15

### Added
- The `each` task supports an `:endure` option to continue iteration in the
  event of subproject failures. This supports better CI usage for testing.


## [0.2.2] - 2016-08-08

### Added
- The `each` task now adds a `:monolith/index` key to project maps passed to
  the project-selector function to enable mod-distribution logic.


## [0.2.1] - 2016-08-05

### Changed
- Split up subtasks into separate namespaces to improve code readability.

### Fixed
- Fix bug where options to `each` weren't output in the continuation command.

### Added
- Add `:deep` option to the `link` task to link checkouts for all transitive
  dependencies.
- Add explicit request for garbage-collection before running subproject tasks in
  `each` iteration.
- Warn when `with-all` is used in a subproject.


## [0.2.0] - 2016-07-20

This release contains a **breaking change** in how the plugin is configured! All
options are now contained in a required metaproject at the repository root
instead of a separate `monolith.clj` file.

This should also be much faster to run due to lazily initializing subprojects
instead of loading them all before running any commands.

### Changed
- Moved monolith configuration into metaproject definition.
- Subprojects are loaded lazily, resulting in dramatically reduced latency
  before plugin tasks are executed.
- The merged profile now includes `:resource-paths` from each subproject.
- The merged profile no longer merges all dependencies; instead, each subproject
  is included in the profile and dependencies are resolved transitively.

### Added
- Metaproject configuration may be inherited using the `:monolith/inherit` key
  in subprojects and `:monolith {:inherit [...]}` in the metaproject.
- New `lint` subtask runs the dependency conflict checks which previously ran
  during every merged profile task.
- Added unit tests and continuous-integration via CircleCI.
- `each` task supports `:skip <project>` and `:select <filter>` options.

### Removed
- Setting the `:monolith` key in a project no longer automatically includes the
  merged profile; instead, it is used for general plugin configuration.


## [0.1.1] - 2016-07-08

### Fixed
- Fixed a bug in which `each :subtree` would show the wrong number of total
  subprojects while printing its progress.
- Internal projects are now implicit dependencies of the merged monolith
  profile.


## 0.1.0 - 2016-07-07

Initial project release


[Unreleased]: https://github.com/amperity/lein-monolith/compare/1.6.1...HEAD
[1.6.1]: https://github.com/amperity/lein-monolith/compare/1.5.0...1.6.1
[1.5.0]: https://github.com/amperity/lein-monolith/compare/1.4.0...1.5.0
[1.4.0]: https://github.com/amperity/lein-monolith/compare/1.3.2...1.4.0
[1.3.2]: https://github.com/amperity/lein-monolith/compare/1.3.1...1.3.2
[1.3.1]: https://github.com/amperity/lein-monolith/compare/1.3.0...1.3.1
[1.3.0]: https://github.com/amperity/lein-monolith/compare/1.2.2...1.3.0
[1.2.2]: https://github.com/amperity/lein-monolith/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/amperity/lein-monolith/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/amperity/lein-monolith/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/amperity/lein-monolith/compare/1.0.1...1.1.0
[1.0.1]: https://github.com/amperity/lein-monolith/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/amperity/lein-monolith/compare/0.3.2...1.0.0
[0.3.2]: https://github.com/amperity/lein-monolith/compare/0.3.1...0.3.2
[0.3.1]: https://github.com/amperity/lein-monolith/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/amperity/lein-monolith/compare/0.2.3...0.3.0
[0.2.3]: https://github.com/amperity/lein-monolith/compare/0.2.2...0.2.3
[0.2.2]: https://github.com/amperity/lein-monolith/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/amperity/lein-monolith/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/amperity/lein-monolith/compare/0.1.1...0.2.0
[0.1.1]: https://github.com/amperity/lein-monolith/compare/0.1.0...0.1.1
