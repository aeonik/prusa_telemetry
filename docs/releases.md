# Releases

Releases are git tags plus built artifacts.

## Versioning

Use SemVer-style tags:

```text
v0.2.0
v0.2.1
v0.3.0
```

Before tagging, update `version` in `build.clj` and any user-facing docs that
show the exact jar name.

## Local Release Checklist

```bash
clojure -M:test
clojure -T:build release
git status --short
git tag -a v0.2.0 -m "Release v0.2.0"
git push origin main v0.2.0
```

Release artifacts:

```text
target/prusa-telemetry.jar
target/prusa-telemetry.jar.sha256
target/prusa_telemetry-VERSION.jar
target/prusa_telemetry-VERSION.jar.sha256
```

## CI

The GitHub Actions workflow installs Java, the Clojure CLI, and Node. Clojure
CLI drives the tests and release build; npm is only used by the build to install
React packages needed for ClojureScript compilation. Tag pushes create release
artifacts.
