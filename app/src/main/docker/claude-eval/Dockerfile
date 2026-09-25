# The container image used for Claude eval sessions (see
# ClaudeEvalContainer). Runs Claude Code's native Linux build inside a
# container so a seeded local settings file can carry the eval-specific
# x-target-account header, which the host's managed settings (locked to
# the highest precedence scope) forbid us from adding.
#
# Build on demand:  ./gradlew claudeEvalImage
# (uses whatever `docker` context is active -- Colima on macOS, a local or
# remote daemon on Linux).

FROM debian:bookworm-slim

# git: Claude's own Bash tool calls `git`; the worktree's .git points back
#       into the main repo object store, both mounted from the host.
# curl + ca-certificates: the native installer downloads over HTTPS.
# python3: plugin MCP servers and hooks run Python (e.g. sphinx's server,
#          which needs >= 3.11; bookworm ships 3.11).
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git python3 \
    && rm -rf /var/lib/apt/lists/*

# uv: plugin launchers (e.g. sphinx-mcp.sh) use `uv run` to install their
# pinned Python deps on first start. Installs to ~/.local/bin (on PATH below).
RUN curl -LsSf https://astral.sh/uv/install.sh | sh

# Claude Code native build. The install script places the launcher at
# ~/.local/bin/claude and the versioned binary under ~/.local/share/claude.
# Runs as root in this image, so HOME=/root.
RUN curl -fsSL https://claude.ai/install.sh | bash

ENV PATH="/root/.local/bin:${PATH}"

# The inner `claude ...` command arrives as an entrypoint script mounted from
# the host (ClaudeEvalContainer.wrap writes it into the config-dir mount).
# This image's ENTRYPOINT is `sh`, so `docker run <img> <script>` becomes
# `sh <script>` -- the launcher MUST NOT prepend an extra `sh`, or it would
# be `sh sh <script>` and fail with "cannot open sh".
ENTRYPOINT ["sh"]
