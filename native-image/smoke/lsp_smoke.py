#!/usr/bin/env python3
"""LSP smoke test / oracle for java-language-server (Phase 0 baseline).

Spawns a server (JVM jar today, native binary later), drives a full LSP
session over stdio, and asserts the observable contract:

  initialize -> capabilities
  didOpen    -> publishDiagnostics (deliberate type error present)
  definition -> cross-file Location in Greeter.java
  references -> Location back in Main.java
  completion -> member `greet` offered after `g.`

Usage:
  lsp_smoke.py <fixture_dir> -- <server command ...>

Exit code 0 = all checks passed. Same script validates the native image.
"""
import sys
import os
import json
import time
import queue
import threading
import subprocess
import pathlib


def as_uri(p: pathlib.Path) -> str:
    return p.resolve().as_uri()


class Server:
    def __init__(self, cmd, cwd, errlog):
        self.proc = subprocess.Popen(
            cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errlog
        )
        self.q: "queue.Queue" = queue.Queue()
        self.buf = []
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def _read_loop(self):
        f = self.proc.stdout
        try:
            while True:
                headers = {}
                line = f.readline()
                if not line:
                    self.q.put(None)
                    return
                while line not in (b"\r\n", b"\n"):
                    if not line:
                        self.q.put(None)
                        return
                    if b":" in line:
                        k, v = line.split(b":", 1)
                        headers[k.strip().lower()] = v.strip()
                    line = f.readline()
                n = headers.get(b"content-length")
                if n is None:
                    continue
                body = f.read(int(n))
                try:
                    self.q.put(json.loads(body))
                except Exception:
                    pass
        except Exception:
            self.q.put(None)

    def send(self, obj):
        data = json.dumps(obj).encode("utf-8")
        self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
        self.proc.stdin.flush()

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, rid, method, params):
        self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})

    def match(self, want, timeout):
        for i, m in enumerate(self.buf):
            if want(m):
                return self.buf.pop(i)
        end = time.time() + timeout
        while True:
            remaining = end - time.time()
            if remaining <= 0:
                return None
            try:
                m = self.q.get(timeout=remaining)
            except queue.Empty:
                return None
            if m is None:
                raise RuntimeError("server closed stdout unexpectedly")
            if want(m):
                return m
            self.buf.append(m)

    def response(self, rid, timeout=30):
        return self.match(
            lambda m: m.get("id") == rid and ("result" in m or "error" in m), timeout
        )

    def notification(self, method, pred=lambda m: True, timeout=60):
        return self.match(
            lambda m: m.get("method") == method and pred(m), timeout
        )


PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {name}" + (f" :: {detail}" if detail else ""), flush=True)


def find_pos(lines, needle, sub):
    for i, line in enumerate(lines):
        c = line.find(needle)
        if c >= 0:
            return i, c + needle.find(sub)
    raise RuntimeError(f"needle {needle!r} not found in fixture")


def main():
    if "--" not in sys.argv:
        print("usage: lsp_smoke.py <fixture_dir> -- <server cmd...>", file=sys.stderr)
        return 2
    split = sys.argv.index("--")
    fixture = pathlib.Path(sys.argv[1]).resolve()
    cmd = sys.argv[split + 1:]

    main_java = fixture / "Main.java"
    greeter_java = fixture / "Greeter.java"
    main_text = main_java.read_text()
    lines = main_text.splitlines()
    # `g.greet(` -> definition/references target and completion anchor
    def_line, greet_col = find_pos(lines, "g.greet(", "greet")
    _, dot_col = find_pos(lines, "g.greet(", ".")
    compl_pos = {"line": def_line, "character": dot_col + 1}  # right after the dot
    ident_pos = {"line": def_line, "character": greet_col + 1}  # inside `greet`

    errlog = open("/tmp/jls-smoke-stderr.log", "wb")
    srv = Server(cmd, cwd=str(fixture), errlog=errlog)
    try:
        # 1. initialize
        srv.request(1, "initialize", {
            "processId": os.getpid(),
            "rootUri": as_uri(fixture),
            "capabilities": {},
        })
        resp = srv.response(1, timeout=30)
        caps = (resp or {}).get("result", {}).get("capabilities", {})
        check("initialize returns definitionProvider",
              bool(caps.get("definitionProvider")), json.dumps(sorted(caps.keys())))

        srv.notify("initialized", {})

        # 2. didOpen both files (open Main last so it is lastEdited -> linted)
        srv.notify("textDocument/didOpen", {"textDocument": {
            "uri": as_uri(greeter_java), "languageId": "java", "version": 1,
            "text": greeter_java.read_text()}})
        srv.notify("textDocument/didOpen", {"textDocument": {
            "uri": as_uri(main_java), "languageId": "java", "version": 1,
            "text": main_text}})

        # 3. diagnostics for Main.java (non-empty; the deliberate type error)
        main_uri = as_uri(main_java)
        diag = srv.notification(
            "textDocument/publishDiagnostics",
            lambda m: m.get("params", {}).get("uri") == main_uri
            and len(m["params"].get("diagnostics", [])) > 0,
            timeout=90)
        diags = (diag or {}).get("params", {}).get("diagnostics", [])
        msgs = "; ".join(d.get("message", "") for d in diags)
        check("didOpen -> non-empty diagnostics on Main.java", len(diags) > 0, msgs[:200])

        # 4. definition of greet() -> Greeter.java
        srv.request(2, "textDocument/definition", {
            "textDocument": {"uri": main_uri}, "position": ident_pos})
        r = srv.response(2, timeout=30)
        locs = (r or {}).get("result") or []
        if isinstance(locs, dict):
            locs = [locs]
        def_ok = any(str(l.get("uri", "")).endswith("Greeter.java") for l in locs)
        check("definition of greet() resolves to Greeter.java", def_ok,
              json.dumps([l.get("uri") for l in locs]))

        # 5. references of greet() -> at least the call in Main.java
        srv.request(3, "textDocument/references", {
            "textDocument": {"uri": main_uri}, "position": ident_pos,
            "context": {"includeDeclaration": True}})
        r = srv.response(3, timeout=30)
        refs = (r or {}).get("result") or []
        ref_ok = any(str(l.get("uri", "")).endswith("Main.java") for l in refs)
        check("references of greet() include Main.java call", ref_ok,
              f"{len(refs)} refs")

        # 6. completion after `g.` -> offers member `greet`
        srv.request(4, "textDocument/completion", {
            "textDocument": {"uri": main_uri}, "position": compl_pos})
        r = srv.response(4, timeout=30)
        res = (r or {}).get("result") or {}
        items = res.get("items", res if isinstance(res, list) else [])
        labels = [it.get("label", "") for it in items]
        check("completion after g. offers greet",
              any(l == "greet" or l.startswith("greet(") for l in labels),
              f"{len(labels)} items")

        # 7. shutdown / exit
        srv.request(5, "shutdown", None)
        srv.response(5, timeout=10)
        srv.notify("exit", None)
        try:
            srv.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            srv.proc.kill()
    finally:
        if srv.proc.poll() is None:
            srv.proc.kill()
        errlog.close()

    print(f"\n{len(PASS)} passed, {len(FAIL)} failed"
          + (f"  (failed: {FAIL})" if FAIL else ""))
    if FAIL:
        print("--- server stderr tail ---", file=sys.stderr)
        try:
            tail = pathlib.Path("/tmp/jls-smoke-stderr.log").read_text(errors="replace")
            sys.stderr.write("\n".join(tail.splitlines()[-40:]) + "\n")
        except Exception:
            pass
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
