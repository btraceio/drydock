# Releasing Drydock to Maven Central

Drydock publishes `io.btrace:drydock` to Maven Central via the
[Central Portal](https://central.sonatype.com), using the
[`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/)
plugin (Central Portal Publisher API — **not** the legacy OSSRH staging-profile
API). The publishable artifact (`jbangJar`) bundles both-arch native dylibs, so
**staging runs on macOS** in CI.

## Required GitHub Actions secrets

The plugin reads credentials from `ORG_GRADLE_PROJECT_*` Gradle properties. The
release workflow maps the existing org-level secrets onto those property names,
so **no new secrets are needed** — reuse what the org already has:

| Existing secret | Mapped to Gradle property | What it is |
| --- | --- | --- |
| `SONATYPE_USERNAME` | `mavenCentralUsername` | Central Portal user-token username |
| `SONATYPE_PASSWORD` | `mavenCentralPassword` | Central Portal user-token password |
| `GPG_SIGNING_KEY` | `signingInMemoryKey` | ASCII-armored PGP **private** key |
| `GPG_KEY_ID` | `signingInMemoryKeyId` | short key id (last 8 chars) |
| `GPG_SIGNING_PWD` | `signingInMemoryKeyPassword` | passphrase for that key |

> **Note:** `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` must be a **Central Portal**
> user token (generated at <https://central.sonatype.com/account>), not a legacy
> OSSRH login — the vanniktech plugin publishes through the Central Portal
> Publisher API. A legacy OSSRH token will fail with `401 Unauthorized`.

The public key must be published to a keyserver (e.g. `keys.openpgp.org`) for
Central's validation to pass.

## Cutting a release

Two ways to trigger `.github/workflows/release.yml`:

1. **workflow_dispatch** (recommended): Actions -> Release -> Run workflow,
   enter the version (e.g. `0.1.0`). CI bumps `app/build.gradle.kts` and
   `jbang-catalog.json`, commits `Release v<version>`, pushes tag `v<version>`,
   then stages.
2. **Manual tag**: bump both files yourself, commit, then
   `git push origin v<version>`.

## What CI does, and your one manual step

1. Two `native` jobs build the macOS libraries **natively, one arch per runner**
   — `arm64` on `macos-15`, `x86_64` on `macos-15-intel` — and upload each
   slice's dylibs as an artifact. (Both use the macOS 15 SDK, which ghostty
   requires; the older `macos-14` 14.5 SDK lacks `kCVPixelFormatType_30RGB_r210`.)
2. `stage-maven` (macOS) downloads both native artifacts, packages the
   `jbangJar` with `-Pnatives.prebuilt=true`, and uploads a **staged**
   deployment to the Central Portal. It does **not** auto-release.
3. `wait-for-maven` polls `repo1.maven.org` for up to 30 minutes.
4. **You** go to https://central.sonatype.com/publishing/deployments, review the
   staged deployment, and click **Publish**.
5. Once the artifact appears on Maven Central, `create-github-release` publishes
   the GitHub Release with the `jbangJar` attached.

If you don't click Publish within 30 minutes, `wait-for-maven` times out and the
release is not finalized; re-run that job (or the workflow) after publishing.

## Debugging the pipeline without cutting a release

To exercise the fragile parts of the pipeline (the macOS runner setup — Zig,
Metal toolchain, JDKs — plus the Gradle build and GPG signing) **without**
minting a real release, use the **dry run** input:

Actions -> Release -> Run workflow, enter the version, and tick **`dry_run`**.

With `dry_run` on, the workflow builds from the branch you launched it on and:

- **skips** `prepare` — no version bump, no commit, no tag, no push;
- runs `stage-maven` but publishes **`:app:publishToMavenLocal`** (into the
  runner's `~/.m2/repository`) instead of staging to the Central Portal — so the
  full runner setup (Zig, Metal, JDKs) and Gradle build/POM generation are
  exercised (signing is skipped for local installs, so no keys are needed);
- **skips** `wait-for-maven` and `create-github-release`.

The result: a full end-to-end run that leaves zero traces (no tags, no Central
deployment, no GitHub release) and can be re-run as many times as needed, with
no secrets required.
