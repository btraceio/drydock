"""Launch wrapper: writes a private launch receipt, then execs the exact agent command.

Usage: launch_wrapper.py <receipt-path> <nonce> <conversation-id> -- <command> [args...]

The receipt (JSON, mode 0600, created exclusively and published by rename) holds the launch
nonce, the intended conversation id, and the identity of THIS process -- which exec keeps:
pid, Linux boot id and process start ticks. An enrollment probe later compares it with the
live process. An empty/blank conversation id, a missing /proc identity or an unwritable
receipt refuses before exec (exit 2) so no agent runs without evidence. The command is
exec'd verbatim: nothing is added to its argv or environment, and nothing is installed or
edited (no SSH config, no files besides the receipt). Stdlib only.
"""
import json
import os
import re
import sys

NONCE_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


def refuse(msg):
    sys.stderr.write("launch_wrapper: " + msg + "\n")
    sys.exit(2)


def start_ticks(proc):
    with open(os.path.join(proc, "self", "stat"), "r") as f:
        data = f.read()
    # comm may contain spaces/parens; fields resume after the LAST ')'. starttime is field 22.
    return int(data[data.rindex(")") + 2:].split()[19])


def main(argv):
    if len(argv) < 6 or argv[4] != "--":
        refuse("usage: <receipt-path> <nonce> <conversation-id> -- <command> [args...]")
    receipt, nonce, conv, cmd = argv[1], argv[2], argv[3], argv[5:]
    if not conv.strip():
        refuse("empty conversation id")
    if not NONCE_RE.match(nonce):
        refuse("invalid nonce")
    proc = os.environ.get("DRYDOCK_PROC_ROOT", "/proc")
    try:
        with open(os.path.join(proc, "sys/kernel/random/boot_id"), "r") as f:
            boot_id = f.read().strip()
        ticks = start_ticks(proc)
    except (OSError, ValueError, IndexError):
        refuse("process identity unavailable")
    body = json.dumps({"nonce": nonce, "conversationId": conv, "pid": os.getpid(),
                       "bootId": boot_id, "startTicks": ticks}).encode()
    tmp = "%s.tmp%d" % (receipt, os.getpid())
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            os.write(fd, body)
            os.fsync(fd)
        finally:
            os.close(fd)
        os.rename(tmp, receipt)
    except OSError as e:
        refuse("cannot write receipt: " + e.strerror)
    os.execvp(cmd[0], cmd)


if __name__ == "__main__":
    main(sys.argv)
