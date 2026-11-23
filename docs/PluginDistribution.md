# Plugin Distribution

How the Drydock IntelliJ plugin reaches users and how updates reach installed
IDEs. The channel is a self-hosted custom plugin repository backed by GitHub
Releases and GitHub Pages — not the JetBrains Marketplace.

## The mechanism

An IntelliJ-based IDE discovers plugin updates by polling *update site
descriptors*: small XML files listing the latest version of each plugin and
where to download it. A custom plugin repository is nothing more than such a
file (conventionally `updatePlugins.xml`) served over HTTPS, plus the plugin
zips it points at. There is no server-side logic: the IDE compares the
installed plugin's version with the entry in the file and offers the update.

Drydock's repository is hosted from the GitHub repo itself:

```
GitHub Release v0.3.0  ── asset: drydock-intellij-0.3.0.zip   (the artifact)
gh-pages branch        ── updatePlugins.xml                   (the descriptor)
                         https://btraceio.github.io/drydock/updatePlugins.xml
```

The descriptor points at the release asset:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="io.btrace.drydock"
          url="https://github.com/btraceio/drydock/releases/download/v0.3.0/drydock-intellij-0.3.0.zip"
          version="0.3.0">
    <idea-version since-build="263"/>
  </plugin>
</plugins>
```

The IDE needs `id` (must equal the `plugin.xml` id), `version` (must equal the
version inside the zip) and `url` (direct HTTPS download); `idea-version`
gates which IDE builds the entry applies to. The plugin builds for IU 2026.3
and declares `since-build="263"` with no `until-build`, so the entry stays
valid for future IDE majors until a new floor is introduced.

## User setup

One-time, per IDE:

1. `Settings | Plugins | ⚙ | Manage Plugin Repositories…`
2. Add `https://btraceio.github.io/drydock/updatePlugins.xml`
3. Install "Drydock" from the Marketplace-style list, or from a release zip.

After that the IDE checks configured repositories on its regular plugin-update
schedule and applies updates automatically (unless automatic plugin updates
are disabled in `Settings | Appearance & Behavior | System Settings | Updates`).

## Release flow

A release is a pushed tag. No source file is edited to release:

```
git tag v0.3.0 && git push origin v0.3.0
```

`.github/workflows/intellij-plugin.yml` then:

1. Runs the test suite and `buildPlugin updatePluginsXml` with
   `-Pversion=0.3.0` (derived from the tag by stripping `v`). The Gradle
   `version` property feeds `patchPluginXml`, so the zip's own descriptor and
   the update-site entry carry the same version — both are generated from the
   patched descriptor (`updatePluginsXml` reads `build/tmp/patchPluginXml/plugin.xml`,
   not `build.gradle.kts`, so the two can never drift apart).
2. Uploads the zip and `updatePlugins.xml` as the build artifact.
3. The `release` job creates a GitHub Release for the tag with the zip as an
   asset, then commits `updatePlugins.xml` to the `gh-pages` branch.
   `gh-pages` must be selected as the Pages source once
   (`Settings | Pages | Build and deployment | Source: Deploy from a branch → gh-pages`),
   after which `https://btraceio.github.io/drydock/` serves the descriptor.

Version discipline: the IDE compares versions using component-wise numeric
ordering, not lexical string ordering (for example, `0.10.0` is newer than
`0.9.0`). Pre-release suffixes such as `-beta.1` rank below the corresponding
plain release. Versions must strictly increase for the IDE to offer an update,
a tag must never be reused, and re-releasing means a new tag.

## Before tagging

Run the Plugin Verifier locally — CI does not run it (it extracts ~4 GB per
IDE version and exhausts the runner disk):

```
./gradlew verifyPlugin
```

## Signing

Plugins from a custom repository are not signed or vetted by JetBrains the way
Marketplace plugins are; the IDE verifies such a plugin's signature against
certificates the user has trusted (`Settings | Plugins | Manage Plugin
Certificates`), not against the JetBrains CA. An unsigned plugin installs and
updates, but the IDE shows a trust/warning dialog on install.

Drydock is signed with its own self-signed certificate chain when the signing
secrets exist. The build wires the convention-named environment variables
(`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`); with them absent
or blank the `signPlugin` task skips itself, so local and unsigned-CI builds
are unaffected. CI reads the secrets `PLUGIN_SIGNING_CERT_CHAIN`,
`PLUGIN_SIGNING_PRIVATE_KEY` and `PLUGIN_SIGNING_KEY_PASSWORD`.

To create the key and chain (marketplace-zip-signer compatible PKCS#8):

```
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -keyout private.pem -out chain.crt \
  -subj "/CN=Drydock Plugin/O=btrace.io"
```

`chain.crt` is the certificate chain users can import into
`Manage Plugin Certificates` (or trust via a `keytool`-built JKS passed with
the `intellij.plugins.truststore` system property); `private.pem` and a
password go into the GitHub secrets. Verify the signed artifact locally with
`./gradlew verifyPluginSignature` after setting the environment variables.

Signing is optional for the update flow: the IDE applies unsigned updates from
the custom repository with the same confirmation it showed at first install.

## Why not the JetBrains Marketplace

| | Custom repository (chosen) | Marketplace |
|---|---|---|
| Release gate | none — push a tag | JetBrains review queue before every listing update |
| Auto-updates | IDE polls the update site | built-in, same mechanism |
| Discoverability | none — users add the repo URL manually | searchable public listing |
| Signing | self-signed, user trusts the cert | verified through JetBrains trust chain |
| Required accounts/secrets | none | JetBrains account + publish token |

The Marketplace is the right channel once the plugin wants public reach; the
Gradle build is already compatible (`publishPlugin` needs only a
`PUBLISH_TOKEN`), so switching later is a workflow change, not a build change.

## Gotchas

- `updatePlugins.xml` must contain exactly one entry per plugin id; the
  release job overwrites the whole file, so it always stays single-entry.
- The `url` must be HTTPS and directly downloadable (the release-asset URL
  satisfies both; raw HTML pages do not).
- An entry whose `idea-version` range excludes the running IDE build makes the
  plugin invisible for updates in that IDE ("No plugins found" on the custom
  repo is usually this).
- A plugin installed from disk (From Disk…) has no update source; it will not
  pick up updates until reinstalled from the configured repository.
