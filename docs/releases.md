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
npm ci
clojure -M:test
clojure -T:build release
git status --short
git tag -a v0.2.0 -m "Release v0.2.0"
git push origin main v0.2.0
```

Release artifacts:

```text
target/prusa_telemetry-VERSION.jar
target/prusa_telemetry-VERSION.jar.sha256
```

## CI

The GitHub Actions workflow runs tests, compiles both ClojureScript builds, and
builds the release jar. Tag pushes create release artifacts.
