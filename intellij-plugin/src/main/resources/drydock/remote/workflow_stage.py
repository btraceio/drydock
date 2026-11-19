"""Safe unpacker for Drydock workflow snapshots (stdlib only, POSIX dir_fd/no-follow).

Usage: workflow_stage.py <base> <namespace-rel> <pending-name> <archive-name> <final-name>

<base> is the trusted anchor ($HOME); each <namespace-rel> component below it is opened
descriptor-relative with O_NOFOLLOW and must be owned by us with mode 0700 (missing ones are
created 0700). The archive is <ns>/pending/<pending-name>/<archive-name>. It is NEVER passed
to extractall: a ZIP holds the regular-file bytes plus one manifest member listing every
entry (file/dir/link, sha256, size, executable, link target). The tree is built in a private
temp dir by exclusive no-follow creation with hashes verified, then published by one
descriptor-relative rename to <ns>/workflows/<final-name>. Any failure removes the temp tree
and the archive and prints {"ok":false,"code":...}; success prints {"ok":true,...}.
"""
import errno
import hashlib
import json
import os
import posixpath
import re
import stat
import sys
import zipfile

MANIFEST = ".drydock-manifest.json"
WIRE_CAP = 40 << 20
MAX_ENTRIES, MAX_FILE, MAX_TOTAL = 2048, 8 << 20, 32 << 20
MAX_DEPTH, MAX_PATH, MAX_MANIFEST = 32, 1024, 1 << 20
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)


class Reject(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def pairs(items):
    out = {}
    for k, v in items:
        if k in out:
            raise Reject("manifest-duplicate-member")
        out[k] = v
    return out


def check_private_dir(fd, code):
    st = os.fstat(fd)
    if not stat.S_ISDIR(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o700:
        raise Reject(code)


def open_child(parent, name, create, code="namespace-unsafe"):
    if create:
        try:
            os.mkdir(name, 0o700, dir_fd=parent)
        except FileExistsError:
            pass
    try:
        fd = os.open(name, DIR_FLAGS, dir_fd=parent)
    except OSError:
        raise Reject(code)
    try:
        check_private_dir(fd, code)
    except Reject:
        os.close(fd)
        raise
    return fd


def open_namespace(base, rel):
    if not os.path.isabs(base) or not rel or rel.startswith("/"):
        raise Reject("bad-arguments")
    parts = rel.split("/")
    if any(p in ("", ".", "..") or not NAME_RE.match(p) for p in parts):
        raise Reject("bad-arguments")
    try:
        fd = os.open(base, DIR_FLAGS)
        st = os.fstat(fd)
    except OSError:
        raise Reject("namespace-unsafe")
    if st.st_uid != os.getuid() or st.st_mode & 0o022:
        os.close(fd)
        raise Reject("namespace-unsafe")
    for p in parts:
        try:
            nxt = open_child(fd, p, True)
        finally:
            os.close(fd)
        fd = nxt
    return fd


def valid_path(p):
    if not p or len(p.encode("utf-8", "strict")) > MAX_PATH or p.startswith("/") or "\\" in p:
        raise Reject("bad-path")
    if any(ord(c) < 32 or ord(c) == 127 for c in p) or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", p):
        raise Reject("bad-path")
    parts = p.split("/")
    if len(parts) > MAX_DEPTH or any(c in ("", ".", "..", ".git") for c in parts) or p == MANIFEST:
        raise Reject("bad-path")
    return parts


def parse_manifest(raw):
    try:
        doc = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    except Reject:
        raise
    except (ValueError, RecursionError):
        raise Reject("manifest-malformed")
    if not isinstance(doc, dict) or set(doc) != {"version", "entries"} or doc["version"] != 1 \
            or not isinstance(doc["entries"], list) or len(doc["entries"]) > MAX_ENTRIES:
        raise Reject("manifest-malformed")
    entries = {}
    for e in doc["entries"]:
        if not isinstance(e, dict) or not isinstance(e.get("path"), str):
            raise Reject("manifest-malformed")
        kind = e.get("type")
        want = {"file": {"path", "type", "sha256", "size", "executable"}, "dir": {"path", "type"},
                "link": {"path", "type", "target"}}.get(kind)
        if want is None or set(e) != want:
            raise Reject("unknown-entry")
        valid_path(e["path"])
        if e["path"] in entries:
            raise Reject("duplicate-entry")
        if kind == "file":
            size = e["size"]
            if type(size) is not int or size < 0 or size > MAX_FILE or type(e["executable"]) is not bool \
                    or not isinstance(e["sha256"], str) or not re.match(r"^[0-9a-f]{64}$", e["sha256"]):
                raise Reject("manifest-malformed")
        if kind == "link" and not isinstance(e["target"], str):
            raise Reject("manifest-malformed")
        entries[e["path"]] = e
    if sum(e["size"] for e in entries.values() if e["type"] == "file") > MAX_TOTAL:
        raise Reject("too-large")
    return entries


def check_layout(entries):
    """Parents are explicit directories (so no entry sits below a link) and links stay contained."""
    dirs = {p for p, e in entries.items() if e["type"] == "dir"}
    prefixes = {p.rsplit("/", 1)[0] for p in entries if "/" in p}
    for p in entries:
        if "/" in p and p.rsplit("/", 1)[0] not in dirs:
            raise Reject("parent-not-directory")
    for p, e in entries.items():
        if e["type"] == "link":
            t = e["target"]
            if not t or t.startswith("/") or "\\" in t or any(ord(c) < 32 for c in t):
                raise Reject("link-escape")
            cur = posixpath.dirname(p).split("/") if "/" in p else []
            todo, hops = t.split("/")[::-1], 0
            while todo:
                c = todo.pop()
                if c in ("", "."):
                    continue
                if c == "..":
                    if not cur:
                        raise Reject("link-escape")
                    cur.pop()
                    continue
                cur.append(c)
                at = "/".join(cur)
                if at in entries and entries[at]["type"] == "link":
                    hops += 1
                    if hops > MAX_DEPTH:
                        raise Reject("link-depth")
                    cur.pop()
                    todo.extend(entries[at]["target"].split("/")[::-1])
            dest = "/".join(cur)
            if not dest or dest.split("/")[0] == ".git" or (dest not in entries and dest not in prefixes):
                raise Reject("link-escape")
            if dest in entries and entries[dest]["type"] == "link":
                raise Reject("link-escape")


def read_zip(archive_fd, entries_expected_parse):
    st = os.fstat(archive_fd)
    if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or st.st_mode & 0o077:
        raise Reject("archive-unsafe")
    if st.st_size > WIRE_CAP:
        raise Reject("archive-too-large")
    return os.fdopen(archive_fd, "rb", closefd=False)


def bounded_read(zf, info, limit, sink):
    """Stream a member through sink() reading at most limit+1 bytes: a lying header cannot bomb."""
    total = 0
    with zf.open(info) as src:
        while True:
            chunk = src.read(65536)
            if not chunk:
                return total
            total += len(chunk)
            if total > limit:
                raise Reject("too-large")
            sink(chunk)


def rm_tree_at(parent, name):
    try:
        fd = os.open(name, DIR_FLAGS, dir_fd=parent)
    except OSError:
        try:
            os.unlink(name, dir_fd=parent)
        except OSError:
            pass
        return
    try:
        for child in os.listdir(fd):
            if stat.S_ISDIR(os.lstat(child, dir_fd=fd).st_mode):
                rm_tree_at(fd, child)
            else:
                os.unlink(child, dir_fd=fd)
    finally:
        os.close(fd)
    os.rmdir(name, dir_fd=parent)


def build(zf, entries, tree_fd):
    members = {}
    infos = zf.infolist()
    if len(infos) > MAX_ENTRIES + 1:
        raise Reject("too-many-entries")
    for i in infos:
        if i.filename in members:
            raise Reject("duplicate-entry")
        members[i.filename] = i
        if i.flag_bits & 1 or i.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) \
                or i.filename.endswith("/") or (i.external_attr >> 16 and not stat.S_ISREG(i.external_attr >> 16)):
            raise Reject("special-entry")
    manifest_info = members.pop(MANIFEST, None)
    if manifest_info is None:
        raise Reject("manifest-missing")
    buf = bytearray()
    bounded_read(zf, manifest_info, MAX_MANIFEST, buf.extend)
    entries.update(parse_manifest(bytes(buf)))
    files = {p for p, e in entries.items() if e["type"] == "file"}
    if set(members) != files:
        raise Reject("unknown-entry")
    check_layout(entries)
    fds = {"": tree_fd}
    opened = []
    try:
        for p in sorted(entries, key=lambda x: (x.count("/"), x)):
            e, parent_path, name = entries[p], p.rsplit("/", 1)[0] if "/" in p else "", p.rsplit("/", 1)[-1]
            parent = fds[parent_path]
            if e["type"] == "dir":
                os.mkdir(name, 0o700, dir_fd=parent)
                fds[p] = os.open(name, DIR_FLAGS, dir_fd=parent)
                opened.append(fds[p])
            elif e["type"] == "link":
                os.symlink(e["target"], name, dir_fd=parent)
            else:
                fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
                try:
                    h = hashlib.sha256()
                    n = bounded_read(zf, members[p], e["size"],
                                     lambda c: (h.update(c), os.write(fd, c)))
                    if n != e["size"] or h.hexdigest() != e["sha256"]:
                        raise Reject("hash-mismatch")
                    os.fchmod(fd, 0o500 if e["executable"] else 0o400)
                    os.fsync(fd)
                finally:
                    os.close(fd)
    finally:
        for fd in opened:
            os.close(fd)


def stage(base, rel, pending, archive, final):
    for n in (pending, archive, final):
        if not NAME_RE.match(n):
            raise Reject("bad-arguments")
    ns = open_namespace(base, rel)
    tmp_name, pend = None, None
    try:
        pend_root = open_child(ns, "pending", False)
        try:
            pend = open_child(pend_root, pending, False, "pending-unsafe")
        finally:
            os.close(pend_root)
        try:
            afd = os.open(archive, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=pend)
        except OSError:
            raise Reject("archive-unsafe")
        try:
            zf_file = read_zip(afd, None)
            try:
                zf = zipfile.ZipFile(zf_file)
            except (zipfile.BadZipFile, OSError, ValueError, NotImplementedError):
                raise Reject("archive-malformed")
            tmp_name = "tree-" + os.urandom(8).hex()
            os.mkdir(tmp_name, 0o700, dir_fd=pend)
            tree = os.open(tmp_name, DIR_FLAGS, dir_fd=pend)
            entries = {}
            try:
                build(zf, entries, tree)
                os.fsync(tree)
            except (zipfile.BadZipFile, zlib_error, EOFError, NotImplementedError, RuntimeError):
                raise Reject("archive-malformed")
            finally:
                os.close(tree)
        finally:
            os.close(afd)
        wf = open_child(ns, "workflows", True)
        try:
            try:
                os.lstat(final, dir_fd=wf)
                raise Reject("already-published")
            except FileNotFoundError:
                pass
            try:
                os.rename(tmp_name, final, src_dir_fd=pend, dst_dir_fd=wf)
            except OSError as ex:
                if ex.errno in (errno.EEXIST, errno.ENOTEMPTY):
                    raise Reject("already-published")
                raise
            tmp_name = None
            os.fsync(wf)
        finally:
            os.close(wf)
        os.unlink(archive, dir_fd=pend)
        return {"ok": True, "entries": len(entries),
                "bytes": sum(e["size"] for e in entries.values() if e["type"] == "file")}
    except BaseException:
        if pend is not None:
            if tmp_name:
                rm_tree_at(pend, tmp_name)
            try:
                os.unlink(archive, dir_fd=pend)
            except OSError:
                pass
        raise
    finally:
        if pend is not None:
            os.close(pend)
        os.close(ns)


try:
    from zlib import error as zlib_error
except ImportError:  # pragma: no cover
    zlib_error = ValueError


def main(argv):
    if len(argv) != 6 or not (os.open in os.supports_dir_fd and os.rename in os.supports_dir_fd):
        print(json.dumps({"ok": False, "code": "unsupported-or-bad-arguments"}))
        return 2
    try:
        out = stage(*argv[1:])
    except Reject as r:
        print(json.dumps({"ok": False, "code": r.code}))
        return 1
    except OSError as ex:
        print(json.dumps({"ok": False, "code": "io-error", "errno": ex.errno}))
        return 1
    except Exception:  # fail closed with a fixed code, never a traceback carrying paths
        print(json.dumps({"ok": False, "code": "internal-error"}))
        return 1
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
