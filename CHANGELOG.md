Change Log
==========

All notable changes to this project will be documented in this file, which
follows the conventions of [keepachangelog.com](http://keepachangelog.com/).
This project adheres to [Semantic Versioning](http://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/amperity/lein-monolith/compare/0.3.2...HEAD
[0.3.2]: https://github.com/amperity/lein-monolith/compare/0.3.1...0.3.2
[0.3.1]: https://github.com/amperity/lein-monolith/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/amperity/lein-monolith/compare/0.2.3...0.3.0
[0.2.3]: https://github.com/amperity/lein-monolith/compare/0.2.2...0.2.3
[0.2.2]: https://github.com/amperity/lein-monolith/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/amperity/lein-monolith/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/amperity/lein-monolith/compare/0.1.1...0.2.0
[0.1.1]: https://github.com/amperity/lein-monolith/compare/0.1.0...0.1.1
