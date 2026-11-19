"""Newline-delimited JSON-RPC framing for the Drydock MCP stdio bridge (stdlib only).

One JSON object per line on stdin/stdout. Input lines are capped at 1 MiB, output
lines at 4 MiB, JSON nesting at 32. A malformed, oversize, batch, duplicate-member
or invalid-Unicode line is answered with an id-null (or its own valid id) error and is
NEVER handed to the dispatcher. Notifications (no "id" member) never get a response.
stdout carries protocol frames only; diagnostics are fixed strings on stderr and never
echo payload bytes. Run directly (`--serve`) it is a self-test echo server.
"""
import json
import sys

MAX_LINE_BYTES = 1 << 20
MAX_OUTPUT_BYTES = 4 << 20
MAX_DEPTH = 32

PARSE_ERROR, INVALID_REQUEST, INTERNAL_ERROR = -32700, -32600, -32603


class FrameError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class OutputTooLarge(Exception):
    pass


def diag(text):
    sys.stderr.write("drydock-bridge: " + text + "\n")
    sys.stderr.flush()


def read_line(stream):
    """Next line's bytes without the newline; None at EOF. Raises FrameError when the
    line is oversize (the rest of it is discarded) or truncated by EOF."""
    chunk = stream.readline(MAX_LINE_BYTES + 1)
    if not chunk:
        return None
    if chunk.endswith(b"\n"):
        if len(chunk) - 1 > MAX_LINE_BYTES:
            raise FrameError(INVALID_REQUEST, "line too large")
        return chunk[:-1]
    if len(chunk) > MAX_LINE_BYTES:
        while True:
            rest = stream.readline(MAX_LINE_BYTES)
            if not rest or rest.endswith(b"\n"):
                break
        raise FrameError(INVALID_REQUEST, "line too large")
    raise FrameError(PARSE_ERROR, "truncated line at end of input")


def _check_depth(text):
    depth = 0
    in_string = escaped = False
    for ch in text:
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch in "[{":
            depth += 1
            if depth > MAX_DEPTH:
                raise FrameError(INVALID_REQUEST, "nesting too deep")
        elif ch in "]}":
            depth -= 1


def _no_duplicates(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise FrameError(INVALID_REQUEST, "duplicate member")
        obj[key] = value
    return obj


def _reject_constant(_name):
    raise FrameError(PARSE_ERROR, "non-finite number")


def _check_unicode(value):
    if isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeEncodeError:
            raise FrameError(PARSE_ERROR, "invalid Unicode") from None
    elif isinstance(value, list):
        for item in value:
            _check_unicode(item)
    elif isinstance(value, dict):
        for key, item in value.items():
            _check_unicode(key)
            _check_unicode(item)


def _valid_id(value):
    return isinstance(value, str) or (isinstance(value, int) and not isinstance(value, bool))


def parse_message(raw):
    """Decode one line into a JSON-RPC request/notification dict, or raise FrameError."""
    if raw.endswith(b"\r"):
        raw = raw[:-1]
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise FrameError(PARSE_ERROR, "invalid UTF-8") from None
    _check_depth(text)
    try:
        msg = json.loads(text, object_pairs_hook=_no_duplicates, parse_constant=_reject_constant)
    except FrameError:
        raise
    except (ValueError, RecursionError):
        raise FrameError(PARSE_ERROR, "malformed JSON") from None
    _check_unicode(msg)
    if isinstance(msg, list):
        raise FrameError(INVALID_REQUEST, "batches are not supported")
    if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0" or not isinstance(msg.get("method"), str):
        raise FrameError(INVALID_REQUEST, "not a JSON-RPC request")
    if "id" in msg and not _valid_id(msg["id"]):
        raise FrameError(INVALID_REQUEST, "invalid id")
    if "params" in msg and not isinstance(msg["params"], (dict, list)):
        raise FrameError(INVALID_REQUEST, "invalid params")
    return msg


def write_message(stream, message):
    """One frame to the binary stream; ASCII-only JSON so no raw newline/binary can leak."""
    data = json.dumps(message, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    if len(data) > MAX_OUTPUT_BYTES:
        raise OutputTooLarge()
    stream.write(data + b"\n")
    stream.flush()


def error_response(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def serve(dispatch, stdin, stdout):
    """Read frames until EOF; dispatch(msg) returns a result object (requests only)."""
    while True:
        try:
            raw = read_line(stdin)
            if raw is None:
                return
            msg = parse_message(raw)
        except FrameError as e:
            diag("rejected input frame (%d)" % e.code)
            write_message(stdout, error_response(None, e.code, str(e)))
            continue
        is_request = "id" in msg
        try:
            result = dispatch(msg)
            if is_request:
                write_message(stdout, {"jsonrpc": "2.0", "id": msg["id"], "result": result})
        except OutputTooLarge:
            diag("response exceeds output cap")
            if is_request:
                write_message(stdout, error_response(msg["id"], INTERNAL_ERROR, "response too large"))
        except Exception:
            diag("dispatch failed")
            if is_request:
                write_message(stdout, error_response(msg["id"], INTERNAL_ERROR, "internal error"))


def _echo(msg):
    params = msg.get("params")
    if msg["method"] == "test/big" and isinstance(params, dict):
        return "x" * int(params.get("n", 0))
    return {"method": msg["method"], "params": params}


if __name__ == "__main__":
    if sys.argv[1:] != ["--serve"]:
        diag("usage: bridge_framing.py --serve")
        sys.exit(2)
    serve(_echo, sys.stdin.buffer, sys.stdout.buffer)
