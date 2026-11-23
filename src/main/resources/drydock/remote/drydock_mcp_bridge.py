"""Drydock MCP stdio-to-HTTP bridge (stdlib only).

Usage: drydock_mcp_bridge.py <manifest-path> [--timeouts <connect-s> <read-s>]

argv carries only the manifest path (and optional non-secret timeouts that can only shorten
the 5/35 s defaults). Newline JSON-RPC framing comes from the sibling bridge_framing.py.
Before EVERY input message the private manifest is reopened and revalidated (no-follow,
regular, ours, mode 0600, one link, <= 64 KiB, strict schema, 0700 parent, no generation
rollback, stable launch identity); the port and token are read anew and never cached.
After each response the manifest is reread: if (epoch, generation, token, port) changed or
the manifest is gone/invalid the response is discarded as outcome-unknown (-32002). When the
generation differs from the last initialized one, a non-initialize message is preceded by an
internal initialize (id drydock-bridge-init-N, launch-nonce headers) consumed internally.
One HTTP POST per message; a timeout/disconnect/HTTP error is NEVER retried, reads
included. Tokens appear only in the HTTP header: never argv, environment, logs or errors.
"""
import http.client
import json
import os
import stat
import sys

sys.dont_write_bytecode = True  # never litter the staged directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bridge_framing as fr  # noqa: E402

MAX_MANIFEST = 64 << 10
MAX_RESPONSE = fr.MAX_OUTPUT_BYTES
CONNECT_S, READ_S = 5.0, 35.0
UNAVAILABLE, OUTCOME_UNKNOWN = -32001, -32002
STR_KEYS = ("installationId", "managedSessionId", "agentSessionId", "launchNonce", "epoch", "token")
INT_KEYS = ("version", "generation", "bridgeProtocol", "port")


class Unavailable(Exception):
    pass


class Unknown(Exception):
    pass


def read_manifest(path):
    parent = os.path.dirname(os.path.abspath(path))
    try:
        pfd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            pst = os.fstat(pfd)
        finally:
            os.close(pfd)
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        raise Unavailable("manifest unreadable")
    try:
        st = os.fstat(fd)
        if pst.st_uid != os.getuid() or stat.S_IMODE(pst.st_mode) != 0o700:
            raise Unavailable("manifest namespace unsafe")
        if (not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o600
                or st.st_nlink != 1 or st.st_size > MAX_MANIFEST):
            raise Unavailable("manifest unsafe")
        raw = os.read(fd, MAX_MANIFEST + 1)
    except OSError:
        raise Unavailable("manifest unreadable")
    finally:
        os.close(fd)
    try:
        fr._check_depth(raw.decode("utf-8"))
        doc = json.loads(raw.decode("utf-8"), object_pairs_hook=fr._no_duplicates,
                         parse_constant=fr._reject_constant)
    except (fr.FrameError, ValueError, RecursionError):
        raise Unavailable("manifest invalid")
    if len(raw) > MAX_MANIFEST or not isinstance(doc, dict) or set(doc) != set(STR_KEYS + INT_KEYS):
        raise Unavailable("manifest invalid")
    if not all(isinstance(doc[k], str) and doc[k] for k in STR_KEYS):
        raise Unavailable("manifest invalid")
    if not all(type(doc[k]) is int for k in INT_KEYS):
        raise Unavailable("manifest invalid")
    if doc["version"] != 1 or doc["bridgeProtocol"] < 1 or not 0 < doc["port"] < 65536 or doc["generation"] < 0:
        raise Unavailable("manifest invalid")
    return doc


class Bridge:
    def __init__(self, path, connect_s=CONNECT_S, read_s=READ_S):
        self.path, self.connect_s, self.read_s = path, connect_s, read_s
        self.identity = None      # (installationId, managedSessionId, agentSessionId, launchNonce)
        self.last = None          # (epoch, generation) most recently accepted
        self.inited = None        # (epoch, generation) last initialized upstream
        self.init_params = {"protocolVersion": "2025-03-26", "capabilities": {},
                            "clientInfo": {"name": "drydock-bridge", "version": "1"}}
        self.internal_seq = 0

    def current(self):
        doc = read_manifest(self.path)
        ident = tuple(doc[k] for k in ("installationId", "managedSessionId", "agentSessionId", "launchNonce"))
        if self.identity is not None and ident != self.identity:
            raise Unavailable("launch identity changed")
        if self.last is not None and doc["epoch"] == self.last[0] and doc["generation"] < self.last[1]:
            raise Unavailable("generation rolled back")
        self.identity, self.last = ident, (doc["epoch"], doc["generation"])
        return doc

    def post(self, doc, payload):
        """One POST; returns (status, body). Never retried. Raises Unknown on any ambiguity."""
        body = json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode("ascii")
        headers = {"Content-Type": "application/json", "Accept": "application/json",
                   "X-Drydock-Session-Token": doc["token"], "X-Drydock-Launch-Nonce": doc["launchNonce"],
                   "X-Drydock-Bridge-Pid": str(os.getpid()), "X-Drydock-Bridge-Protocol": str(doc["bridgeProtocol"])}
        conn = http.client.HTTPConnection("127.0.0.1", doc["port"], timeout=self.connect_s)
        try:
            conn.connect()
            conn.sock.settimeout(self.read_s)
            conn.request("POST", "/mcp", body, headers)
            resp = conn.getresponse()
            data = resp.read(MAX_RESPONSE + 1)
            return resp.status, data
        except (OSError, http.client.HTTPException):
            raise Unknown("transport failure") from None
        finally:
            conn.close()

    @staticmethod
    def stamp(doc):
        return (doc["epoch"], doc["generation"], doc["token"], doc["port"])

    def parse(self, data, rid):
        if len(data) > MAX_RESPONSE:
            raise Unavailable("response too large")
        try:
            text = data.decode("utf-8")
            fr._check_depth(text)
            out = json.loads(text, object_pairs_hook=fr._no_duplicates, parse_constant=fr._reject_constant)
        except (fr.FrameError, ValueError, RecursionError):
            raise Unavailable("malformed upstream response") from None
        ok = (isinstance(out, dict) and out.get("jsonrpc") == "2.0" and type(out.get("id")) is type(rid)
              and out["id"] == rid and ("result" in out) != ("error" in out))
        if not ok:
            raise Unavailable("invalid upstream response")
        return out

    def reinitialize(self, doc):
        """Internal initialize for a new generation; the response never reaches the caller."""
        self.internal_seq += 1
        rid = "drydock-bridge-init-%d" % self.internal_seq
        try:
            status, data = self.post(doc, {"jsonrpc": "2.0", "id": rid, "method": "initialize",
                                           "params": self.init_params})
        except Unknown:
            raise Unavailable("reconnect initialize failed") from None
        if status != 200 or "result" not in self.parse(data, rid):
            raise Unavailable("reconnect initialize rejected")
        self.inited = (doc["epoch"], doc["generation"])

    def forward(self, msg):
        """Returns the response object for a request, or None (notification / nothing to emit)."""
        doc = self.current()
        method = msg.get("method")
        if method != "initialize" and self.inited != (doc["epoch"], doc["generation"]):
            self.reinitialize(doc)
        status, data = self.post(doc, msg)
        try:
            superseded = self.stamp(read_manifest(self.path)) != self.stamp(doc)
        except Unavailable:
            superseded = True
        if superseded:
            raise Unknown("generation superseded")
        is_request = "id" in msg
        if status in (202, 204):
            if is_request:
                raise Unavailable("empty response to a request")
            return None
        if status != 200:
            raise Unavailable("upstream rejected (%d)" % status)
        if not is_request:
            return None
        out = self.parse(data, msg["id"])
        if method == "initialize" and "result" in out:
            self.inited = (doc["epoch"], doc["generation"])
            if isinstance(msg.get("params"), dict):
                self.init_params = msg["params"]
        return out


def run(bridge, stdin, stdout):
    while True:
        try:
            raw = fr.read_line(stdin)
            if raw is None:
                return
            msg = fr.parse_message(raw)
        except fr.FrameError as e:
            fr.diag("rejected input frame (%d)" % e.code)
            fr.write_message(stdout, fr.error_response(None, e.code, str(e)))
            continue
        rid = msg.get("id")
        try:
            out = bridge.forward(msg)
        except Unknown:
            fr.diag("request outcome unknown, not retried")
            out = fr.error_response(rid, OUTCOME_UNKNOWN, "outcome unknown; request not retried")
        except Unavailable as e:
            fr.diag("request not completed")
            out = fr.error_response(rid, UNAVAILABLE, "drydock connection unavailable: %s" % e)
        except Exception:
            fr.diag("bridge failure")
            out = fr.error_response(rid, fr.INTERNAL_ERROR, "internal error")
        if "id" not in msg:
            continue
        try:
            fr.write_message(stdout, out)
        except fr.OutputTooLarge:
            fr.write_message(stdout, fr.error_response(rid, fr.INTERNAL_ERROR, "response too large"))


def main(argv):
    args = argv[1:]
    connect_s, read_s = CONNECT_S, READ_S
    if len(args) == 4 and args[1] == "--timeouts":
        try:
            connect_s, read_s = min(CONNECT_S, float(args[2])), min(READ_S, float(args[3]))
        except ValueError:
            args = []
    elif len(args) != 1:
        args = []
    if not args or min(connect_s, read_s) <= 0:
        fr.diag("usage: drydock_mcp_bridge.py <manifest> [--timeouts <connect> <read>]")
        return 2
    run(Bridge(args[0], connect_s, read_s), sys.stdin.buffer, sys.stdout.buffer)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
