# Agent Identity

An agent session in a configured repository can commit, push, and open pull
requests under a **non-human identity**: commits carry the bot's author and
committer, pushes authenticate with the bot's credential, and `gh` calls run
with the bot's token. The identity source is pluggable — a per-repository
credential command you write — so nothing in Drydock knows or cares who
issues the credential.

The feature is **opt-in per repository and off by default**: a repository
without stored settings behaves exactly as before.

## Configuring

Open the Drydock tool window's repository settings (the same dialog that
holds the Jira, GitHub, and workspace-box settings) and enable **Agent
Identity** for the repository, then enter the credential command: a single
shell command, executed with `/bin/sh -c` in the launching session's working
directory.

Settings are stored under `drydock.agentIdentity.<repositoryId>.*` in the
IDE's application properties. The command is stored verbatim; it is not a
secret, and nothing runs at save time.

## The credential command contract

The command must print **`KEY=VALUE` lines on stdout**. Drydock re-runs it at
every session launch and resume, and the session's `gh` wrapper (see below)
re-runs it on every `gh` invocation, so it **must be safe to re-run**: mint a
fresh token on every call instead of relying on one call ever happening.

Any deviation is a hard, loud launch failure — never a silent fallback to
your own identity:

- a non-zero exit or a timeout (30 s) fails the launch;
- every non-blank stdout line must be `KEY=VALUE`, with a valid environment
  variable name (`[A-Za-z_][A-Za-z0-9_]*`), a non-empty value, and no
  newlines inside values;
- output with no `KEY=VALUE` lines at all fails the launch;
- stderr is ignored on success (pass through for humans) and included in the
  failure message otherwise.

Keys Drydock understands — all optional; mint only what your setup needs:

| Key | Effect |
| --- | --- |
| `GIT_AUTHOR_NAME`, `GIT_AUTHOR_EMAIL` | Commit author attribution (git environment variables). |
| `GIT_COMMITTER_NAME`, `GIT_COMMITTER_EMAIL` | Committer attribution. |
| `GIT_CREDENTIAL_HELPER_SPEC` | A git [credential-helper spec](https://git-scm.com/docs/gitcredentials) the session's `git push` uses to authenticate. |
| `GH_TOKEN` | A token for `gh`. The session's `gh` wrapper re-mints it per invocation; this value is what tools that read the environment directly see. |

Unknown keys are kept and exported to the session environment, so your glue
can ship extra variables for its own helpers.

## What Drydock injects at launch

When the launching session's repository has agent identity enabled, Drydock:

1. **Runs the credential command** and validates its output.
2. **Writes a session env file** (`<state>/identity/env/<sessionId>.env`,
   owner-only 0600 inside an owner-only directory) carrying the minted keys,
   the credential command itself (for the wrapper to re-invoke), and the
   session-scoped git config (below).
3. **Prefixes the launch command** with
   `set -ea; . <env>; rm -f <env>; exec <agent command>` — the shell sources
   the environment and deletes the file within the same instant, then `exec`
   replaces the shell with the agent. The env file's life is milliseconds and
   nothing reads it after that moment; only a launch killed in between can
   leave one, and the next IDE start sweeps the directory.
4. **Installs the `gh` wrapper** (`<state>/identity/bin/gh`, owner-only
   executable) and puts its directory **first on the session's PATH**, ahead
   of your profile and any other shims.

### Session-scoped git config

The env file carries `GIT_CONFIG_COUNT` / `GIT_CONFIG_KEY_n` /
`GIT_CONFIG_VALUE_n` entries, which git applies at command scope — after the
system and global config files, and only for processes in this session.
Nothing is ever written to your global git config or keychain.

When the command mints `GIT_CREDENTIAL_HELPER_SPEC`, Drydock injects:

- `credential.helper=` (an **empty value resets the helper list**) — this is
  load-bearing: without it, a globally configured helper (for example one
  that routes `github.com` through your own `gh`) would run first and supply
  *your* stored credential;
- `credential.helper=<your spec>`;
- `url.<https-origin>.insteadOf=<ssh-origin>` when the repository's origin is
  a `github.com` SSH URL — installation-style tokens usually authenticate
  HTTPS only, and this rewrites push/fetch URLs for this session without
  touching your SSH setup.

A credential helper paired with an origin that cannot be rewritten to HTTPS
(an SSH URL to another host, or a malformed github.com one) is a hard launch
failure: the push would silently go out over SSH under your SSH identity.

### The `gh` wrapper

The wrapper is a generic script: it re-invokes the session's credential
command, takes the fresh `GH_TOKEN` from its output, and execs the next `gh`
on PATH (yours, including any routing shims) with that token exported. It
fails loudly — exit 97, message on stderr — when the credential command is
absent, fails, or prints no `GH_TOKEN`, and it refuses to recurse when no
other `gh` exists. Every `gh` call therefore mints a fresh token, so a
short-lived token never strands a long session.

## Example skeleton

A neutral skeleton for the credential command; fill in the minting step with
whatever issues your repository's credentials:

```sh
#!/bin/sh
# Prints KEY=VALUE lines on stdout; anything else fails the session launch.
# Must be safe to re-run: called at every session launch and once per gh call.

# 1. Mint a short-lived credential for this repository (your mechanism here).
#    Keep it short-lived: a fresh one is minted on every call, so there is
#    nothing to cache and nothing long-lived to leak.

# 2. Print the identity.
echo "GIT_AUTHOR_NAME=My Project Bot"
echo "GIT_AUTHOR_EMAIL=bot@example.com"
echo "GIT_COMMITTER_NAME=My Project Bot"
echo "GIT_COMMITTER_EMAIL=bot@example.com"
echo "GIT_CREDENTIAL_HELPER_SPEC=!/absolute/path/to/git-credential-helper"
echo "GH_TOKEN=<the minted token>"
```

The git credential helper the spec above points at has its own contract: git
pipes the query in and reads the credential from stdout.

```sh
#!/bin/sh
# A git credential helper: reads the query on stdin, prints the credential.
echo "username=my-project-bot"
echo "password=<the minted token>"
```

## Failure modes

| Situation | Result |
| --- | --- |
| Command fails, times out, prints malformed output, or prints nothing | The launch fails with the stderr excerpt; nothing opens. |
| Helper spec + an origin that cannot be rewritten to HTTPS | The launch fails with the offending origin in the message. |
| The env file cannot be written | The launch fails. |
| Launch fails after preparation | The env file is deleted; the next launch mints fresh. |
| Launch shell is fish, or Windows | No injection; the session launches under your own identity behind a **warning balloon**. |

## Scope and limitations

- A **plain-terminal session** (an interactive shell with no agent) never
  carries identity: there is no `-c` command to prefix and no agent to
  attribute.
- Sessions on **workspace boxes** and SSH remotes run on another host and do
  not carry the injection.
- Drydock's **own** `gh`/`git` calls (PR status, checkouts) run outside
  agent sessions and use your identity as before; the identity pipeline
  covers what the agent does inside its session.
- The `gh` wrapper wraps only `gh`. Other tools that talk to the forge read
  `GH_TOKEN` from the environment (minted at launch) or use the git
  credential helper; your glue decides what else to mint.

## Verification

The parsing, validation, command runner, git-config construction, env-file
permissions, launch prefix, and wrapper behavior are covered by the plugin's
test suite (`AgentIdentity*Test`, `GhWrapperScriptTest`, which runs the real
shipped wrapper against fake credential commands and a fake `gh`). The
credential-helper reset and the `insteadOf` rewrite are verified against real
git (`GIT_TRACE=1` shows the transport using the rewritten HTTPS URL, and
`git credential fill` resolves the session helper ahead of a globally
configured one). End-to-end use against a live forge depends on your own
credential glue and is not exercised by the automated suite.
