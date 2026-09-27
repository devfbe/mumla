## IDE Integration

Android Studio has this project open (`nix run .#android-studio`). Two MCP servers expose it, and
using them is **mandatory** for everything they can do — they are faster, cheaper (tokens and CPU)
and more accurate than shell tools, and they keep the IDE, its index and the disk in step:

- **`jetbrains-index`** — code navigation and refactoring:
  - **Finding classes/files** — `ide_find_class`, `ide_find_file`
  - **Finding references** — `ide_find_references` instead of grep/search
  - **Go to definition** — `ide_find_definition`
  - **Type/call hierarchy** — `ide_type_hierarchy`, `ide_call_hierarchy`
  - **Finding implementations** — `ide_find_implementations`
  - **Text search** (strings, XML, comments) — `ide_search_text`
  - **Renaming, deleting, moving** — `ide_refactor_rename`, `ide_refactor_safe_delete`, `ide_move_file`
  - **Diagnostics** — `ide_diagnostics`
- **`studio`** (Android Studio's built-in MCP server) — editing files, building, running tests and
  run configurations, file problems.

### Rules

1. **No shell text tools on sources.** No `grep`/`rg`/`sed`/`awk`/`cat`/`head`/`ls`/python scripts on
   files of this checkout. Find code through `jetbrains-index`; read it with Read (or studio `read_file`).
2. **Change files only through Studio**, so its editor never holds a version that differs from the
   disk (that raises a blocking "file changed on disk" dialog and stalls every MCP call):
   - `apply_patch` (preferred): Codex `*** Begin Patch` / `*** Update File:` / `@@` hunks, or a
     unified diff; `*** Add File:` and `*** Delete File:` too. It fails loudly when the context does
     not match.
   - `create_new_file` for new files (`overwrite: true` to replace one wholesale).
   - `replace_text_in_file` only with an `oldText` that occurs exactly once, and always
     `replaceAll: false`: it silently replaces the first match, even inside a longer line.
   - Before an IDE refactoring, `ide_sync_files` the files involved. If a refactoring or any Studio
     call times out, stop and report: Studio is most likely showing a modal dialog.
3. **Check every edit in the IDE.** After each batch of edits run `ide_diagnostics` with `files:`
   set to all changed files (about a second). It must show no errors before anything is built or
   tested. Never use a Gradle run as a compile check.
4. **Build and test through the IDE** (`studio` MCP; warm daemon, incremental):
   - Compile: `build_project` with `filesToRebuild` (or without, for the project).
   - Tests: `get_run_configurations` with `filePath` lists the run points of a test file;
     `execute_run_configuration` with `filePath` + `line` runs a class or a single test. Tests run
     in Studio's selected build variant (beta debug). Read the result from
     `<module>/build/test-results/test<Variant>UnitTest/TEST-<class>.xml`.
   - File problems: `get_file_problems` (or `ide_diagnostics`).
5. **One `verify` per task, at the end, also through Studio.** The shared run configuration
   `.run/verify.run.xml` runs `./gradlew verify` (all shipped variants assembled, foss unit tests,
   device tests built, lint, detekt): `execute_run_configuration(configurationName: "verify",
   waitForExit: false)` returns a `fullOutputPath`; wait until it shows `BUILD SUCCESSFUL` or
   `BUILD FAILED` (e.g. `until grep -qE "BUILD (SUCCESSFUL|FAILED)" <path>; do sleep 3; done`).
   A long `waitForExit: true` call times out while the build keeps running.
6. **No worktrees.** The IDE indexes only this checkout. Work here on a feature branch from
   `modernization`, one agent at a time. The hook blocks creating or editing worktrees.

A PreToolUse hook (`.claude/hooks/ide-first.py`) enforces rules 1, 2, 5 and 6 while the IDE is
reachable (command-line Gradle, Edit/Write on sources, shell text tools on sources, worktrees) and
logs every decision to `.claude/logs/ide-first.jsonl`. Build output, `.claude/`, the Nix store and
`/tmp` stay free. When Studio is not running, the hook steps aside and
`nix develop --command ./gradlew verify` is the fallback; say so in the report.

## Agent workflow

- The orchestrating model (Opus) plans: it breaks work into small, precise tasks (files, symbols,
  tests to write, definition of done) using the IDE index, and reviews every result (diff,
  `ide_diagnostics`, hook log, MCP vs. shell call counts) before the next task.
- Implementation runs one agent at a time in this checkout. Opus implements by default; Sonnet only
  for small, well-bounded tasks (one concern, a few files, no lifecycle/threading/persistence or
  audio-pipeline questions). In UX wave 2 Sonnet was clean on such a task but needed substantial
  corrections on larger ones (a process-death crash, a database write per slider step, broken
  translations, an unfinished verify), while Opus delivered the larger ones clean and cheaper.
- A task is only done when its `verify` run is green; an agent never reports success on a verify it
  did not see finish.
- Every subagent report lists its MCP calls per tool and any fallback to Gradle or shell tools.
