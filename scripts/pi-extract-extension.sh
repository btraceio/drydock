#!/bin/sh
# Extracts PiExtensionSource.SOURCE to a .ts file. Prints the path it wrote.
set -eu
OUT="${1:-/tmp/drydock-mcp.ts}"
python3 - "$OUT" <<'EXTRACT'
import sys, pathlib, textwrap
out = sys.argv[1]
src = "app/src/main/java/app/drydock/agent/providers/pi/internal/PiExtensionSource.java"
body = pathlib.Path(src).read_text().split('"""')[1]
body = body.split("\n", 1)[1]
pathlib.Path(out).write_text(textwrap.dedent(body).replace("\\\\", "\\"))
EXTRACT
echo "$OUT"
