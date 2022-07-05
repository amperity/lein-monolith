# Releasing

1. Update the version number in these places:

   - [project.clj](./project.clj)
   - [example/project.clj](./example/project.clj)

1. Update [CHANGELOG.md](./CHANGELOG.md). We follow the guidelines from
   [keepachangelog.com](http://keepachangelog.com/) and [Semantic
   Versioning](http://semver.org/)

1. Commit changes, create a PR, merge the PR into `main`.

1. Create a signed tag at the release commit. `git tag -s X.X.X -m "X.X.X
   Release" && git push X.X.X`

1. From the release commit, run `lein deploy clojars`, which will build and
   upload the plugin jar to the Clojars repository. You will need to be a member
   of the `lein-monolith` [Clojars group](https://clojars.org/groups/lein-monolith).
