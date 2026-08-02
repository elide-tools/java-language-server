#!/usr/bin/env python3
"""Coverage-maximizing LSP exercise for the native-image tracing agent (Phase 1).

Unlike lsp_smoke.py (which asserts a small contract), this drives every LSP
request the server dispatches, so the tracing agent records reflection / resource
/ proxy / serialization metadata for all providers. It is deliberately
failure-tolerant: the point is to *execute* code paths, then exit cleanly so the
agent flushes its config-merge-dir.

Usage:
  agent_exercise.py <fixture_dir> -- <server command ...>
"""
import sys
import os
import json
import time
import pathlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lsp_smoke import Server, as_uri, find_pos  # noqa: E402


def step(name, fn):
    try:
        out = fn()
        print(f"[ran ] {name}" + (f" :: {out}" if out else ""), flush=True)
    except Exception as e:
        print(f"[warn] {name} :: {type(e).__name__}: {e}", flush=True)


def main():
    split = sys.argv.index("--")
    fixture = pathlib.Path(sys.argv[1]).resolve()
    cmd = sys.argv[split + 1:]

    main_java = fixture / "Main.java"
    greeter_java = fixture / "Greeter.java"
    main_text = main_java.read_text()
    lines = main_text.splitlines()
    def_line, greet_col = find_pos(lines, "g.greet(", "greet")
    _, dot_col = find_pos(lines, "g.greet(", ".")
    _, paren_col = find_pos(lines, "g.greet(", "(")
    ident = {"line": def_line, "character": greet_col + 1}
    after_dot = {"line": def_line, "character": dot_col + 1}
    in_args = {"line": def_line, "character": paren_col + 1}
    main_uri = as_uri(main_java)
    greeter_uri = as_uri(greeter_java)

    errlog = open("/tmp/jls-agent-stderr.log", "wb")
    srv = Server(cmd, cwd=str(fixture), errlog=errlog)
    rid = [10]

    def req(method, params, timeout=20):
        rid[0] += 1
        i = rid[0]
        srv.request(i, method, params)
        r = srv.response(i, timeout=timeout)
        if r is None:
            return "no response"
        if "error" in r:
            return f"error: {r['error'].get('message')}"
        res = r.get("result")
        return f"result: {json.dumps(res)[:120]}"

    try:
        step("initialize", lambda: req("initialize", {
            "processId": os.getpid(), "rootUri": as_uri(fixture), "capabilities": {}}))
        srv.notify("initialized", {})
        step("didOpen Greeter", lambda: srv.notify("textDocument/didOpen", {
            "textDocument": {"uri": greeter_uri, "languageId": "java", "version": 1,
                             "text": greeter_java.read_text()}}) or "sent")
        step("didOpen Main", lambda: srv.notify("textDocument/didOpen", {
            "textDocument": {"uri": main_uri, "languageId": "java", "version": 1,
                             "text": main_text}}) or "sent")

        # diagnostics (drives ErrorProvider + ColorProvider + markup)
        diag_range = None
        d = srv.notification(
            "textDocument/publishDiagnostics",
            lambda m: m.get("params", {}).get("uri") == main_uri
            and len(m["params"].get("diagnostics", [])) > 0, timeout=90)
        if d:
            ds = d["params"]["diagnostics"]
            print(f"[ran ] diagnostics :: {len(ds)} -> {ds[0].get('message','')[:80]}", flush=True)
            diag_range = ds[0].get("range")

        step("hover", lambda: req("textDocument/hover", {
            "textDocument": {"uri": main_uri}, "position": ident}))
        step("signatureHelp", lambda: req("textDocument/signatureHelp", {
            "textDocument": {"uri": main_uri}, "position": in_args}))
        step("definition", lambda: req("textDocument/definition", {
            "textDocument": {"uri": main_uri}, "position": ident}))
        step("references", lambda: req("textDocument/references", {
            "textDocument": {"uri": main_uri}, "position": ident,
            "context": {"includeDeclaration": True}}))
        step("documentSymbol Main", lambda: req("textDocument/documentSymbol", {
            "textDocument": {"uri": main_uri}}))
        step("documentSymbol Greeter", lambda: req("textDocument/documentSymbol", {
            "textDocument": {"uri": greeter_uri}}))
        step("workspaceSymbol", lambda: req("workspace/symbol", {"query": "Greet"}))
        step("foldingRange", lambda: req("textDocument/foldingRange", {
            "textDocument": {"uri": main_uri}}))
        step("documentLink", lambda: req("textDocument/documentLink", {
            "textDocument": {"uri": main_uri}}))

        # completion + resolve (drives Docs / markdown / member indexing)
        rid[0] += 1
        ci = rid[0]
        srv.request(ci, "textDocument/completion", {
            "textDocument": {"uri": main_uri}, "position": after_dot})
        cr = srv.response(ci, timeout=20)
        first_item = None
        if cr and isinstance(cr.get("result"), dict):
            items = cr["result"].get("items", [])
            print(f"[ran ] completion :: {len(items)} items", flush=True)
            if items:
                first_item = items[0]
        if first_item is not None:
            step("completionItem/resolve",
                 lambda: req("completionItem/resolve", first_item))

        # codeLens + resolve
        rid[0] += 1
        cl = rid[0]
        srv.request(cl, "textDocument/codeLens", {"textDocument": {"uri": main_uri}})
        clr = srv.response(cl, timeout=20)
        lenses = (clr or {}).get("result") or []
        print(f"[ran ] codeLens :: {len(lenses)} lenses", flush=True)
        if lenses:
            step("codeLens/resolve", lambda: req("codeLens/resolve", lenses[0]))

        # code actions: cursor-based and diagnostic-based
        full_range = {"start": {"line": def_line, "character": 0},
                      "end": {"line": def_line, "character": max(1, len(lines[def_line]))}}
        step("codeAction (cursor)", lambda: req("textDocument/codeAction", {
            "textDocument": {"uri": main_uri}, "range": full_range,
            "context": {"diagnostics": []}}))
        if diag_range:
            step("codeAction (diagnostic)", lambda: req("textDocument/codeAction", {
                "textDocument": {"uri": main_uri}, "range": diag_range,
                "context": {"diagnostics": [{"range": diag_range,
                                             "message": "incompatible types",
                                             "severity": 1}]}}))

        # rename family
        step("prepareRename", lambda: req("textDocument/prepareRename", {
            "textDocument": {"uri": main_uri}, "position": ident}))
        step("rename", lambda: req("textDocument/rename", {
            "textDocument": {"uri": main_uri}, "position": ident,
            "newName": "greetz"}))

        # formatting
        step("formatting", lambda: req("textDocument/formatting", {
            "textDocument": {"uri": main_uri},
            "options": {"tabSize": 4, "insertSpaces": True}}))

        # extra hover/signature probes at multiple positions (widen Hover /
        # SignatureHelp / SignatureInformation reflection coverage)
        greeter_lines = greeter_java.read_text().splitlines()
        def pos_in(lines_, needle, sub):
            try:
                return find_pos(lines_, needle, sub)
            except Exception:
                return None
        hovers = []
        p = pos_in(lines, "Greeter g", "Greeter")
        if p: hovers.append((main_uri, {"line": p[0], "character": p[1] + 1}))
        p = pos_in(lines, "System.out.println", "println")
        if p: hovers.append((main_uri, {"line": p[0], "character": p[1] + 1}))
        p = pos_in(greeter_lines, "greet(String", "greet")
        if p: hovers.append((greeter_uri, {"line": p[0], "character": p[1] + 1}))
        for i, (u, pp) in enumerate(hovers):
            step(f"hover#{i}", lambda u=u, pp=pp: req("textDocument/hover", {
                "textDocument": {"uri": u}, "position": pp}))
        p = pos_in(lines, "System.out.println(", "(")
        if p:
            step("signatureHelp#println", lambda p=p: req("textDocument/signatureHelp", {
                "textDocument": {"uri": main_uri},
                "position": {"line": p[0], "character": p[1] + 1}}))

        # didChange (full replace) then re-lint, then didSave
        changed = main_text.replace("int bad = \"notAnInt\";",
                                    "int good = 42;\n        String more = g.greet(\"again\");")
        step("didChange", lambda: srv.notify("textDocument/didChange", {
            "textDocument": {"uri": main_uri, "version": 2},
            "contentChanges": [{"text": changed}]}) or "sent")
        srv.notification("textDocument/publishDiagnostics",
                         lambda m: m.get("params", {}).get("uri") == main_uri, timeout=30)
        step("didSave", lambda: srv.notify("textDocument/didSave", {
            "textDocument": {"uri": main_uri}}) or "sent")
        srv.notification("textDocument/publishDiagnostics",
                         lambda m: m.get("params", {}).get("uri") == main_uri, timeout=30)

        # let any trailing async work / colors flush
        time.sleep(1.0)

        step("shutdown", lambda: req("shutdown", None, timeout=10))
        srv.notify("exit", None)
        try:
            srv.proc.wait(timeout=15)
        except Exception:
            srv.proc.kill()
    finally:
        if srv.proc.poll() is None:
            srv.proc.kill()
        errlog.close()
    print("exercise complete", flush=True)


if __name__ == "__main__":
    sys.exit(main())
