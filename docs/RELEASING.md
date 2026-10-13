# Releasing Drydock

Drydock releases are GitHub Releases produced by
[`.github/workflows/release.yml`](../.github/workflows/release.yml). There is no
Maven Central publication: the `jbangJar` and the Windows runtime image are
attached directly to the release, and `jbang-catalog.json` points at the
release asset URL. The publishable artifact (`jbangJar`) bundles both-arch
macOS native dylibs, so **it is built on macOS** in CI; the Windows image is
built on a Windows x64 runner.

## Cutting a release

Two ways to trigger `.github/workflows/release.yml`:

1. **workflow_dispatch** (recommended): Actions -> Release -> Run workflow,
   enter the version (e.g. `0.1.0`). CI bumps `app/build.gradle.kts`,
   `build.gradle.kts` and `jbang-catalog.json`, commits `Release v<version>`,
   pushes tag `v<version>`, and creates the GitHub Release.
2. **Manual tag**: bump the files yourself, commit, then
   `git push origin v<version>`.

No GitHub Actions secrets are needed beyond the default `GITHUB_TOKEN`.

## What CI does

1. Two `native` jobs build the macOS libraries **natively, one arch per runner**
   — `arm64` on `macos-15`, `x86_64` on `macos-15-intel` — and upload each
   slice's dylibs as an artifact. (Both use the macOS 15 SDK, which ghostty
   requires; the older `macos-14` 14.5 SDK lacks `kCVPixelFormatType_30RGB_r210`.)
2. `jbang-jar` (macOS) downloads both native artifacts and packages the
   `jbangJar` with `-Pnatives.prebuilt=true`. Build-only — nothing is published.
3. `windows-image` (windows-latest, x64) runs `:app:windowsRuntimeImage` and
   zips `build/image-windows/` as `drydock-windows-x64-<version>.zip` with a
   top-level `drydock-windows-x64/` folder inside the zip.
4. `create-github-release` creates the GitHub Release for tag `v<version>` and
   attaches `drydock-<version>.jar` and `drydock-windows-x64-<version>.zip`.
   It is idempotent: on a re-run it refreshes the assets (`--clobber`) instead
   of failing.

The release is fully automatic — there is no manual staging/publish step.

## jbang catalog

`jbang-catalog.json` resolves the alias against the GitHub release asset:

```json
"script-ref": "https://github.com/btraceio/drydock/releases/download/v<version>/drydock-<version>.jar"
```

so a release must exist (with the jar attached) before the catalog resolves.
The `prepare` job rewrites the URL as part of the version bump.

## Debugging the pipeline without cutting a release

To exercise the fragile parts of the pipeline (the macOS runner setup — Zig,
Metal toolchain, JDKs — plus the Gradle builds and the Windows image job)
**without** minting a real release, use the **dry run** input:

Actions -> Release -> Run workflow, enter the version, and tick **`dry_run`**.

With `dry_run` on, the workflow builds from the branch you launched it on and:

- **skips** `prepare` — no version bump, no commit, no tag, no push;
- runs `jbang-jar` and `windows-image` exactly as in a real release, so the
  full runner setup and Gradle builds are exercised (no secrets needed);
- **skips** `create-github-release`.

The result: a full end-to-end run that leaves zero traces (no tags, no GitHub
release) and can be re-run as many times as needed.
