# Maven Central release CI for Drydock — design

Date: 2026-07-23
Status: Approved (design), pending implementation plan

## Goal

Make it possible to publish Drydock's artifacts to Maven Central from CI. Model
the approach on `~/src/btrace`, which already publishes to the Central Portal,
but adapt to Drydock's fundamental difference: its publishable artifact bundles
native code and can only be built on macOS.

## Coordinates & artifacts

- GAV: `io.btraceio:drydock:<version>` (currently `0.1.0`).
- Published artifacts (already defined in `app/build.gradle.kts` `publishing`
  block, publication name `drydock`):
  - main artifact: `jbangJar` — the thin, publishable jar that **bundles both
    macOS arch native dylibs** (`libghostty`, `libdrydockterminalhost`) — NOT
    `components["java"]` (whose POM would leak host-specific JavaFX classifiers).
  - `sourcesJar`, `javadocJar`.
  - POM: classifier-less runtime deps (JavaFX 26 base/graphics/controls,
    richtextfx) so jbang resolves the host classifier + module path at run time.

## The constraint that shapes the design

btrace is pure Java and stages to Central from `ubuntu-latest`. Drydock's
`jbangJar` embeds native `libghostty` + host dylibs for **both** macOS
architectures. Building it requires:

- macOS runner (`macos-14`, arm64),
- Zig **0.15.2** (ghostty pins `minimum_zig_version = 0.15.2`; a newer Homebrew
  `zig` fails at comptime — see `scripts/build-ghostty.sh`),
- full Xcode incl. the **Metal Toolchain** component,
- the `third_party/ghostty` git submodule initialized.

Therefore the staging job runs on `macos-14`, not Ubuntu. This is the only
structural deviation from the btrace pattern.

## Part A — Gradle publishing configuration

Reuses btrace's Central Portal setup (OSSRH was sunset 2025-06-30; the Portal
exposes OSSRH-compat endpoints consumed by the nexus-publish plugin).

1. **Root `build.gradle.kts`**
   - Apply `io.github.gradle-nexus.publish-plugin` version `2.0.0`.
   - Set root `group = "io.btraceio"` and `version` so the plugin can resolve
     its package group (defaults to `rootProject.group`).
   - Add a `nexusPublishing` block:
     - `nexusUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/"`
     - `snapshotRepositoryUrl = "https://central.sonatype.com/repository/maven-snapshots/"`
     - credentials from `sonatype.username`/`sonatype.password` properties, else
       `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` env (Central Portal **user
       token**, not legacy OSSRH login).
   - The plugin adds a `sonatype` staging repository to the `:app` publication,
     yielding `publishAllPublicationsToSonatypeRepository`.

2. **`app/build.gradle.kts` signing block** (currently keyring-based)
   - Add in-memory PGP keys, mirroring btrace's `btrace-dist`:
     - `signingKey` from `gpg.signing.key` prop else `GPG_SIGNING_KEY` env
     - `signingPwd` from `gpg.signing.pwd` prop else `GPG_SIGNING_PWD` env
     - `if (signingKey != null && signingPwd != null) useInMemoryPgpKeys(...)`.
   - Keep the existing guard: sign only for a real publish to a repository,
     never for `publishToMavenLocal`.

3. **Version single-sourcing**
   - `version` is hardcoded `0.1.0` in `app/build.gradle.kts` AND in
     `jbang-catalog.json` (`script-ref: io.btraceio:drydock:0.1.0`). The release
     workflow rewrites both during a `workflow_dispatch` release. A maintainer
     cutting a release by hand bumps both, commits, and pushes the tag.

## Part B — Release workflow (`.github/workflows/release.yml`)

**One** workflow, **two** triggers, to avoid the well-known gotcha that a tag
pushed with the default `GITHUB_TOKEN` does not re-trigger another workflow.

Triggers:
- `push: tags: ['v*']`
- `workflow_dispatch` with a `version` input (e.g. `0.1.0`).

Jobs:

1. **`setup`** — resolve `version` and `ref` as outputs:
   - tag push → `version = ${GITHUB_REF_NAME#v}`, `ref = <the tag>`.
   - dispatch → `version = inputs.version`; depends on `prepare` (below);
     `ref = <the pushed tag>`.
   - Validate `version` matches `^[0-9]+\.[0-9]+\.[0-9]+$`.

2. **`prepare`** (dispatch only) — bump + tag:
   - checkout, configure git bot identity,
   - rewrite `version = "..."` in `app/build.gradle.kts`,
   - rewrite the `script-ref` version in `jbang-catalog.json`,
   - commit `Release v<version>`, create + push annotated tag `v<version>`.
   - (Because this runs inside the same workflow run, the pushed tag not
     re-triggering the workflow is irrelevant — the same run continues.)

3. **`stage-maven`** (`macos-14`) — build natives + stage to Central:
   - `actions/checkout` with `submodules: recursive`, `ref: <setup.ref>`,
   - install Zig 0.15.2 (pinned; e.g. `mlugg/setup-zig` or `brew install
     zig@0.15`),
   - ensure Metal Toolchain (`xcodebuild -downloadComponent MetalToolchain`
     defensively; the build script already fails clearly if absent),
   - set up JDK (Temurin; version per Drydock's toolchain, JDK 17+ for the
     daemon, project targets 26),
   - `./gradlew publishAllPublicationsToSonatypeRepository --no-daemon` with env
     `GPG_SIGNING_KEY`, `GPG_SIGNING_PWD`, `SONATYPE_USERNAME`,
     `SONATYPE_PASSWORD`.
   - Result: a **staged** deployment. Does NOT auto-release — the maintainer
     reviews and clicks **Publish** at
     https://central.sonatype.com/publishing/deployments.

4. **`wait-for-maven`** — poll for availability (btrace-style):
   - poll `https://repo1.maven.org/maven2/io/btraceio/drydock/<version>/drydock-<version>.pom`
     every 30s up to ~30 min (until the maintainer clicks Publish).
   - Timeout prints manual-completion instructions and fails.

5. **`create-github-release`** — `gh release create v<version>` with generated
   notes; attach the `jbangJar`. (Runtime image / `.app` / `.dmg` are a separate
   concern, out of scope here.)

The in-repo `jbang-catalog.json` update happens in `prepare`, so no separate
cross-repo catalog job is needed (btrace's catalog lives in a separate repo;
Drydock's is in-tree).

## Required GitHub secrets

- `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` — Central Portal **user token**.
- `GPG_SIGNING_KEY` — ASCII-armored private key.
- `GPG_SIGNING_PWD` — its passphrase.

## Decisions taken (approved defaults)

- Staging runs on `macos-14` (single runner; both arch dylibs come from one
  universal build via Zig cross-compile — no matrix).
- `workflow_dispatch` performs the version bump + commit + tag; direct tag push
  is the manual alternative.
- GitHub Release attaches `jbangJar` only.
- Stage-only (manual Publish click), never auto-release.

## Known risk to validate during implementation

Metal Toolchain availability on GH `macos-14` runners. If not preinstalled with
the runner's Xcode, the `xcodebuild -downloadComponent MetalToolchain` step (or
selecting an Xcode that includes it) is required before `buildGhosttyNative`.
The build scripts already detect and fail with an actionable message, so a first
CI run will surface this deterministically.

## Out of scope (vs btrace's release.yml)

RC tags, release branches, dry-run mode, release smoke tests, SDKMAN release,
milestone updates, a separate jbang-catalog repository.
