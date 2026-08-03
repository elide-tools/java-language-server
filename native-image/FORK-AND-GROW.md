# Fork-and-grow: closing the JLS → jdtls gap, native-image-safe

Branch: `native-image`. Baseline proven: JLS builds+runs as a GraalVM 25 native
image and answers a real LSP session (see `native-image/` build script, feature,
substitution; oracle `smoke/lsp_smoke.py`). **Zero edits to JLS source were
needed for native-image** — only additive build tooling + `-Djava.home` at
runtime.

This document is the roadmap for growing JLS's feature set toward jdtls parity,
restricted to features that are **native-image-safe** (no OSGi, no runtime
classloading of arbitrary bytecode). Each feature has a runnable acceptance test
in `native-image/smoke/features/feature_tests.py` (tagged `target`, currently
RED) that flips to `baseline` (GREEN) when the feature lands, and a note on
mirroring the assertion into the Elide test suite.

## Status (2026-08-01) — Phase A landed

INFRA-1 and all five Phase-A features are implemented, committed one-per-commit
with a JUnit test each, and **green in the native image** (acceptance harness
`smoke/features/feature_tests.py`: 5/5 Phase-A targets now `baseline`; full JVM
suite 251 tests, 0 failures):

- **INFRA-1** — `JlsNativeImageFeature` blanket-registers all `org.javacs.lsp.*`
  DTOs for reflection (discovered from the code source at build time). This
  removed the `MissingReflectionRegistrationError` class of crashes; the new
  `SelectionRange`/`SelectionRangeParams`/`DocumentHighlight` DTOs serialize
  in-image with no per-field agent runs.
- **A1 implementation**, **A2 typeDefinition**, **A3 declaration**,
  **A4 documentHighlight**, **A5 selectionRange** — done.
- **A6 formatting** — still a `target`; deferred to Elide's google-java-format.

Two native-image / javac lessons worth carrying into Phase B:

1. **`Elements.getTypeElement` returns null for unnamed-package types.** Resolve
   type declarations by walking the compiled trees (or via
   `CompilerProvider.findAnywhere`, which also covers the doc path + type index),
   not `getTypeElement`. A named-package JUnit fixture masks this; the
   default-package in-image harness caught it (typeDefinition).
2. **`CompilerProvider.findTypeDeclaration` is not a substitute for
   `findAnywhere`.** It missed the default-package interface that `findAnywhere`
   (used by goto-definition) resolves. Prefer `findAnywhere` for cross-file type
   lookup.

## Status (2026-08-01) — Phase B landed

All six Phase-B features are implemented, committed one-per-commit with a JUnit
test each, and **green in the native image** (harness: 6/6 Phase-B targets now
`baseline`; full JVM suite 261 tests, 0 failures):

- **B1 semanticTokens/full** — AST tokenizer classifying identifiers/declarations
  by resolved element kind into a delta-encoded token stream (richer than the
  legacy `java/colors`; declares a legend).
- **B2 inlayHint** — parameter-name hints at call sites + `var` inferred-type
  hints (via `JCVariableDecl.declaredUsingVar()`).
- **B3 callHierarchy** — prepare/incoming (reuses `FindReferences`, groups by
  caller)/outgoing (walks the body; resolves callees in-task or via `findAnywhere`).
- **B4 typeHierarchy** — prepare/supertypes (`Types.directSupertypes`)/subtypes
  (`findTypeReferences` filtered by `Types.isSubtype`).
- **B5 generate members** — `source` code actions: generate constructor +
  getters/setters (new `GenerateConstructor`/`GenerateGettersAndSetters` rewrites).
- **B6 organizeImports** — `source.organizeImports` action backed by `AutoFixImports`.

**Recurring lesson reinforced:** the unnamed (default) package keeps breaking
`Elements.getTypeElement`, `CompilerProvider.findTypeDeclaration`, and
`FindHelper.findMethod` (all element/index lookups keyed by qualified name).
B5's generators first used `getTypeDeclaration`+`getTypeElement` and returned
nothing in-image (the named-package JUnit passed); the fix resolves the class by
**tree scan within the file** (no element lookup). Rule of thumb for Phase C and
beyond: prefer tree/`findAnywhere` resolution over `getTypeElement` anywhere a
default-package project could reach.

## Status (2026-08-02) — codeAction/resolve + refactor code actions (Tier 1/2)

Beyond the Phase A/B parity list, the code-action surface landed on
`native-image` in commit-per-feature steps, each with a JUnit test and (where the
diagnostic can be produced in a flat default-package fixture) an in-image
`feature_tests.py` case. Full JVM suite: 275 tests, 0 failures via
`scripts/junit-summary.py`.

**Tier 2 — `codeAction/resolve` (lazy edits).** `textDocument/codeAction` lists
cursor + source actions with an opaque `data` descriptor and no `edit`;
`codeAction/resolve` reconstructs the rewrite from that descriptor and computes
the edit on demand (`codeActionProvider.resolveProvider = true`). A menu of N
actions costs one compile instead of one-per-action. Diagnostic quick fixes stay
**eager**: a rewrite that returns `CANCELLED` is only detectable by running it, so
a lazy quick fix would offer an unresolvable action. `data` is a `JsonElement`
(gson-special-cased, no reflection); reconstruction is an explicit factory switch
— no `Class.forName`, no extra reflection registration.

**Tier 1 — refactor/source code actions (complete).** Most of `rewrite/` was
*scaffolding* (empty `CANCELLED` stubs), not "written but unwired." Every one is
now a real javac-AST implementation, each gated so it only fires when provably
behavior-preserving:

- **Add missing @Override** (`source`) — `AutoAddOverrides` (walks `FindMissingOverride`).
- **refactor.extract:**
  - *extract variable* (`ExtractVariable`) — `var extracted = <expr>;` before the
    enclosing block statement; value-producing expression in a block.
  - *extract constant* (`ExtractConstant`) — `private static final` field; the
    expression must be legal in a static initializer (no locals/params/`this`/non-static members).
  - *extract method* (`ExtractMethod`) — free locals become parameters; a single
    variable live after the selection becomes the return value; CANCELLED on
    non-whole-statement selections, more than one return value, or escaping control flow.
- **refactor.inline:**
  - *inline variable* (`InlineVariable`) — substitute + delete decl; local never
    reassigned, initializer side-effect-free over effectively-final inputs.
  - *inline method* (`InlineMethod`) — same-file single-`return` method whose body
    references only its parameters; arguments must be side-effect-free.
  - *inline field* (`InlineField`) — `private static final` field with a constant,
    side-effect-free initializer; qualified uses replaced whole.
- **refactor.rewrite:**
  - *change method access* (`ChangeMethodAccess`) — replace/insert/remove the access
    keyword; offers the levels the method lacks.
  - *replace constructor with factory* (`ReplaceConstructorWithFactoryMethod`) —
    generate a static `create(...)` and redirect same-file `new` calls; top-level
    non-generic class, constructor left intact.
  - *add parameter* (`AddParameter`) — promote an extra call argument into a
    parameter (type inferred from the argument); same-file private method, single
    call site, so the file compiles with no other edits.
  - *remove parameter* (`RemoveParameter`) — drop an unused parameter of a private,
    non-overloaded method plus its argument at same-file call sites.
- **quick fixes** (diagnostic-driven, eager):
  - *surround with try/catch* (`CatchException`) — alternative to "Add throws" on an
    unreported checked exception.
  - *create missing field* (`CreateMissingField`) — on "cannot find symbol" for an
    assignment target, insert a field with the assigned expression's type.

Shared analyses (purity, effectively-final, static-safety) are reused between the
lazy `canX` detection and `rewrite`/`resolve`, so a listed action never resolves
to nothing.

### What's next
The refactor/quick-fix catalog is complete. Remaining non-refactor targets:
**C1 workspace/pull diagnostics** and **A6 formatting-in-Elide**. Note: the flat
default-package acceptance harness cannot surface Flow-phase compiler diagnostics
(e.g. unreported-exception), so a few quick fixes are validated by JUnit only —
worth revisiting if the harness gains named-package fixtures.

## Why this is the right shape

- JLS is built directly on **javac** (`com.sun.source.tree`, `javax.lang.model`,
  and `com.sun.tools.javac.*` internals). Every feature below is *additional
  analysis over the same trees/elements JLS already compiles* — no new engine.
- The two areas JLS is genuinely weakest at are **exactly what Elide already
  owns**: real classpath/project resolution (Elide's Maven/Aether
  `MavenClasspathProvider` replaces JLS's `InferConfig` `~/.m2` scavenging) and
  **formatting** (Elide bundles google-java-format, `GoogleJavaFormat.kt`).
- MIT license + plain code ⇒ patching JLS is cheap and upstreamable-in-spirit.

## Ground rules (native-image invariants)

1. **DTO reflection is mandatory and easy to miss.** All `org.javacs.lsp.*`
   request/response POJOs are (de)serialized by gson *reflectively*. The native
   image only includes fields the Phase-1 tracing agent actually observed. A
   field the agent never populated crashes the server at runtime, e.g. this real
   failure hit while building the harness:
   ```
   MissingReflectionRegistrationError: Cannot reflectively read or write field
   'public java.util.List org.javacs.lsp.CodeActionContext.only'
   ```
   (the agent run sent `codeAction` with `context.diagnostics` but never
   `context.only`). **INFRA-1 below fixes this class of bug once for all DTOs.**
2. **New LSP methods need three edits, not one:** a `case` in
   `org.javacs.lsp.LSP.connect(...)` dispatch, a method on
   `org.javacs.lsp.LanguageServer` + `JavaLanguageServer`, and a capability flag
   in `JavaLanguageServer.initialize(...)`. Unhandled methods currently return
   *no response* (the harness reports `TimeoutError` for them — that is the
   "unimplemented" signal).
3. **No runtime classloading.** Anything that must load+execute arbitrary user
   bytecode at runtime (annotation processors) cannot run in-image. See the
   Annotation Processing note.
4. **Every grow-step ends with:** rebuild (`native-image/build-native.sh`) →
   `feature_tests.py` target flips DONE → mirror assertion into Elide.

## Enabling infrastructure (do first)

### INFRA-1 — blanket reflection for all `org.javacs.lsp.*` DTOs
Register every LSP DTO for full field+ctor reflection so gson never hits a
missing field again (covers all current and future feature params/results).
- **How:** add a `reflection` block to a checked-in metadata file
  (`native-image/reachability-metadata.extra.json`, merged via
  `-H:ConfigurationFileDirectories`) enumerating `org.javacs.lsp.*` with
  `allDeclaredFields`+`allDeclaredConstructors`; or a tiny `Feature` using
  `RuntimeReflection.registerAllDeclaredFields` over the package. Prefer the
  Feature (no hand-maintained list).
- **Native-image note:** this is the single highest-leverage config change; it
  removes the whole `MissingReflectionRegistrationError` failure mode.
- **Acceptance:** `feature_tests.py` no longer shows `server closed stdout` for
  `codeAction` tests (they return `[]` until the feature lands, not crash).

### INFRA-2 — config regen workflow
Keep `smoke/agent_exercise.py` as the metadata generator. When adding a feature,
extend it to drive the new request (with **all** params populated), re-run
`smoke/run-agent-server.sh`, and commit the merged
`native-image/agent-config/reachability-metadata.json`. INFRA-1 makes DTOs
robust regardless, but new *compiler* code paths still benefit from a fresh
agent pass.

### INFRA-3 — capabilities + dispatch scaffolding
Add the capability advertisement + dispatch `case` for each new method as it
lands (ground rule 2). Group related methods (e.g. all navigation variants) to
minimize churn.

---

## Phase A — easy wins (pure tree/element analysis)

Each is a small provider over data JLS already computes. All native-image-safe;
only new DTOs (register via INFRA-1).

### A1 — `textDocument/implementation`
- **jdtls:** jump from an interface/abstract method (or type) to its concrete
  implementations.
- **Substrate:** JLS has `ScanClassPath`/`index` (type index),
  `navigation/FindReferences`, `javax.lang.model.util.Types#isSubtype`,
  `Elements`. Enumerate workspace types (FileStore source roots), keep those
  whose element is a subtype and that override the target method.
- **Sketch:** new `navigation/ImplementationProvider`; resolve element at
  position via `Trees`; if type → subtypes, if method → overriders.
- **Capability:** `implementationProvider: true`.
- **Test:** `t_implementation` — implementations of `Shape.area()` include
  `Circle.java` and `Square.java`.

### A2 — `textDocument/typeDefinition`
- **jdtls:** from a variable/expression to the declaration of its *type*.
- **Substrate:** `Trees.getTypeMirror`/`getElement` on the `TreePath`; map the
  type's `TypeElement` to a source location (JLS `FindTypeDeclarationNamed` +
  `CompilerProvider.findTypeDeclaration`).
- **Capability:** `typeDefinitionProvider: true`.
- **Test:** `t_type_definition` — type of `s` in `s.area()` resolves to
  `Shape.java`.

### A3 — `textDocument/declaration`
- **jdtls:** go-to-declaration (for JLS ≈ definition).
- **Substrate:** delegate to the existing `navigation/DefinitionProvider`.
- **Capability:** `declarationProvider: true`.
- **Test:** `t_declaration` — declaration of the `describe(shape)` call is in
  `Geometry.java`.

### A4 — `textDocument/documentHighlight`
- **jdtls:** highlight all read/write occurrences of the symbol under the cursor,
  within the current file.
- **Substrate:** `navigation/FindReferences` scoped to one `CompilationUnitTree`;
  classify read vs write (assignment LHS → Write) for `DocumentHighlightKind`.
- **Capability:** `documentHighlightProvider: true`.
- **Test:** `t_document_highlight` — cursor on `sum` in `total()` returns ≥3
  highlights.

### A5 — `textDocument/selectionRange`
- **jdtls:** smart expand-selection (identifier → expression → statement → block
  → member → class).
- **Substrate:** walk the `TreePath` from the position outward; each parent node's
  source span (via `Trees.getSourcePositions` + `LineMap`) becomes the next
  `SelectionRange.parent`.
- **Capability:** `selectionRangeProvider: true`.
- **Test:** `t_selection_range` — position on `radius` returns a range with a
  non-null `parent` chain.

### A6 — formatting (real) via google-java-format
- **jdtls:** on-type/range/file formatting.
- **Reality:** JLS's current `textDocument/formatting` returns a single
  empty-text edit (no real formatting; confirmed by `t_formatting` = 1 empty
  edit). **In Elide, delegate to the bundled google-java-format**
  (`EmbeddedTool` / `GoogleJavaFormat.kt`) — native-image-safe (Elide already AOT
  compiles it) and free. Standalone JLS would need to bundle the GJF jar (adds a
  dep + its reflection config).
- **Capability:** already `documentFormattingProvider: true`; add
  `documentRangeFormattingProvider` when range support lands.
- **Test:** `t_formatting` — formatting `Messy.java` yields ≥1 non-empty edit.
- **Elide note:** this test is most meaningful in Elide (where GJF is wired); in
  standalone it stays RED unless the GJF jar is bundled.

---

## Phase B — medium wins

### B1 — `textDocument/semanticTokens/full`
- **jdtls:** standard semantic highlighting.
- **Substrate:** JLS **already computes semantic colors** (`markup/ColorProvider`,
  `markup/Colorizer`, `markup/SemanticColors`) and ships them via a *non-standard*
  `java/colors` notification. Re-encode those spans into the LSP semantic-tokens
  delta-encoded `int[]` (5 ints per token) with a declared legend.
- **Capability:** `semanticTokensProvider: { legend, full: true }`.
- **Native-image note:** the legend is static; only new DTOs.
- **Test:** `t_semantic_tokens` — `data` non-empty, length % 5 == 0.

### B2 — `textDocument/inlayHint`
- **jdtls:** parameter-name hints at call sites; inferred types for `var`.
- **Substrate:** walk `MethodInvocationTree` args (map to `ExecutableElement`
  parameter names) and `VariableTree` with `var` (resolve `TypeMirror` via
  `Trees`).
- **Capability:** `inlayHintProvider: true`.
- **Test:** `t_inlay_hint` — ≥1 hint in `Geometry.java` (param names on
  `describe(shape)` / type of `double a`).

### B3 — `callHierarchy/*`
- **jdtls:** prepare + incoming + outgoing calls.
- **Substrate:** prepare = resolve method element (`FindMethodDeclarationAt`);
  incoming = `rewrite/FindMethodReferences`/`navigation/FindReferences` grouped by
  enclosing method; outgoing = walk the method body's `MethodInvocationTree`s.
- **Capability:** `callHierarchyProvider: true`.
- **Test:** `t_call_hierarchy` — incoming calls of `describe` include `total`.

### B4 — `typeHierarchy/*`
- **jdtls:** prepare + supertypes + subtypes.
- **Substrate:** supertypes via `Types.directSupertypes`/`Elements` (easy);
  subtypes via the workspace type index (`ScanClassPath` + `index`) filtered by
  `Types.isSubtype` (moderate).
- **Capability:** `typeHierarchyProvider: true`.
- **Test:** `t_type_hierarchy` — supertypes of `Circle` include `Shape`.

### B5 — code-gen source actions
- **jdtls:** generate getters/setters, `toString()`, `hashCode()/equals()`,
  constructors, delegate methods, override/implement.
- **Substrate:** JLS `rewrite/` already has `ImplementAbstractMethods`,
  `OverrideInheritedMethod`, `GenerateRecordConstructor`, `EditHelper`. Add
  `rewrite/GenerateGettersSetters`, `GenerateToString`, `GenerateHashCodeEquals`,
  `GenerateConstructor`; surface them from `action/CodeActionProvider` as
  `source.*` code actions (template generation over `Elements`).
- **Capability:** already `codeActionProvider: true`; declare `codeActionKinds`.
- **Test:** `t_codeaction_generate` — a `source` code action titled like
  getter/toString/constructor/hashCode is offered on `Geometry`.
- **Depends on INFRA-1** (CodeActionContext.only field).

### B6 — `source.organizeImports` code action
- **Substrate:** JLS has `rewrite/AutoFixImports` + `rewrite/FindUsedImports`;
  expose as an `organizeImports` source action.
- **Test:** `t_organize_imports` — a source action mentioning "import" is offered.
- **Depends on INFRA-1.**

---

## Phase C — harder (feasible, bounded)

### C1 — whole-workspace diagnostics
- **jdtls:** as-you-type diagnostics across the whole project (incremental
  builder). JLS lints only open/edited files.
- **Substrate:** compile all source roots (`FileStore` + `JavaCompilerService`)
  and publish; or implement the pull model `workspace/diagnostic`.
- **Native-image note:** safe; **performance** is the real cost — JLS's model is
  focused per-file compile, so full-project as-you-type needs batching/debounce
  and is the one place jdtls's incremental builder stays ahead. Scope to
  on-open/on-save initially.
- **Test:** `t_workspace_diagnostics` — `workspace/diagnostic` returns items.

---

## NOTE — annotation processing (the one true wall)

Annotation processors (Lombok, Dagger, AutoValue, MapStruct, immutables) are
**arbitrary user bytecode loaded from the project classpath at runtime**. GraalVM
native-image is closed-world: it **cannot define/load classes that were not
compiled into the image**, so APT **cannot run in-process in any native image** —
this is imposed by native-image, not by JLS, and jdtls-in-native-image would hit
the identical wall (on top of OSGi). Consequences and the realistic path:

- **In-image live analysis** will not see APT-generated symbols (e.g. Lombok's
  generated getters, a Dagger component). Hovers/completion on generated members
  will be absent.
- **Escape hatch (design, not yet built):** Elide stages a *real runnable JDK*
  (`build.mts` copies `bin/java` + `lib/modules` + `conf/`). So APT can be
  delegated to a **forked `javac` subprocess** on the staged JDK, run
  out-of-process on open/save, with generated sources fed back as an extra source
  root the in-image analyzer then reads. This mirrors how Elide already forks the
  staged `java` for the native-image builder and Maven.
- **Investigation task (before committing):** measure (1) how often target
  projects actually need APT for *editor* correctness vs just build, (2) latency
  of a subprocess `javac -proc:only` round on save, (3) whether generated-sources
  re-feed keeps the in-image index consistent. Prototype with a Lombok fixture
  under `smoke/features/apt/` and a test that asserts a generated getter resolves
  *after* the subprocess round (RED in pure in-image, GREEN with the subprocess
  design).
- **Scope decision:** treat APT as an explicit, documented boundary for v1
  (in-image), with the subprocess design as a fast-follow only if real projects
  demand it.

---

## Test workflow

Harness: `native-image/smoke/features/` (shared fixture project + `feature_tests.py`).
Reuses the `Server` LSP client from `smoke/lsp_smoke.py`; each test starts its
own server (crash-isolated) and asserts one LSP contract.

Run against the native image:
```
cd native-image/smoke/features
JAVA_HOME=/path/to/graal-25 python3 feature_tests.py fixture -- bash ../run-native-server.sh
```
Run against the JVM baseline (oracle parity): swap `run-native-server.sh` →
`run-jvm-server.sh`.

Output tags: `PASS/FAIL` for `baseline` (regression guard, fails the run),
`DONE/TODO` for `target` (backlog; never fails the run). Current state:
baseline GREEN for A1–A5, B1–B6, and the full Tier-1 refactor/quick-fix catalog
(add overrides; extract variable/constant/method; inline variable/method/field;
change method access; replace constructor with factory; add/remove parameter;
create missing field); **2 targets TODO** (A6 formatting-in-Elide, C1 workspace
diagnostics).

Per feature, as it lands:
1. implement provider + dispatch + capability in JLS source (on `native-image`
   branch),
2. update `agent_exercise.py` to exercise it, regen config (INFRA-2),
3. `build-native.sh`, confirm its `feature_tests.py` target flips DONE,
4. move the test tag from `target`→`baseline`,
5. **mirror the assertion into the Elide repo** as an LSP-level integration test
   against the Elide-built binary (same fixture, same request/assert), so the
   feature is defended where it ships.

## Suggested order (value / effort)

1. INFRA-1 (unblocks codeAction + all future DTOs) — tiny, high value.
2. A1–A5 (navigation/highlight/selection) — each ~a provider, all GREEN-able fast.
3. B1 semanticTokens (reuse existing color engine) + A6 formatting-in-Elide.
4. B3/B4 hierarchies, B2 inlay hints.
5. B5/B6 code-gen + organizeImports.
6. C1 workspace diagnostics (perf-scoped).
7. APT investigation note → decide.

## Relationship to Phase 4/6 (Elide fold)

Phase 4 changes shape: rather than "minimal patches to vendor JLS as-is," JLS
becomes an **Elide-owned fork** on this `native-image` branch with the backlog
above. Vendoring into Elide (Phase 6) still applies: it inherits Elide's jrt
hooks + staged `java.home` (so the runtime-JDK requirement is free), swaps
`InferConfig` for `MavenClasspathProvider`, wires `elide lsp` → `LSP.connect`,
and gains google-java-format formatting. INFRA-1's DTO reflection folds into
Elide's `reachability-metadata.json`.
