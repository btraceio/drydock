"""Publishes a Drydock connection manifest (stdlib only, POSIX dir_fd/no-follow).

Usage: namespace_manifest.py <home> <installation-id> <session-id> <pending-name> <generation>

argv carries only paths/ids and the owning generation, never a credential. <pending-name> is a
file directly inside <ns>/pending, where <ns> is <home>/.local/share/drydock/<installation-id>/
sessions/<session-id>. Every namespace component is opened descriptor-relative with O_NOFOLLOW;
drydock and below must be owned by us with mode 0700 (missing ones are created 0700). The
pending file is opened no-follow, must be ours, regular, mode 0600 and <= 64 KiB, and must hold
a schema-valid manifest whose generation equals <generation> and exceeds any published one.
It is written to an exclusive no-follow 0600 temp file, fsynced, and atomically renamed over
<ns>/connection.json under an advisory lock. The pending file is removed on every outcome.
Prints {"ok":true,...} or {"ok":false,"code":...} (exit 1); never echoes manifest content.
"""
import fcntl
import json
import os
import re
import secrets
import stat
import sys

MAX_MANIFEST = 64 << 10
FINAL = "connection.json"
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
STR_KEYS = ("installationId", "managedSessionId", "agentSessionId", "launchNonce", "epoch", "token")
INT_KEYS = ("version", "generation", "bridgeProtocol", "port")


class Reject(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def pairs(items):
    out = {}
    for k, v in items:
        if k in out:
            raise Reject("schema-invalid")
        out[k] = v
    return out


def open_dir(parent, name, strict, create):
    if create:
        try:
            os.mkdir(name, 0o700, dir_fd=parent)
        except FileExistsError:
            pass
        except OSError:
            raise Reject("namespace-unsafe")
    try:
        fd = os.open(name, DIR_FLAGS, dir_fd=parent)
    except OSError:
        raise Reject("namespace-unsafe")
    st = os.fstat(fd)
    bad = st.st_uid != os.getuid() or (stat.S_IMODE(st.st_mode) != 0o700 if strict else st.st_mode & 0o022)
    if bad:
        os.close(fd)
        raise Reject("namespace-unsafe")
    return fd


def open_namespace(home, inst, sid):
    if not os.path.isabs(home) or not NAME_RE.match(inst) or not NAME_RE.match(sid):
        raise Reject("bad-arguments")
    try:
        fd = os.open(home, DIR_FLAGS)
    except OSError:
        raise Reject("namespace-unsafe")
    st = os.fstat(fd)
    if st.st_uid != os.getuid() or st.st_mode & 0o022:
        os.close(fd)
        raise Reject("namespace-unsafe")
    for name, strict in ((".local", False), ("share", False), ("drydock", True), (inst, True),
                         ("sessions", True), (sid, True)):
        try:
            nxt = open_dir(fd, name, strict, True)
        finally:
            os.close(fd)
        fd = nxt
    return fd


def read_bounded(fd):
    data = b""
    while len(data) <= MAX_MANIFEST:
        chunk = os.read(fd, MAX_MANIFEST + 1 - len(data))
        if not chunk:
            return data
        data += chunk
    raise Reject("manifest-too-large")


def parse(raw, inst, sid):
    try:
        doc = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    except Reject:
        raise
    except (ValueError, RecursionError):
        raise Reject("schema-invalid")
    if not isinstance(doc, dict) or set(doc) != set(STR_KEYS + INT_KEYS):
        raise Reject("schema-invalid")
    for k in STR_KEYS:
        if not isinstance(doc[k], str) or not doc[k]:
            raise Reject("schema-invalid")
    for k in INT_KEYS:
        if type(doc[k]) is not int:
            raise Reject("schema-invalid")
    if doc["version"] != 1 or doc["bridgeProtocol"] < 1 or not 0 < doc["port"] < 65536 or doc["generation"] < 0:
        raise Reject("schema-invalid")
    if doc["installationId"] != inst or doc["managedSessionId"] != sid:
        raise Reject("schema-invalid")
    return doc


def published_generation(ns, inst, sid):
    try:
        fd = os.open(FINAL, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=ns)
    except OSError:
        return -1
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():
            return -1
        return parse(read_bounded(fd), inst, sid)["generation"]
    except Reject:
        return -1
    finally:
        os.close(fd)


def discard(parent, name):
    for op in (os.unlink, os.rmdir):
        try:
            op(name, dir_fd=parent)
            return
        except OSError:
            pass


def publish(ns, pend, name, inst, sid, generation):
    try:
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=pend)
    except OSError:
        raise Reject("pending-unreadable")
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():
            raise Reject("pending-unsafe")
        if stat.S_IMODE(st.st_mode) != 0o600 or st.st_nlink != 1:
            raise Reject("pending-unsafe")
        if st.st_size > MAX_MANIFEST:
            raise Reject("manifest-too-large")
        raw = read_bounded(fd)
    finally:
        os.close(fd)
    doc = parse(raw, inst, sid)
    if doc["generation"] != generation:
        raise Reject("stale-generation")
    fcntl.flock(ns, fcntl.LOCK_EX)
    if doc["generation"] <= published_generation(ns, inst, sid):
        raise Reject("stale-generation")
    tmp = ".%s.%s.tmp" % (FINAL, secrets.token_hex(8))
    tfd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=ns)
    try:
        try:
            view = memoryview(raw)
            while view:
                view = view[os.write(tfd, view):]
            os.fsync(tfd)
        finally:
            os.close(tfd)
        os.rename(tmp, FINAL, src_dir_fd=ns, dst_dir_fd=ns)
    except BaseException:
        discard(ns, tmp)
        raise
    os.fsync(ns)
    return doc["generation"]


def main(argv):
    if len(argv) != 6 or not NAME_RE.match(argv[4]) or not re.match(r"^[0-9]{1,15}$", argv[5]):
        return {"ok": False, "code": "bad-arguments"}
    home, inst, sid, name, generation = argv[1], argv[2], argv[3], argv[4], int(argv[5])
    ns = pend = None
    try:
        ns = open_namespace(home, inst, sid)
        pend = open_dir(ns, "pending", True, True)
        try:
            return {"ok": True, "generation": publish(ns, pend, name, inst, sid, generation)}
        finally:
            discard(pend, name)
    except Reject as e:
        return {"ok": False, "code": e.code}
    except OSError as e:
        return {"ok": False, "code": "io-error", "errno": e.errno}
    finally:
        for fd in (pend, ns):
            if fd is not None:
                os.close(fd)


if __name__ == "__main__":
    out = main(sys.argv)
    print(json.dumps(out))
    sys.exit(0 if out["ok"] else 1)
