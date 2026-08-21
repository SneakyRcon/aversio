# Aversio

Aversio is a Maven core extension that derives Maven-compatible SemVer values
from Git tags and Conventional Commits. It is consumed before Maven builds a
project model, so projects can use `${revision}` without adding a runtime
dependency.

## Coordinates

```text
com.sneakyrcon:aversio:1.0.0
```

Stable releases are published to Maven Central. The only release tags that
Aversio reads are immutable `vX.Y.Z` tags. Legacy tags such as `2026.1` and
`besign-*` are ignored.

## Version rules

Aversio selects the nearest reachable stable `vX.Y.Z` tag. If no such tag is
reachable, the base is `0.0.0`. It then examines every commit after that tag:

| Commit | Bump |
| --- | --- |
| `!` in the header or `BREAKING CHANGE:` footer | major |
| `feat` | minor |
| `fix`, `perf`, or `revert` | patch |
| other valid types (`docs`, `chore`, `build`, `ci`, `refactor`, `test`, etc.) | no numeric bump |

Every commit in the range must have a valid Conventional Commit header. An
invalid header stops version resolution with an actionable error. This keeps
the version calculation deterministic and makes malformed history visible.

The resulting values are:

```text
clean checkout exactly at v3.0.0       3.0.0
fix after v3.0.0                        3.0.1-abc1234-snapshot
feature after v3.0.0                    3.1.0-abc1234-snapshot
breaking change after v3.0.0            4.0.0-abc1234-snapshot
docs-only commit after v3.0.0          3.0.0-abc1234-snapshot
dirty or untracked checkout             3.0.0-abc1234-dirty-snapshot
no tag plus a feature commit            0.1.0-abc1234-snapshot
source tree without Git metadata       0.0.0-snapshot
```

`abc1234` is the seven-character commit ID. Dirty means that Git reports a
tracked modification or an untracked, non-ignored file. Ignored build output
does not make a checkout dirty.

Stable releases must be clean tag checkouts and contain no snapshot suffix.
Snapshots are build artifacts and are never published as releases.

## First-release bootstrap

A Maven core extension cannot resolve the artifact version that is being
published for the first time. Publish Aversio `1.0.0` once with an explicit
revision and without a self-extension file:

```bash
mvn -Dversioning.disable=true -Drevision=1.0.0 clean verify
```

Create the immutable `v1.0.0` tag from that verified commit and publish it
through the release workflow. After Maven Central exposes
`com.sneakyrcon:aversio:1.0.0`, add this checked-in `.mvn/extensions.xml`:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0">
  <extension>
    <groupId>com.sneakyrcon</groupId>
    <artifactId>aversio</artifactId>
    <version>1.0.0</version>
  </extension>
</extensions>
```

All subsequent Aversio builds then use the previously published Aversio
artifact to version Aversio itself. The explicit `1.0.0` build is the only
bootstrap exception.

## Build and test

Use Maven 3.9 or newer and a JDK that can run it. The extension bytecode
targets Java 11 for broad Maven compatibility.

```bash
mvn -B --no-transfer-progress -Dversioning.disable=true -Drevision=1.0.0 clean verify
```

The explicit revision is intentional before the first self-hosted extension
release. After bootstrap, ordinary Maven commands use the checked-in core
extension.

For a deliberate exact version, use either property form:

```bash
mvn -Dversioning.version=1.2.3 clean verify
mvn -Dversioning.disable=true -Drevision=1.2.3 clean verify
```

The `versioning.version` and `VERSIONING_VERSION` override must be valid SemVer.

## Releases

The test workflow runs on every branch. Stable publication is driven by a
clean `vX.Y.Z` tag push:

1. verify the tagged checkout;
2. confirm the project version equals `X.Y.Z`;
3. create the GitHub Release with generated notes;
4. build with the explicit tag version;
5. sign and publish the artifacts to Maven Central.

Non-tag builds remain snapshots and do not create releases. Publishing needs a
verified `com.sneakyrcon` namespace, Central Portal credentials, and a GPG key.
The release workflow expects `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY`, and
`MAVEN_GPG_PASSPHRASE` secrets.

## License

Licensed under the Apache License, Version 2.0.
