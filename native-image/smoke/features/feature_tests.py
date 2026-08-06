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


def diagnostics(s, name):
    """Published diagnostics for a fixture file. didSave re-lints all active documents."""
    uri = s.uri(name)
    s.srv.notify("textDocument/didSave", {"textDocument": {"uri": uri}})
    m = s.srv.notification(
        "textDocument/publishDiagnostics",
        pred=lambda msg: (msg.get("params") or {}).get("uri") == uri
        and len(((msg.get("params") or {}).get("diagnostics") or [])) > 0,
        timeout=60)
    return ((m or {}).get("params") or {}).get("diagnostics") or []


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
    actions = r or []
    titles = [a.get("title", "") for a in actions]
    want = ("getter", "tostring", "constructor", "hashcode")
    picked = next((a for a in actions if any(w in a.get("title", "").lower() for w in want)), None)
    if picked is None:
        return False, f"{titles}"
    # lazy listing carries no edit; codeAction/resolve computes it from the echoed `data`
    if picked.get("edit"):
        return False, f"edit present before resolve: {titles}"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    ok = any(v for v in changes.values())
    return ok, f"{titles} resolved={ok}"


def t_organize_imports(s):
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "range": full_range(s, "Geometry.java"),
        "context": {"diagnostics": [], "only": ["source.organizeImports"]}}, timeout=6)
    titles = [a.get("title", "").lower() for a in (r or [])]
    return any("import" in t for t in titles), f"{titles}"


def t_add_overrides(s):
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("Circle.java")},
        "range": full_range(s, "Circle.java"),
        "context": {"diagnostics": [], "only": ["source"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "override" in a.get("title", "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    inserted = any("@Override" in e.get("newText", "") for edits in changes.values() for e in edits)
    return inserted, f"inserted={inserted}"


def t_extract_variable(s):
    start = s.pos("Geometry.java", "s.area()", "s.area()")
    end = {"line": start["line"], "character": start["character"] + len("s.area()")}
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "range": {"start": start, "end": end},
        "context": {"diagnostics": [], "only": ["refactor.extract"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if a.get("kind") == "refactor.extract"), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any("var extracted = s.area()" in t for t in texts), f"{texts}"


def t_extract_constant(s):
    start = s.pos("ExtractConstant.java", "return 2 + 3 * 4;", "3 * 4")
    end = {"line": start["line"], "character": start["character"] + len("3 * 4")}
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("ExtractConstant.java")},
        "range": {"start": start, "end": end},
        "context": {"diagnostics": [], "only": ["refactor.extract"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "constant" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any("EXTRACTED_CONSTANT = 3 * 4" in t for t in texts), f"{texts}"


def t_extract_method(s):
    start = s.pos("ExtractMethod.java", "int sum = a + b;", "int")
    endtok = s.pos("ExtractMethod.java", "int scaled = sum * 2;", "int scaled = sum * 2;")
    end = {"line": endtok["line"], "character": endtok["character"] + len("int scaled = sum * 2;")}
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("ExtractMethod.java")},
        "range": {"start": start, "end": end},
        "context": {"diagnostics": [], "only": ["refactor.extract"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "method" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    declares = any("private int extracted(int a, int b)" in t and "return scaled;" in t for t in texts)
    call = any("int scaled = extracted(a, b);" in t for t in texts)
    return declares and call, f"{texts}"


def t_inline_variable(s):
    var = s.pos("Inline.java", "int sum = a + b;", "sum")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("Inline.java")},
        "range": {"start": var, "end": var},
        "context": {"diagnostics": [], "only": ["refactor.inline"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if a.get("kind") == "refactor.inline"), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any(t == "(a + b)" for t in texts), f"{texts}"


def t_inline_method(s):
    cur = s.pos("InlineMethod.java", "return twice(n + 1);", "twice")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("InlineMethod.java")},
        "range": {"start": cur, "end": cur},
        "context": {"diagnostics": [], "only": ["refactor.inline"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "method" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any(t == "((n + 1) * 2)" for t in texts), f"{texts}"


def t_inline_field(s):
    cur = s.pos("InlineField.java", "private static final int FACTOR = 3;", "FACTOR")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("InlineField.java")},
        "range": {"start": cur, "end": cur},
        "context": {"diagnostics": [], "only": ["refactor.inline"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "field" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any(t == "3" for t in texts) and any(t == "" for t in texts), f"{texts}"


def t_change_method_access(s):
    cur = s.pos("ChangeMethodAccess.java", "private int secret()", "secret")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("ChangeMethodAccess.java")},
        "range": {"start": cur, "end": cur},
        "context": {"diagnostics": [], "only": ["refactor.rewrite"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "public" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any(t == "public" for t in texts), f"{texts}"


def t_replace_constructor(s):
    cur = s.pos("ReplaceConstructor.java", "ReplaceConstructor(int value) {", "ReplaceConstructor")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("ReplaceConstructor.java")},
        "range": {"start": cur, "end": cur},
        "context": {"diagnostics": [], "only": ["refactor.rewrite"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "factory" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    factory = any("static ReplaceConstructor create(int value)" in t
                  and "return new ReplaceConstructor(value);" in t for t in texts)
    redirect = any(t == "ReplaceConstructor.create(42)" for t in texts)
    return factory and redirect, f"{texts}"


def t_add_parameter(s):
    cur = s.pos("AddParameter.java", "return scale(10, 2);", "scale")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("AddParameter.java")},
        "range": {"start": cur, "end": cur},
        "context": {"diagnostics": [], "only": ["refactor.rewrite"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "add parameter" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any(t == ", int param2" for t in texts), f"{texts}"


def t_remove_parameter(s):
    src = s.files["RemoveParameter.java"].split("\n")
    cur = s.pos("RemoveParameter.java", "private int compute(int a, int unused)", "unused")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("RemoveParameter.java")},
        "range": {"start": cur, "end": cur},
        "context": {"diagnostics": [], "only": ["refactor.rewrite"]}}, timeout=6)
    actions = r or []
    picked = next((a for a in actions if "remove" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in actions]}"
    if picked.get("edit"):
        return False, "edit present before resolve"
    resolved = s.req("codeAction/resolve", picked, timeout=6)
    changes = ((resolved or {}).get("edit") or {}).get("changes") or {}
    edits = [e for edits in changes.values() for e in edits]
    deleted = set()
    for e in edits:
        if e.get("newText", "") != "":
            return False, f"unexpected non-deletion: {e}"
        rng = e["range"]
        if rng["start"]["line"] != rng["end"]["line"]:
            return False, "multi-line deletion"
        deleted.add(src[rng["start"]["line"]][rng["start"]["character"]:rng["end"]["character"]])
    return deleted == {", int unused", ", 9"}, f"{sorted(deleted)}"


def t_create_missing_field(s):
    ds = diagnostics(s, "CreateMissingField.java")
    r = s.req("textDocument/codeAction", {
        "textDocument": {"uri": s.uri("CreateMissingField.java")},
        "range": full_range(s, "CreateMissingField.java"),
        "context": {"diagnostics": ds}}, timeout=6)
    picked = next((a for a in (r or []) if "create field" in (a.get("title") or "").lower()), None)
    if picked is None:
        return False, f"{[a.get('title') for a in (r or [])]}"
    changes = (picked.get("edit") or {}).get("changes") or {}
    texts = [e.get("newText", "") for edits in changes.values() for e in edits]
    return any("private int value;" in t for t in texts), f"{texts}"


def t_formatting(s):
    r = s.req("textDocument/formatting", {
        "textDocument": {"uri": s.uri("Messy.java")},
        "options": {"tabSize": 2, "insertSpaces": True}}, timeout=6)
    edits = r or []
    # google-java-format-quality output should introduce real edits (not empty)
    nonempty = [e for e in edits if e.get("newText", "") not in ("",)]
    return len(nonempty) >= 1, f"{len(edits)} edits"


def t_workspace_diagnostics(s):
    # pull-model workspace diagnostics: one full report per source file, errors surfaced
    r = s.req("workspace/diagnostic", {"previousResultIds": []}, timeout=6)
    items = (r or {}).get("items") if isinstance(r, dict) else None
    if not items:
        return False, "no reports"
    bad = next((it for it in items if str(it.get("uri", "")).endswith("DiagError.java")), None)
    bad_diags = len(bad.get("items") or []) if bad else 0
    ok = bad is not None and bad_diags >= 1
    return ok, f"{len(items)} reports, DiagError diags={bad_diags}"


def t_document_diagnostics(s):
    # pull-model single-document diagnostics
    r = s.req("textDocument/diagnostic", {
        "textDocument": {"uri": s.uri("DiagError.java")}}, timeout=6)
    items = (r or {}).get("items") if isinstance(r, dict) else None
    kind = (r or {}).get("kind") if isinstance(r, dict) else None
    return bool(items) and kind == "full", f"kind={kind}, {len(items) if items else 0} diagnostics"


def t_reference_codelens(s):
    # lazy "N references" code lens: listed with no command + data, filled by codeLens/resolve
    lenses = s.req("textDocument/codeLens", {
        "textDocument": {"uri": s.uri("Geometry.java")}}, timeout=6)
    if not lenses:
        return False, "no lenses"
    lazy = next((l for l in lenses if l.get("command") is None and l.get("data") is not None), None)
    if lazy is None:
        return False, "no lazy reference lens"
    resolved = s.req("codeLens/resolve", lazy, timeout=6)
    title = ((resolved or {}).get("command") or {}).get("title", "")
    return "reference" in title, f"title={title!r}"


def t_semantic_range(s):
    full = s.req("textDocument/semanticTokens/full", {
        "textDocument": {"uri": s.uri("Geometry.java")}}, timeout=6)
    fd = (full or {}).get("data") or []
    r = s.req("textDocument/semanticTokens/range", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "range": {"start": {"line": 0, "character": 0}, "end": {"line": 4, "character": 0}}}, timeout=6)
    data = (r or {}).get("data") if isinstance(r, dict) else None
    ok = bool(data) and len(data) % 5 == 0 and len(data) < len(fd)
    return ok, f"range={len(data) if data else 0} ints, full={len(fd)}"


def t_semantic_delta(s):
    # delta falls back to a full token set with a resultId (spec-permitted)
    r = s.req("textDocument/semanticTokens/full/delta", {
        "textDocument": {"uri": s.uri("Geometry.java")},
        "previousResultId": "stale"}, timeout=6)
    data = (r or {}).get("data") if isinstance(r, dict) else None
    return bool(data) and len(data) % 5 == 0, f"{len(data) if data else 0} ints, resultId={(r or {}).get('resultId')}"


def t_rename_type(s):
    pos = s.pos("RenameType.java", "class RenameType", "RenameType")
    prep = s.req("textDocument/prepareRename", {
        "textDocument": {"uri": s.uri("RenameType.java")},
        "position": pos}, timeout=6)
    if not prep or prep.get("placeholder") != "RenameType":
        return False, f"prepare={prep}"
    edit = s.req("textDocument/rename", {
        "textDocument": {"uri": s.uri("RenameType.java")},
        "position": pos, "newName": "Renamed"}, timeout=6)
    changes = (edit or {}).get("changes") or {}
    edits = []
    for k, v in changes.items():
        if str(k).endswith("RenameType.java"):
            edits = v
    allnew = bool(edits) and all(e.get("newText") == "Renamed" for e in edits)
    # class decl + constructor decl + return type + `new` expression
    return len(edits) == 4 and allnew, f"{len(edits)} edits, allRenamed={allnew}"

TESTS = [
    # name, phase, kind, fn
    ("definition (baseline)", "-", "baseline", t_definition),
    ("references (baseline)", "-", "baseline", t_references),
    ("implementation", "A", "baseline", t_implementation),
    ("typeDefinition", "A", "baseline", t_type_definition),
    ("declaration", "A", "baseline", t_declaration),
    ("documentHighlight", "A", "baseline", t_document_highlight),
    ("selectionRange", "A", "baseline", t_selection_range),
    ("formatting (google-java-format)", "A", "target", t_formatting),
    ("semanticTokens/full", "B", "baseline", t_semantic_tokens),
    ("inlayHint", "B", "baseline", t_inlay_hint),
    ("callHierarchy", "B", "baseline", t_call_hierarchy),
    ("typeHierarchy", "B", "baseline", t_type_hierarchy),
    ("codeAction: generate members", "B", "baseline", t_codeaction_generate),
    ("codeAction: organizeImports", "B", "baseline", t_organize_imports),
    ("codeAction: add overrides", "T1", "baseline", t_add_overrides),
    ("codeAction: extract variable", "T1", "baseline", t_extract_variable),
    ("codeAction: extract constant", "T1", "baseline", t_extract_constant),
    ("codeAction: extract method", "T1", "baseline", t_extract_method),
    ("codeAction: inline variable", "T1", "baseline", t_inline_variable),
    ("codeAction: inline method", "T1", "baseline", t_inline_method),
    ("codeAction: inline field", "T1", "baseline", t_inline_field),
    ("codeAction: change method access", "T1", "baseline", t_change_method_access),
    ("codeAction: replace constructor with factory", "T1", "baseline", t_replace_constructor),
    ("codeAction: add parameter", "T1", "baseline", t_add_parameter),
    ("codeAction: remove parameter", "T1", "baseline", t_remove_parameter),
    ("codeAction: create missing field", "T1", "baseline", t_create_missing_field),
    ("workspace diagnostics", "C", "baseline", t_workspace_diagnostics),
    ("document diagnostics (pull)", "C", "baseline", t_document_diagnostics),
    ("rename type (declaration + references)", "T2", "baseline", t_rename_type),
    ("reference code lens (lazy resolve)", "T2", "baseline", t_reference_codelens),
    ("semanticTokens/range", "T2", "baseline", t_semantic_range),
    ("semanticTokens/full/delta", "T2", "baseline", t_semantic_delta),
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
