"""Bounded, read-only Git capture for remote review (stdlib only).

Usage:
  review_git.py capture <root> <range> <context> <worktree:0|1>

The ONLY operation. <range> is one revision/range argument handed to `git diff` after
--end-of-options (never option-parsed, never a shell); <context> is 0-99 unified context lines;
<worktree> is 1 when the range compares the working tree, which also reports staged paths and
includes untracked files. Untracked inclusion mirrors the local DiffService: a private COPY of the
real index (timestamps preserved) selected with GIT_INDEX_FILE gets `git add -N -- .`, and the diff
runs against that copy; the copy is removed on every exit. The real index, refs and checkout are
never written (GIT_OPTIONAL_LOCKS=0), except that Git may store one unreachable empty blob.
One JSON line is printed:
  {"ok":true,"head":<sha|null>,"branch":<name|null>,"detached":bool,"index_sha256":<hex|null>,
   "refs_sha256":<hex>,"status_b64","staged_b64","untracked_b64" (NUL-separated raw paths),
   "untracked_omitted":bool,"diff_b64"}
  {"ok":false,"code":...} with code in bad-arguments, root-unavailable, git-failed, timeout,
  too-large, stale (HEAD, refs or the real index changed during the capture).
Bounds: 1 MiB per status/path/ref command, 16 MiB diff, 64 MiB per snapshot, 10 s per command.
"""
import base64
import hashlib
import json
import os
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time

MAX_SMALL, MAX_DIFF, MAX_SNAPSHOT = 1 << 20, 16 << 20, 64 << 20
COMMAND_SECONDS, MAX_UNTRACKED, MAX_RANGE = 10, 2000, 512


class Reject(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def run(root, args, limit, env=None, ok=(0,)):
    """Runs git with a deadline and a hard output cap; never truncates silently."""
    full = dict(os.environ, GIT_OPTIONAL_LOCKS="0", LC_ALL="C", GIT_TERMINAL_PROMPT="0")
    full.update(env or {})
    try:
        proc = subprocess.Popen(["git", "-C", root] + args, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, env=full)
    except OSError:
        raise Reject("git-failed")
    deadline, chunks, total = time.monotonic() + COMMAND_SECONDS, [], 0
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise Reject("timeout")
            if not select.select([proc.stdout], [], [], left)[0]:
                continue
            chunk = os.read(proc.stdout.fileno(), 1 << 16)
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > limit:
                raise Reject("too-large")
        proc.wait(timeout=max(0.1, deadline - time.monotonic()))
    except subprocess.TimeoutExpired:
        raise Reject("timeout")
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()
        proc.stdout.close()
    if proc.returncode not in ok:
        raise Reject("git-failed")
    return b"".join(chunks)


def file_sha(path):
    try:
        with open(path, "rb") as f:
            return hashlib.file_digest(f, "sha256").hexdigest()
    except FileNotFoundError:
        return None
    except OSError:
        raise Reject("git-failed")


def identity(root, index_path):
    head = run(root, ["rev-parse", "--verify", "-q", "HEAD"], MAX_SMALL, ok=(0, 1)).decode().strip() or None
    refs = run(root, ["for-each-ref", "--format=%(refname) %(objectname)"], MAX_SMALL)
    return {"head": head, "refs_sha256": hashlib.sha256(refs).hexdigest(),
            "index_sha256": file_sha(index_path)}


def diff_with_untracked(root, diff_cmd, real_index, scratch):
    temp_index = os.path.join(scratch, "index")
    if os.path.exists(real_index):
        shutil.copy2(real_index, temp_index)  # keeps mtime: racily-clean entries stay trusted
    env = {"GIT_INDEX_FILE": temp_index}
    run(root, ["add", "-N", "--", "."], MAX_SMALL, env)
    return run(root, diff_cmd, MAX_DIFF, env)


def capture(args):
    if len(args) != 4 or args[3] not in ("0", "1") or not args[2].isdigit() or len(args[2]) > 2:
        raise Reject("bad-arguments")
    root, rng, context, worktree = args[0], args[1], args[2], args[3] == "1"
    if (not os.path.isabs(root) or "\0" in root or not rng or rng.startswith("-") or "\0" in rng
            or len(rng.encode("utf-8", "surrogateescape")) > MAX_RANGE):
        raise Reject("bad-arguments")
    if not os.path.isdir(root):
        raise Reject("root-unavailable")
    git_dir = run(root, ["rev-parse", "--absolute-git-dir"], MAX_SMALL).decode().strip()
    real_index = os.path.join(git_dir, "index")
    before = identity(root, real_index)
    branch_out = run(root, ["symbolic-ref", "-q", "--short", "HEAD"], MAX_SMALL, ok=(0, 1)).decode().strip()
    status = run(root, ["status", "--porcelain=v1", "-z", "--untracked-files=all"], MAX_SMALL)
    staged = untracked = b""
    omitted = False
    if worktree:
        staged = run(root, ["diff", "--cached", "--name-only", "-z"], MAX_SMALL)
        untracked = run(root, ["ls-files", "--others", "--exclude-standard", "-z"], MAX_SMALL)
        omitted = untracked.count(b"\0") > MAX_UNTRACKED
    diff_cmd = ["diff", "--no-color", "--no-ext-diff", "-U" + context, "--end-of-options", rng]
    scratch = None
    try:
        if worktree and untracked and not omitted:
            scratch = tempfile.mkdtemp(prefix="drydock-review-git-")
            diff = diff_with_untracked(root, diff_cmd, real_index, scratch)
        else:
            diff = run(root, diff_cmd, MAX_DIFF)
    finally:
        if scratch:
            shutil.rmtree(scratch, ignore_errors=True)
    if identity(root, real_index) != before:
        raise Reject("stale")
    if len(status) + len(staged) + len(untracked) + len(diff) > MAX_SNAPSHOT:
        raise Reject("too-large")
    enc = lambda b: base64.b64encode(b).decode("ascii")
    out = {"ok": True, "branch": branch_out or None, "detached": not branch_out}
    out.update(before)
    out.update(status_b64=enc(status), staged_b64=enc(staged), untracked_b64=enc(untracked),
               untracked_omitted=omitted, diff_b64=enc(diff))
    return out


def main(argv):
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))  # run the finally-cleanup
    try:
        if len(argv) < 2 or argv[1] != "capture":
            raise Reject("bad-arguments")
        result = capture(argv[2:])
    except Reject as e:
        result = {"ok": False, "code": e.code}
    except Exception:
        result = {"ok": False, "code": "git-failed"}
    sys.stdout.write(json.dumps(result, separators=(",", ":")) + "\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
