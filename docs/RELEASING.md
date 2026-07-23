# Releasing Drydock to Maven Central

Drydock publishes `io.btraceio:drydock` to Maven Central via the
[Central Portal](https://central.sonatype.com). The publishable artifact
(`jbangJar`) bundles both-arch native dylibs, so **staging runs on macOS** in CI.

## Required GitHub Actions secrets

| Secret | What it is |
| --- | --- |
| `SONATYPE_USERNAME` | Central Portal user-token username |
| `SONATYPE_PASSWORD` | Central Portal user-token password |
| `GPG_SIGNING_KEY` | ASCII-armored PGP **private** key (`gpg --armor --export-secret-keys <id>`) |
| `GPG_SIGNING_PWD` | passphrase for that key |

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

1. `stage-maven` (macOS) builds the natives and uploads a **staged** deployment
   to the Central Portal. It does **not** auto-release.
2. `wait-for-maven` polls `repo1.maven.org` for up to 30 minutes.
3. **You** go to https://central.sonatype.com/publishing/deployments, review the
   staged deployment, and click **Publish**.
4. Once the artifact appears on Maven Central, `create-github-release` publishes
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
  full build + in-memory GPG signing path is validated with real secrets;
- **skips** `wait-for-maven` and `create-github-release`.

The result: a full end-to-end run that leaves zero traces (no tags, no Central
deployment, no GitHub release) and can be re-run as many times as needed. It
needs `GPG_SIGNING_KEY`/`GPG_SIGNING_PWD`; Sonatype credentials are not used.
