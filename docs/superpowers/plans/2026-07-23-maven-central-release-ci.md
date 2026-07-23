# Maven Central Release CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable CI to build Drydock's native-bundled artifact on macOS and stage it (with GPG signatures) to Maven Central via the Central Portal, then create a GitHub Release.

**Architecture:** Two parts. (A) Gradle: apply the `io.github.gradle-nexus.publish-plugin` at the root with a `nexusPublishing` block pointing at the Central Portal OSSRH-compat endpoints, and add in-memory GPG signing to the existing `:app` `signing` block. (B) A single `.github/workflows/release.yml` with two triggers (tag push `v*` and `workflow_dispatch`) whose `stage-maven` job runs on `macos-14` (because the publishable `jbangJar` bundles native `libghostty`/host dylibs for both arches), then `wait-for-maven` and `create-github-release`.

**Tech Stack:** Gradle 8.11 (Kotlin DSL), `io.github.gradle-nexus.publish-plugin:2.0.0`, GitHub Actions, Zig 0.15.2, Temurin JDK 17 (Gradle daemon) + 26 (app toolchain), `gh` CLI.

## Global Constraints

- GAV: `io.btraceio:drydock:<version>`; current version `0.1.0`.
- Publishable main artifact is `jbangJar` → `app/build/libs/drydock-<version>.jar` (`archiveBaseName=drydock`, empty classifier). Plus `sourcesJar`, `javadocJar`. Publication name is `drydock`.
- `jbangJar` bundles both-arch native dylibs → building it requires **macOS** + Zig **0.15.2** (ghostty pins `minimum_zig_version = 0.15.2`) + full Xcode/Metal Toolchain + the `third_party/ghostty` submodule.
- Gradle's own daemon runs on **JDK 17** (`gradle/gradle-daemon-jvm.properties` `toolchainVersion=17`); the app toolchain targets **JDK 26**. Both JDKs must be present and discoverable by Gradle toolchain detection on the runner.
- Central Portal endpoints (OSSRH sunset 2025-06-30): `nexusUrl = https://ossrh-staging-api.central.sonatype.com/service/local/`, `snapshotRepositoryUrl = https://central.sonatype.com/repository/maven-snapshots/`.
- Credentials via env: `SONATYPE_USERNAME`, `SONATYPE_PASSWORD` (Central Portal **user token**), `GPG_SIGNING_KEY` (ASCII-armored private key), `GPG_SIGNING_PWD`.
- **Stage-only**: never auto-release. CI uploads a staged deployment; a human clicks Publish at https://central.sonatype.com/publishing/deployments.
- Existing signing guard (keep it): sign only when publishing to a real repository, never for `publishToMavenLocal`.

---

## File Structure

- `build.gradle.kts` (root) — **Modify**: apply nexus-publish plugin; add `nexusPublishing`.
- `app/build.gradle.kts` — **Modify**: add in-memory GPG keys to the `signing` block.
- `.github/workflows/release.yml` — **Create**: the release workflow.
- `docs/RELEASING.md` — **Create**: how to cut a release + required secrets.
- `README.md` — **Modify**: one pointer line to `docs/RELEASING.md`.

---

### Task 1: Gradle publishing + in-memory signing config

**Files:**
- Modify: `build.gradle.kts:8-12` (root `plugins` block) and append `nexusPublishing`.
- Modify: `app/build.gradle.kts:234-238` (`signing` block).

**Interfaces:**
- Produces: the Gradle task `:app:publishAllPublicationsToSonatypeRepository` (staging upload), driven by env `SONATYPE_USERNAME`/`SONATYPE_PASSWORD` and `GPG_SIGNING_KEY`/`GPG_SIGNING_PWD`. Consumed by Task 2's `stage-maven` job.

- [ ] **Step 1: Add the nexus-publish plugin to the root `plugins` block**

In `build.gradle.kts`, change the existing block:

```kotlin
plugins {
    // Applied only in subprojects; declared here (with apply false) so the
    // version is resolved once for the whole build.
    id("org.openjfx.javafxplugin") version "0.1.0" apply false
    // Central Portal staging: adds a `sonatype` publishing repository and the
    // publish*ToSonatypeRepository tasks. Applied at the root so the plugin can
    // resolve its package group from rootProject.group.
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "io.btraceio"
version = "0.1.0"
```

- [ ] **Step 2: Append the `nexusPublishing` block to the root build file**

Add at the end of `build.gradle.kts`:

```kotlin
// Maven Central release automation via the Central Portal (OSSRH sunset
// 2025-06-30). Credentials are the Central Portal USER TOKEN, not a legacy
// OSSRH login. Left unset locally -> the publish task fails only when actually
// run, so `publishToMavenLocal` and every other task stay usable offline.
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(
                providers.gradleProperty("sonatype.username")
                    .orElse(providers.environmentVariable("SONATYPE_USERNAME"))
            )
            password.set(
                providers.gradleProperty("sonatype.password")
                    .orElse(providers.environmentVariable("SONATYPE_PASSWORD"))
            )
        }
    }
}
```

- [ ] **Step 3: Add in-memory GPG keys to the `:app` signing block**

In `app/build.gradle.kts`, replace the `signing { ... }` block (currently ~lines 234-238) with:

```kotlin
signing {
    // Sign only for a real publish, never for publishToMavenLocal.
    setRequired({ gradle.taskGraph.allTasks.any { it.name.startsWith("publish") && it.name.contains("Repository") && !it.name.contains("MavenLocal") } })

    // In-memory PGP key for CI (mirrors btrace's btrace-dist). Falls back to
    // the default gpg-agent/keyring when the env/props are absent (local dev).
    val signingKey = (findProperty("gpg.signing.key") as String?) ?: System.getenv("GPG_SIGNING_KEY")
    val signingPwd = (findProperty("gpg.signing.pwd") as String?) ?: System.getenv("GPG_SIGNING_PWD")
    if (signingKey != null && signingPwd != null) {
        useInMemoryPgpKeys(signingKey, signingPwd)
    }

    sign(publishing.publications["drydock"])
}
```

- [ ] **Step 4: Verify the staging task now exists and the build configures**

Run: `./gradlew :app:tasks --all --offline 2>/dev/null | grep -i sonatype`
Expected: lists `publishAllPublicationsToSonatypeRepository` and `publishDrydockPublicationToSonatypeRepository` (proves plugin + publication wired). If offline resolution of the plugin fails, drop `--offline`.

- [ ] **Step 5: Verify `publishToMavenLocal` still works unsigned (no regression)**

Run: `./gradlew :app:publishToMavenLocal`
Expected: BUILD SUCCESSFUL; `~/.m2/repository/io/btraceio/drydock/0.1.0/drydock-0.1.0.jar` exists and **no** `.asc` files (signing skipped for MavenLocal by the guard).

- [ ] **Step 6: Verify in-memory signing actually engages with a throwaway key**

This proves the CI signing path without touching your real key. Runs against `publishDrydockPublicationToSonatypeRepository`'s signing dependency only.

```bash
# Generate a throwaway, passphrase-protected key in an isolated GNUPGHOME.
export GNUPGHOME="$(mktemp -d)"
cat > "$GNUPGHOME/keyspec" <<'EOF'
%no-protection
Key-Type: RSA
Key-Length: 3072
Name-Real: Drydock CI Test
Name-Email: ci-test@example.com
Expire-Date: 0
EOF
gpg --batch --generate-key "$GNUPGHOME/keyspec"
KEYID=$(gpg --list-secret-keys --with-colons | awk -F: '/^sec:/{print $5; exit}')
export GPG_SIGNING_KEY="$(gpg --batch --pinentry-mode loopback --armor --export-secret-keys "$KEYID")"
export GPG_SIGNING_PWD=""   # %no-protection -> empty passphrase

# useInMemoryPgpKeys requires a non-null pwd; use a space-safe non-empty stub
# by regenerating with a passphrase instead if empty is rejected:
```

If Gradle rejects an empty passphrase, regenerate the key with `Passphrase: testpwd` (remove the `%no-protection` line, add `Passphrase: testpwd`) and set `GPG_SIGNING_PWD=testpwd`.

Then run the sign task:

Run: `GPG_SIGNING_KEY="$GPG_SIGNING_KEY" GPG_SIGNING_PWD="$GPG_SIGNING_PWD" ./gradlew :app:signDrydockPublication`
Expected: BUILD SUCCESSFUL; `.asc` signature files appear under `app/build/libs/` (e.g. `drydock-0.1.0.jar.asc`). Then clean up: `rm -rf "$GNUPGHOME"; unset GNUPGHOME GPG_SIGNING_KEY GPG_SIGNING_PWD`.

- [ ] **Step 7: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts
git commit -m "Wire Central Portal staging + in-memory GPG signing"
```

---

### Task 2: Release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: `:app:publishAllPublicationsToSonatypeRepository` (Task 1); `jbangJar` output `app/build/libs/drydock-<version>.jar`.
- Produces: nothing consumed by later tasks (terminal deliverable).

- [ ] **Step 1: Create the workflow file**

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags: ['v*']
  workflow_dispatch:
    inputs:
      version:
        description: 'Release version (e.g. 0.1.0)'
        required: true
        type: string

permissions:
  contents: write

jobs:
  setup:
    name: Resolve version
    runs-on: ubuntu-latest
    outputs:
      version: ${{ steps.resolve.outputs.version }}
      ref: ${{ steps.resolve.outputs.ref }}
    steps:
      - id: resolve
        run: |
          if [ "${{ github.event_name }}" = "push" ]; then
            V="${GITHUB_REF_NAME#v}"; REF="${GITHUB_REF_NAME}"
          else
            V="${{ github.event.inputs.version }}"; REF="v${V}"
          fi
          if ! echo "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            echo "::error::Invalid version '$V' (expected X.Y.Z)"; exit 1
          fi
          echo "version=$V" >> "$GITHUB_OUTPUT"
          echo "ref=$REF" >> "$GITHUB_OUTPUT"

  prepare:
    name: Bump version and tag
    needs: setup
    if: ${{ github.event_name == 'workflow_dispatch' }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
          token: ${{ secrets.GITHUB_TOKEN }}
      - name: Bump, commit, tag, push
        env:
          V: ${{ needs.setup.outputs.version }}
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          sed -i -E "s/^version = \".*\"/version = \"${V}\"/" app/build.gradle.kts
          sed -i -E "s|(\"script-ref\": \"io\.btraceio:drydock:)[^\"]*(\")|\1${V}\2|" jbang-catalog.json
          grep -q "version = \"${V}\"" app/build.gradle.kts || { echo "::error::version bump failed"; exit 1; }
          grep -q "io.btraceio:drydock:${V}" jbang-catalog.json || { echo "::error::catalog bump failed"; exit 1; }
          git add app/build.gradle.kts jbang-catalog.json
          git commit -m "Release v${V}"
          git tag -a "v${V}" -m "Release v${V}"
          git push origin "HEAD:${GITHUB_REF_NAME}"
          git push origin "v${V}"

  stage-maven:
    name: Stage to Maven Central
    needs: [setup, prepare]
    if: ${{ always() && needs.setup.result == 'success' && (needs.prepare.result == 'success' || needs.prepare.result == 'skipped') }}
    runs-on: macos-14
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ needs.setup.outputs.ref }}
          submodules: recursive
      - uses: mlugg/setup-zig@v1
        with:
          version: 0.15.2
      - name: Ensure Metal Toolchain
        run: xcodebuild -downloadComponent MetalToolchain || echo "Metal Toolchain already present or bundled"
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: |
            17
            26
      - name: Stage to Central Portal
        env:
          GPG_SIGNING_KEY: ${{ secrets.GPG_SIGNING_KEY }}
          GPG_SIGNING_PWD: ${{ secrets.GPG_SIGNING_PWD }}
          SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
          SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
        run: |
          ./gradlew :app:publishAllPublicationsToSonatypeRepository \
            --no-daemon --stacktrace \
            -Dorg.gradle.java.installations.fromEnv=JAVA_HOME_17_ARM64,JAVA_HOME_26_ARM64
      - name: Upload jbangJar for the release job
        uses: actions/upload-artifact@v4
        with:
          name: drydock-jbang-jar
          path: app/build/libs/drydock-${{ needs.setup.outputs.version }}.jar
          if-no-files-found: error
      - name: Staging complete
        run: |
          echo "Artifacts STAGED. Review and click Publish at:"
          echo "  https://central.sonatype.com/publishing/deployments"

  wait-for-maven:
    name: Wait for Maven Central
    needs: [setup, stage-maven]
    runs-on: ubuntu-latest
    timeout-minutes: 35
    steps:
      - name: Poll repo1 for the released POM
        run: |
          V="${{ needs.setup.outputs.version }}"
          URL="https://repo1.maven.org/maven2/io/btraceio/drydock/${V}/drydock-${V}.pom"
          echo "Waiting for ${URL}"
          echo "Click Publish at https://central.sonatype.com/publishing/deployments"
          for i in $(seq 1 60); do
            CODE=$(curl -s -o /dev/null -w '%{http_code}' "$URL")
            if [ "$CODE" = "200" ]; then echo "Available on Maven Central."; exit 0; fi
            echo "Attempt ${i}/60: HTTP ${CODE} - waiting 30s..."
            sleep 30
          done
          echo "::error::Timed out after 30 min. Release not finalized on Central."
          exit 1

  create-github-release:
    name: Create GitHub Release
    needs: [setup, wait-for-maven]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ needs.setup.outputs.ref }}
      - uses: actions/download-artifact@v4
        with:
          name: drydock-jbang-jar
          path: dist
      - name: Create release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          V="${{ needs.setup.outputs.version }}"
          gh release create "v${V}" \
            --title "Drydock v${V}" \
            --generate-notes \
            "dist/drydock-${V}.jar"
```

- [ ] **Step 2: Verify the YAML parses**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('ok')"`
Expected: `ok`

- [ ] **Step 3: Verify the version-bump sed commands against real copies**

The `prepare` job's two `sed` rewrites are the riskiest logic. Test them on throwaway copies without touching tracked files:

```bash
tmp=$(mktemp -d)
cp app/build.gradle.kts "$tmp/build.gradle.kts"
cp jbang-catalog.json "$tmp/jbang-catalog.json"
V=1.2.3
sed -i -E "s/^version = \".*\"/version = \"${V}\"/" "$tmp/build.gradle.kts"
sed -i -E "s|(\"script-ref\": \"io\.btraceio:drydock:)[^\"]*(\")|\1${V}\2|" "$tmp/jbang-catalog.json"
grep -n "^version = \"1.2.3\"" "$tmp/build.gradle.kts"
grep -n "io.btraceio:drydock:1.2.3" "$tmp/jbang-catalog.json"
rm -rf "$tmp"
```

Expected: both `grep`s print exactly one matching line; no error. (Confirms the anchors match the real files' current formatting.)

- [ ] **Step 4: Lint the workflow with actionlint (if available)**

Run: `command -v actionlint >/dev/null && actionlint .github/workflows/release.yml || echo "actionlint not installed - skipping (CI parse already verified in Step 2)"`
Expected: no errors, or the skip message.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "Add Maven Central release workflow (macOS staging, two triggers)"
```

---

### Task 3: Release documentation

**Files:**
- Create: `docs/RELEASING.md`
- Modify: `README.md` (add one pointer line)

**Interfaces:** none (documentation only).

- [ ] **Step 1: Write `docs/RELEASING.md`**

Create `docs/RELEASING.md`:

```markdown
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
```

- [ ] **Step 2: Add a pointer to README**

Add to `README.md` (under an existing "Building"/"Development" section, or as a new short section):

```markdown
## Releasing

See [docs/RELEASING.md](docs/RELEASING.md) for how releases are staged to Maven
Central and finalized.
```

- [ ] **Step 3: Verify links resolve**

Run: `test -f docs/RELEASING.md && grep -q "docs/RELEASING.md" README.md && echo ok`
Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add docs/RELEASING.md README.md
git commit -m "Document the Maven Central release process"
```

---

## Self-Review

**Spec coverage:**
- Gradle nexus-publish + Central Portal URLs + env creds → Task 1 Steps 1-2. ✓
- In-memory GPG signing + keep MavenLocal guard → Task 1 Step 3. ✓
- Version single-sourced across `app/build.gradle.kts` + `jbang-catalog.json` → Task 2 `prepare` job + Step 3 test. ✓
- One workflow, two triggers, setup resolves version/ref → Task 2 `setup`/`prepare` + `always()` gating. ✓
- macOS staging with Zig 0.15.2 + Metal Toolchain + submodules + dual JDK → Task 2 `stage-maven`. ✓
- Stage-only (no `closeAndRelease`) → `stage-maven` calls only `publish…ToSonatypeRepository`. ✓
- wait-for-maven polling repo1 → Task 2 `wait-for-maven`. ✓
- GitHub Release attaching `jbangJar` (built once, via artifact hand-off) → `stage-maven` upload + `create-github-release` download. ✓
- Required secrets documented → Task 3. ✓
- In-repo catalog update (no separate repo) → handled in `prepare`. ✓

**Placeholder scan:** No TBD/TODO; every code/YAML block is complete. ✓

**Type/name consistency:** Publication `drydock`; tasks `publishAllPublicationsToSonatypeRepository` / `signDrydockPublication`; artifact path `app/build.gradle.kts` version anchor `^version = "..."`; jar `app/build/libs/drydock-<version>.jar`; groupId path `io/btraceio/drydock`; env names `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`/`GPG_SIGNING_KEY`/`GPG_SIGNING_PWD` — all consistent across tasks and matching the spec and existing code. ✓

**Known risk (from spec):** Metal Toolchain on `macos-14` — handled defensively by the `xcodebuild -downloadComponent MetalToolchain` step; first real CI run surfaces it deterministically via the build script's clear failure.
