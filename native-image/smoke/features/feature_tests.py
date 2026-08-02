#!/usr/bin/env python3
"""Fork-and-grow acceptance tests for java-language-server.

One test per jdtls-parity feature we intend to add on the javac substrate. Each
test drives a real LSP request against a running server (JVM jar or native
binary) and asserts the observable contract. Tests are tagged:

  baseline  - already implemented; MUST pass now (guards regressions)
  target    - not implemented yet; expected RED until the grow-step lands

Run:
  feature_tests.py <fixture_dir> -- <server command ...>
e.g. against the native image:
  feature_tests.py fixture -- bash ../run-native-server.sh

Exit code is 0 unless a *baseline* test fails (targets never fail the run; they
report TODO). As each feature ships, its target flips to baseline here and the
same assertion is mirrored into the Elide test suite.
"""
import sys
import os
import json
import pathlib

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lsp_smoke import Server, as_uri, find_pos  # noqa: E402


class Session:
    def __init__(self, cmd, fixture):
        self.errlog = open("/tmp/jls-features-stderr.log", "wb")
        self.srv = Server(cmd, cwd=str(fixture), errlog=self.errlog)
        self.fixture = fixture
        self.rid = 0
        self.files = {}
        for p in sorted(fixture.glob("*.java")):
            self.files[p.name] = p.read_text()

    def uri(self, name):
        return as_uri(self.fixture / name)

    def pos(self, name, needle, sub):
        line, col = find_pos(self.files[name].splitlines(), needle, sub)
        return {"line": line, "character": col}

    def start(self):
        self.req("initialize", {"processId": os.getpid(),
                                "rootUri": as_uri(self.fixture), "capabilities": {}})
        self.srv.notify("initialized", {})
        for name, text in self.files.items():
            self.srv.notify("textDocument/didOpen", {"textDocument": {
                "uri": self.uri(name), "languageId": "java", "version": 1, "text": text}})

    def req(self, method, params, timeout=15):
        self.rid += 1
        i = self.rid
        self.srv.request(i, method, params)
        r = self.srv.response(i, timeout=timeout)
        if r is None:
            raise TimeoutError("no response (method likely unimplemented)")
        if "error" in r:
            raise RuntimeError(f"error: {r['error'].get('message')}")
        return r.get("result")

    def stop(self):
        try:
            self.req("shutdown", None, timeout=5)
            self.srv.notify("exit", None)
            self.srv.proc.wait(timeout=10)
        except Exception:
            pass
        finally:
            if self.srv.proc.poll() is None:
                self.srv.proc.kill()
            self.errlog.close()


def uris_of(result):
    if result is None:
        return []
    if isinstance(result, dict):
        result = [result]
    out = []
    for x in result:
        u = x.get("uri") or x.get("targetUri") or (x.get("location") or {}).get("uri")
        if u:
            out.append(str(u))
    return out


def full_range(s, name):
    lines = s.files[name].splitlines()
    last = max(0, len(lines) - 1)
    return {"start": {"line": 0, "character": 0},
            "end": {"line": last, "character": len(lines[last]) if lines else 0}}


# ---- feature tests: each returns (ok: bool, detail: str) ----

def t_definition(s):  # baseline sanity
    r = s.req("textDocument/definition", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "position": s.pos("Geometry.java", "s.area()", "area")})
    us = uris_of(r)
    return any(u.endswith("Shape.java") for u in us), f"{us}"


def t_references(s):  # baseline sanity
    r = s.req("textDocument/references", {
        "textDocument": {"uri": s.uri("Shape.java")},
        "position": s.pos("Shape.java", "double area();", "area"),
        "context": {"includeDeclaration": False}})
    us = uris_of(r)
    return any(u.endswith("Geometry.java") for u in us), f"{len(us)} refs {us}"


def t_implementation(s):
    r = s.req("textDocument/implementation", {
        "textDocument": {"uri": s.uri("Shape.java")},
        "position": s.pos("Shape.java", "double area();", "area")}, timeout=4)
    us = uris_of(r)
    ok = any(u.endswith("Circle.java") for u in us) and any(u.endswith("Square.java") for u in us)
    return ok, f"{us}"


def t_type_definition(s):
    r = s.req("textDocument/typeDefinition", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "position": s.pos("Geometry.java", "s.area()", "s")}, timeout=4)
    us = uris_of(r)
    return any(u.endswith("Shape.java") for u in us), f"{us}"


def t_declaration(s):
    r = s.req("textDocument/declaration", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "position": s.pos("Geometry.java", "describe(shape)", "describe")}, timeout=4)
    us = uris_of(r)
    return any(u.endswith("Geometry.java") for u in us), f"{us}"


def t_document_highlight(s):
    r = s.req("textDocument/documentHighlight", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "position": s.pos("Geometry.java", "sum = 0.0", "sum")}, timeout=4)
    n = len(r or [])
    return n >= 3, f"{n} highlights"


def t_selection_range(s):
    r = s.req("textDocument/selectionRange", {
        "textDocument": {"uri": s.uri("Circle.java")},
        "positions": [s.pos("Circle.java", "* radius *", "radius")]}, timeout=4)
    ok = bool(r) and isinstance(r, list) and r[0].get("parent") is not None
    return ok, "has parent chain" if ok else f"{r}"


def t_semantic_tokens(s):
    r = s.req("textDocument/semanticTokens/full", {
        "textDocument": {"uri": s.uri("Geometry.java")}}, timeout=4)
    data = (r or {}).get("data") if isinstance(r, dict) else None
    ok = bool(data) and len(data) % 5 == 0
    return ok, f"{len(data) if data else 0} ints"


def t_inlay_hint(s):
    r = s.req("textDocument/inlayHint", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "range": full_range(s, "Geometry.java")}, timeout=4)
    n = len(r or [])
    return n >= 1, f"{n} hints"


def t_call_hierarchy(s):
    items = s.req("textDocument/prepareCallHierarchy", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "position": s.pos("Geometry.java", "double describe(Shape s)", "describe")}, timeout=4)
    if not items:
        return False, "no prepare item"
    incoming = s.req("callHierarchy/incomingCalls", {"item": items[0]}, timeout=4)
    callers = [(c.get("from") or {}).get("name") for c in (incoming or [])]
    return any(c == "total" for c in callers), f"incoming from {callers}"


def t_type_hierarchy(s):
    items = s.req("textDocument/prepareTypeHierarchy", {
        "textDocument": {"uri": s.uri("Circle.java")},
        "position": s.pos("Circle.java", "class Circle", "Circle")}, timeout=4)
    if not items:
        return False, "no prepare item"
    supers = s.req("typeHierarchy/supertypes", {"item": items[0]}, timeout=4)
    names = [x.get("name") for x in (supers or [])]
    return any(n == "Shape" for n in names), f"supertypes {names}"


def t_codeaction_generate(s):
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "range": full_range(s, "Geometry.java"),
        "context": {"diagnostics": [], "only": ["source"]}}, timeout=6)
    titles = [a.get("title", "") for a in (r or [])]
    want = ("getter", "tostring", "constructor", "hashcode")
    ok = any(any(w in t.lower() for w in want) for t in titles)
    return ok, f"{titles}"


def t_organize_imports(s):
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "range": full_range(s, "Geometry.java"),
        "context": {"diagnostics": [], "only": ["source.organizeImports"]}}, timeout=6)
    titles = [a.get("title", "").lower() for a in (r or [])]
    return any("import" in t for t in titles), f"{titles}"


def t_formatting(s):
    r = s.req("textDocument/formatting", {
        "textDocument": {"uri": s.uri("Messy.java")},
        "options": {"tabSize": 2, "insertSpaces": True}}, timeout=6)
    edits = r or []
    # google-java-format-quality output should introduce real edits (not empty)
    nonempty = [e for e in edits if e.get("newText", "") not in ("",)]
    return len(nonempty) >= 1, f"{len(edits)} edits"


def t_workspace_diagnostics(s):
    # pull-model workspace diagnostics
    r = s.req("workspace/diagnostic", {"previousResultIds": []}, timeout=4)
    items = (r or {}).get("items") if isinstance(r, dict) else None
    return bool(items), f"{len(items) if items else 0} reports"


TESTS = [
    # name, phase, kind, fn
    ("definition (baseline)", "-", "baseline", t_definition),
    ("references (baseline)", "-", "baseline", t_references),
    ("implementation", "A", "target", t_implementation),
    ("typeDefinition", "A", "target", t_type_definition),
    ("declaration", "A", "target", t_declaration),
    ("documentHighlight", "A", "target", t_document_highlight),
    ("selectionRange", "A", "target", t_selection_range),
    ("formatting (google-java-format)", "A", "target", t_formatting),
    ("semanticTokens/full", "B", "target", t_semantic_tokens),
    ("inlayHint", "B", "target", t_inlay_hint),
    ("callHierarchy", "B", "target", t_call_hierarchy),
    ("typeHierarchy", "B", "target", t_type_hierarchy),
    ("codeAction: generate members", "B", "target", t_codeaction_generate),
    ("codeAction: organizeImports", "B", "target", t_organize_imports),
    ("workspace diagnostics", "C", "target", t_workspace_diagnostics),
]


def main():
    if "--" not in sys.argv:
        print("usage: feature_tests.py <fixture_dir> -- <server cmd...>", file=sys.stderr)
        return 2
    split = sys.argv.index("--")
    fixture = pathlib.Path(sys.argv[1]).resolve()
    cmd = sys.argv[split + 1:]

    baseline_fail = 0
    target_pass = 0
    target_total = 0
    for name, phase, kind, fn in TESTS:
        s = Session(cmd, fixture)
        try:
            s.start()
            ok, detail = fn(s)
        except Exception as e:
            ok, detail = False, f"{type(e).__name__}: {e}"
        finally:
            s.stop()
        if kind == "baseline":
            mark = "PASS" if ok else "FAIL"
            if not ok:
                baseline_fail += 1
        else:
            target_total += 1
            if ok:
                target_pass += 1
            mark = "DONE" if ok else "TODO"
        tag = "" if phase == "-" else f"[{phase}] "
        print(f"[{mark}] {tag}{name} :: {detail}"[:200], flush=True)

    print(f"\nbaseline: {'OK' if baseline_fail == 0 else str(baseline_fail)+' FAILED'}"
          f" | targets implemented: {target_pass}/{target_total}")
    if baseline_fail:
        print("--- server stderr tail ---", file=sys.stderr)
        try:
            tail = pathlib.Path("/tmp/jls-features-stderr.log").read_text(errors="replace")
            sys.stderr.write("\n".join(tail.splitlines()[-30:]) + "\n")
        except Exception:
            pass
    return 1 if baseline_fail else 0


if __name__ == "__main__":
    sys.exit(main())
