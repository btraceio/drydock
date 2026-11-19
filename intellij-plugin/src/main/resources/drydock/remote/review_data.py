"""Bounded, enumerated working-tree file reads for remote review (stdlib only, POSIX dir_fd).

Usage:
  review_data.py read    <root> <relpath> [<expect-sha256> [<expect-root-id>]]
  review_data.py excerpt <root> <relpath> <line> <context> [<expect-sha256> [<expect-root-id>]]

These are the ONLY operations; there is no arbitrary-path or command API. All arguments are
positional literals (never option-parsed), so a path such as "-x" or "--help" is just a name.
<root> is an absolute directory opened O_NOFOLLOW; <relpath> is walked one component at a time
descriptor-relative with O_NOFOLLOW (symlink, "..", ".", empty and case-insensitive ".git" components are refused)
and the leaf must be a regular file (opened O_NONBLOCK so a FIFO cannot hang us). Bytes are read
with limit+1 and never truncated. Responses are one JSON line:
  {"ok":true,"sha256":<hex of working bytes>,"size":n,"root_id":"dev:ino","binary":bool,
   "b64":<base64>} (excerpt adds "start","end"; b64 is then the returned UTF-8 window)
  {"ok":false,"code":...} with code in bad-arguments, unsafe-path, root-unavailable,
  root-replaced, unavailable, not-regular, too-large, binary, not-text, line-out-of-range,
  stale (hash mismatch or the file changed while it was read), read-failed.
"""
import base64
import hashlib
import json
import os
import stat
import sys

MAX_FILE, MAX_EXCERPT_FILE, MAX_WINDOW = 8 << 20, 4 << 20, 64 << 10
MAX_PATH, MAX_DEPTH, MAX_CONTEXT = 1024, 32, 20
CLOEXEC = getattr(os, "O_CLOEXEC", 0)
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | CLOEXEC
FILE_FLAGS = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | CLOEXEC


class Reject(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def split_path(rel):
    try:
        raw = rel.encode("utf-8")
    except UnicodeEncodeError:
        raise Reject("unsafe-path")
    parts = rel.split("/")
    if (not rel or len(raw) > MAX_PATH or len(parts) > MAX_DEPTH or "\0" in rel or "\\" in rel
            or any(p in ("", ".", "..") or p.lower() == ".git" for p in parts)):
        raise Reject("unsafe-path")
    return parts


def open_root(root, expect_root):
    if not os.path.isabs(root) or "\0" in root:
        raise Reject("bad-arguments")
    try:
        fd = os.open(root, DIR_FLAGS)
        st = os.fstat(fd)
    except OSError:
        raise Reject("root-unavailable")
    root_id = "%d:%d" % (st.st_dev, st.st_ino)
    if expect_root and expect_root != root_id:
        os.close(fd)
        raise Reject("root-replaced")
    return fd, root_id


def open_leaf(root_fd, parts):
    cur = os.dup(root_fd)
    try:
        for name in parts[:-1]:
            try:
                nxt = os.open(name, DIR_FLAGS, dir_fd=cur)
            except OSError:
                raise Reject("unavailable")
            os.close(cur)
            cur = nxt
        try:
            fd = os.open(parts[-1], FILE_FLAGS, dir_fd=cur)
        except OSError:
            raise Reject("unavailable")
    finally:
        os.close(cur)
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise Reject("not-regular")
    except BaseException:
        os.close(fd)
        raise
    return fd, st


def read_bounded(fd, st, limit):
    if st.st_size > limit:
        raise Reject("too-large")
    chunks, total = [], 0
    try:
        while total <= limit:
            chunk = os.read(fd, min(1 << 20, limit + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
    except OSError:
        raise Reject("read-failed")
    if total > limit:
        raise Reject("too-large")
    after = os.fstat(fd)
    if (after.st_size, after.st_mtime_ns, after.st_ino) != (st.st_size, st.st_mtime_ns, st.st_ino) \
            or total != st.st_size:
        raise Reject("stale")
    return b"".join(chunks)


def load(root, rel, limit, expect_sha, expect_root):
    parts = split_path(rel)
    root_fd, root_id = open_root(root, expect_root)
    try:
        fd, st = open_leaf(root_fd, parts)
    finally:
        os.close(root_fd)
    try:
        data = read_bounded(fd, st, limit)
    finally:
        os.close(fd)
    digest = hashlib.sha256(data).hexdigest()
    if expect_sha and expect_sha != digest:
        raise Reject("stale")
    return data, digest, root_id


def reply(data, digest, root_id, **extra):
    out = {"ok": True, "sha256": digest, "size": len(data), "root_id": root_id,
           "binary": b"\0" in data}
    out.update(extra)
    return out


def op_read(args):
    if len(args) not in (2, 3, 4):
        raise Reject("bad-arguments")
    data, digest, root_id = load(args[0], args[1], MAX_FILE, *pad(args[2:], 2))
    return reply(data, digest, root_id, b64=base64.b64encode(data).decode("ascii"))


def op_excerpt(args):
    if len(args) not in (4, 5, 6):
        raise Reject("bad-arguments")
    try:
        line, ctx = int(args[2]), int(args[3])
    except ValueError:
        raise Reject("bad-arguments")
    if not args[2].isdigit() or not args[3].isdigit() or line < 1 or not 0 <= ctx <= MAX_CONTEXT:
        raise Reject("bad-arguments")
    data, digest, root_id = load(args[0], args[1], MAX_EXCERPT_FILE, *pad(args[4:], 2))
    if b"\0" in data:
        raise Reject("binary")
    lines = data.split(b"\n")
    if data.endswith(b"\n"):
        lines.pop()
    if line > len(lines):
        raise Reject("line-out-of-range")
    start, end = max(1, line - ctx), min(len(lines), line + ctx)
    window = b"\n".join(lines[start - 1:end]) + (b"\n" if end < len(lines) or data.endswith(b"\n") else b"")
    if len(window) > MAX_WINDOW:
        raise Reject("too-large")
    try:
        window.decode("utf-8")
    except UnicodeDecodeError:
        raise Reject("not-text")
    return reply(data, digest, root_id, start=start, end=end,
                 b64=base64.b64encode(window).decode("ascii"))


def pad(values, n):
    return list(values) + [None] * (n - len(values))


OPS = {"read": op_read, "excerpt": op_excerpt}


def main(argv):
    try:
        op = OPS.get(argv[1]) if len(argv) > 1 else None
        if op is None:
            raise Reject("bad-arguments")
        result = op(argv[2:])
    except Reject as e:
        result = {"ok": False, "code": e.code}
    except Exception:
        result = {"ok": False, "code": "read-failed"}
    sys.stdout.write(json.dumps(result, separators=(",", ":")) + "\n")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
