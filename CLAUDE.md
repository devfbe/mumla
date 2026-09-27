## IDE Integration

Android Studio has this project open (`nix run .#android-studio`). Two MCP servers expose it, and
using them is **mandatory** for everything they can do — they are faster, cheaper (tokens and CPU)
and more accurate than shell tools:

- **`jetbrains-index`** — code navigation and refactoring:
  - **Finding classes/files** — `ide_find_class`, `ide_find_file`
  - **Finding references** — `ide_find_references` instead of grep/search
  - **Go to definition** — `ide_find_definition`
  - **Type/call hierarchy** — `ide_type_hierarchy`, `ide_call_hierarchy`
  - **Finding implementations** — `ide_find_implementations`
  - **Text search** (strings, XML, comments) — `ide_search_text`
  - **Renaming, deleting, moving** — `ide_refactor_rename`, `ide_refactor_safe_delete`, `ide_move_file`
  - **Diagnostics** — `ide_diagnostics`
- **`studio`** (Android Studio's built-in MCP server) — building, running tests and run
  configurations, file problems.

### Rules

1. **No shell text tools on sources.** No `grep`/`rg`/`sed`/`awk`/`cat`/`head`/python scripts on
   files of this checkout. Find code through `jetbrains-index`; read and change it with Read + Edit.
   A PreToolUse hook (`.claude/hooks/ide-first.py`) enforces this while the IDE is reachable and
   logs every block to `.claude/logs/ide-first.jsonl`. Build output, the Nix store and `/tmp` stay free.
2. **Check every edit in the IDE.** After each batch of edits run `ide_diagnostics` with `files:`
   set to all changed files (about a second). It must show no errors before anything is built or
   tested. Never use a Gradle run as a compile check.
3. **Build and test through the IDE** (`studio` MCP; warm daemon, incremental):
   - Compile: `build_project` with `filesToRebuild` (or without, for the project).
   - Tests: `get_run_configurations` with `filePath` lists the run points of a test file;
     `execute_run_configuration` with `filePath` + `line` runs a class or a single test.
     Tests run in Studio's selected build variant (beta debug). The tool output is empty; read the
     result from `<module>/build/test-results/test<Variant>UnitTest/TEST-<class>.xml`.
   - File problems: `get_file_problems` (or `ide_diagnostics`).
   The hook blocks command-line Gradle for anything but `verify` while Studio is reachable.
4. **One Gradle run per task, at the end.** The pre-merge check is
   `nix develop --command ./gradlew verify` (all shipped variants assembled, all unit tests once,
   device tests built, lint, detekt). Use Gradle otherwise only when an IDE tool demonstrably cannot
   do the job, and say which and why.
5. **No worktrees.** The IDE indexes only this checkout. Work here on a feature branch from
   `modernization`, one agent at a time. The hook blocks creating or editing worktrees.

## Agent workflow

- The orchestrating model (Opus) plans: it breaks work into small, precise tasks (files, symbols,
  tests to write, definition of done) using the IDE index.
- Implementation tasks go to Sonnet subagents, one after another in this checkout.
- The orchestrator reviews every result (diff, `ide_diagnostics`, hook log, MCP vs. Bash call
  counts) before the next task, and counts the corrections it had to make. If Sonnet needs many
  corrections or extra iterations, implementation goes back to Opus.
- Every subagent report lists its MCP calls per tool and any fallback to Gradle or shell tools.
