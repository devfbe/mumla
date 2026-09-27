#!/usr/bin/env python3
"""PreToolUse hook: keep all work in the IDE-indexed main checkout, and shell text tools off its sources.

Android Studio indexes exactly one tree: the main checkout. While its MCP server
is reachable, Bash text tools (grep, sed, cat, python, ...), the Grep tool and
the Glob tool may not touch sources inside that tree; navigation, search and
checks go through the jetbrains-index / studio MCP servers instead. Worktrees,
build output, the Nix store, /tmp and everything else stay free, and when the
IDE is down nothing is blocked. Every decision that involves project sources is
appended to .claude/logs/ide-first.jsonl so repeated blocks can be audited.

Worktrees are banned outright (the IDE MCP servers cannot see them): creating one
(EnterWorktree, Agent isolation "worktree", git worktree add) and editing files
inside an existing linked worktree of this repo are always blocked.

Builds, tests and edits go through Android Studio's MCP server as well: while it is reachable,
command-line Gradle may only run harmless meta tasks such as `help` (the pre-merge `verify` runs as a
Studio run configuration), and Edit/Write may not touch the checkout's sources.
"""
import datetime
import json
import os
import re
import socket
import subprocess
import sys

def main_checkout() -> str:
    """The primary worktree of the repo holding this hook; linked worktrees map to it too."""
    if os.environ.get("IDE_FIRST_ROOT"):
        return os.path.realpath(os.environ["IDE_FIRST_ROOT"])
    hook_dir = os.path.dirname(os.path.abspath(__file__))
    try:
        common = subprocess.run(
            ["git", "-C", hook_dir, "rev-parse", "--path-format=absolute", "--git-common-dir"],
            capture_output=True, text=True, timeout=2, check=True,
        ).stdout.strip()
        return os.path.realpath(os.path.dirname(common))
    except (OSError, subprocess.SubprocessError):
        return os.path.realpath(os.path.join(hook_dir, "..", ".."))


INDEXED_ROOT = main_checkout()
LOG = os.environ.get("IDE_FIRST_LOG") or os.path.join(INDEXED_ROOT, ".claude", "logs", "ide-first.jsonl")
IDE_PORT = 29171
STUDIO_PORT = 64342

GRADLE_CALL = re.compile(r"(?:^|[\s;&|(`])(?:\./)?gradlew?(?=\s|$)(?P<args>[^;|&><\n]*)", re.M)
GRADLE_VALUE_OPTIONS = {"--tests", "--console", "-x", "--exclude-task", "-p", "--project-dir", "--warning-mode"}
GRADLE_ALLOWED_TASKS = {"help", "tasks", "projects", "properties", "dependencies"}
GRADLE_HINT = (
    "Blocked by the IDE-first rule (CLAUDE.md): builds and tests go through the studio MCP "
    "(build_project, get_run_configurations + execute_run_configuration, get_file_problems), and so "
    "does the pre-merge check: execute_run_configuration(configurationName=\"verify\", "
    "waitForExit=false), then wait for BUILD SUCCESSFUL/FAILED in the returned fullOutputPath."
)
EDIT_HINT = (
    "Blocked by the IDE-first rule (CLAUDE.md): files of the main checkout are changed through the "
    "studio MCP so Android Studio's editor, index and the disk never diverge: apply_patch (preferred; "
    "fails loudly on a context mismatch), create_new_file for new files, replace_text_in_file only "
    "with a unique oldText and replaceAll=false. Reading with Read stays allowed."
)
EDITING_TOOLS = {"Edit", "Write", "NotebookEdit", "MultiEdit"}

SOURCE_EXT = re.compile(r"\.(kt|kts|java|xml|toml|gradle|pro|proto|properties)$")
FREE_DIR = re.compile(r"/(build|\.gradle|\.cxx|\.git|\.idea|\.claude)(/|$)")
TEXT_TOOL = re.compile(
    r"(^|[\s;|&(`$])(grep|egrep|fgrep|rg|ag|ack|sed|awk|gawk|perl|cat|head|tail|less|more|bat|"
    r"python3?|cut|wc|sort|uniq|diff|find|tree|ls|xargs)\b"
)
RECURSIVE_SEARCH = re.compile(r"(^|[\s;|&(`$])(rg|ag|ack)\b|\bgrep\b[^|;&\n]*\s-\w*[rR]|\bfind\b|\btree\b")
GIT_CMD = re.compile(r"\bgit\s+[^|;&\n]*")
LEADING_CD = re.compile(r"^\s*cd\s+(\S+)\s*(?:&&|;)")
GIT_MESSAGE = re.compile(r"\s-m\s*(\"(?:[^\"\\]|\\.)*\"|'[^']*')", re.S)
HEREDOC = re.compile(
    r"^(?P<head>[^\n]*<<-?\s*['\"]?(?P<tag>\w+)['\"]?[^\n]*)\n.*?\n[ \t]*(?P=tag)[ \t]*$", re.M | re.S
)
DATA_SINK = re.compile(r"\b(cat|tee|git)\b")
SCRIPT_RUNNER = re.compile(r"\b(python3?|perl|bash|sh|zsh|awk|gawk|sed|ruby|node)\b")
# Not right after `$`, `{` or a word character: `$S/ui.xml` names a variable's directory, not `S/ui.xml`.
PATH_TOKEN = re.compile(r"(?<![\w${.~/+@*-])[\w.~/+@*-]*[/.][\w.~/+@*-]*")

WORKTREE_HINT = (
    "Blocked: worktrees are not used in this project. Android Studio and its MCP servers index only "
    "the main checkout (" + INDEXED_ROOT + "); work there on a feature branch, one agent at a time."
)

HINT = (
    "Blocked by the IDE-first rule (CLAUDE.md): sources of the main checkout are searched, read "
    "and checked through the IDE MCP servers, not shell text tools. Use jetbrains-index "
    "(ide_find_class, ide_find_definition, ide_find_references, ide_find_file, ide_search_text, "
    "ide_diagnostics, ide_refactor_*), the studio MCP for build/tests, and Read + Edit for file "
    "contents. Build output, worktrees, the Nix store and /tmp stay free."
)


def indexed_source(path: str, cwd: str) -> bool:
    """True if [path] lies in the IDE-indexed checkout and outside generated/tool dirs."""
    full = os.path.realpath(os.path.join(cwd, os.path.expanduser(path)))
    if full != INDEXED_ROOT and not full.startswith(INDEXED_ROOT + "/"):
        return False
    return not FREE_DIR.search(full[len(INDEXED_ROOT):])


def strip_data(cmd: str) -> str:
    """Drops text that is data, not paths: commit messages and heredocs fed to cat, tee or git."""
    cmd = GIT_MESSAGE.sub(" ", cmd)

    def heredoc(match: re.Match) -> str:
        head = match.group("head")
        return head if DATA_SINK.search(head) and not SCRIPT_RUNNER.search(head) else match.group(0)

    return HEREDOC.sub(heredoc, cmd)


def bash_hits_sources(cmd: str, cwd: str) -> bool:
    cmd = strip_data(cmd)
    leading_cd = LEADING_CD.match(cmd)
    if leading_cd:
        cwd = os.path.realpath(os.path.join(cwd, os.path.expanduser(leading_cd.group(1).strip("'\""))))
    if not TEXT_TOOL.search(cmd):
        return False
    rest = GIT_CMD.sub(" ", cmd)
    paths = [t for t in PATH_TOKEN.findall(rest) if not re.fullmatch(r"[.\d]+|-.*|\*?\.\w+", t)]
    for token in paths:
        full = os.path.realpath(os.path.join(cwd, os.path.expanduser(token)))
        if indexed_source(token, cwd) and (SOURCE_EXT.search(token) or (os.path.isdir(full) and full != INDEXED_ROOT)):
            return True
    if RECURSIVE_SEARCH.search(rest) and indexed_source(".", cwd):
        explicit_elsewhere = [p for p in paths if p.startswith(("/", "~")) and not indexed_source(p, cwd)]
        return not explicit_elsewhere
    return False


def linked_worktrees() -> list:
    try:
        out = subprocess.run(
            ["git", "-C", INDEXED_ROOT, "worktree", "list", "--porcelain"],
            capture_output=True, text=True, timeout=2, check=True,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    roots = [os.path.realpath(l[len("worktree "):]) for l in out.splitlines() if l.startswith("worktree ")]
    return [r for r in roots if r != INDEXED_ROOT]


def in_linked_worktree(path: str, cwd: str) -> bool:
    full = os.path.realpath(os.path.join(cwd, os.path.expanduser(path)))
    return any(full == r or full.startswith(r + "/") for r in linked_worktrees())


def uses_worktree(tool: str, args: dict, cwd: str) -> bool:
    if tool == "EnterWorktree":
        return True
    if tool in ("Agent", "Task"):
        return args.get("isolation") == "worktree"
    if tool == "Bash":
        return bool(re.search(r"\bgit\s+(-C\s+\S+\s+)?worktree\s+add\b", args.get("command", "")))
    if tool in ("Edit", "Write", "NotebookEdit"):
        path = args.get("file_path") or args.get("notebook_path") or ""
        return bool(path) and in_linked_worktree(path, cwd)
    return False


def gradle_beyond_meta_tasks(cmd: str) -> bool:
    """True if a Gradle call runs a real task rather than a harmless meta task such as `help`."""
    command_lines = HEREDOC.sub(lambda heredoc: heredoc.group("head"), strip_data(cmd))
    for match in GRADLE_CALL.finditer(command_lines):
        tokens = match.group("args").split()
        tasks, skip = [], False
        for token in tokens:
            if skip:
                skip = False
            elif token in GRADLE_VALUE_OPTIONS:
                skip = True
            elif not token.startswith("-") and not token.isdigit():
                tasks.append(token)
        if any(task not in GRADLE_ALLOWED_TASKS for task in tasks):
            return True
    return False


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def log(data: dict, decision: str) -> None:
    args = data.get("tool_input") or {}
    entry = {
        "ts": datetime.datetime.now().isoformat(timespec="seconds"),
        "decision": decision,
        "session": data.get("session_id"),
        "agent": data.get("agent_id") or data.get("agent_type") or "main",
        "tool": data.get("tool_name"),
        "input": str(args.get("command") or args.get("pattern") or args.get("path") or args.get("file_path") or args.get("description") or "")[:300],
        "cwd": data.get("cwd"),
    }
    try:
        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        with open(LOG, "a") as f:
            f.write(json.dumps(entry) + "\n")
    except OSError:
        pass


def main() -> int:
    data = json.load(sys.stdin)
    tool = data.get("tool_name", "")
    args = data.get("tool_input") or {}
    cwd = data.get("cwd") or os.getcwd()

    if uses_worktree(tool, args, cwd):
        log(data, "blocked-worktree")
        print(WORKTREE_HINT, file=sys.stderr)
        return 2

    edited = args.get("file_path") or args.get("notebook_path") or ""
    if tool in EDITING_TOOLS and edited and indexed_source(edited, cwd):
        if not port_open(STUDIO_PORT):
            log(data, "allowed-edit-studio-down")
            return 0
        log(data, "blocked-edit")
        print(EDIT_HINT, file=sys.stderr)
        return 2

    if tool == "Bash" and gradle_beyond_meta_tasks(args.get("command", "")):
        if not port_open(STUDIO_PORT):
            log(data, "allowed-gradle-studio-down")
            return 0
        log(data, "blocked-gradle")
        print(GRADLE_HINT, file=sys.stderr)
        return 2

    if tool == "Bash":
        hits = bash_hits_sources(args.get("command", ""), cwd)
    elif tool in ("Grep", "Glob"):
        hits = indexed_source(args.get("path") or ".", cwd)
    else:
        hits = False
    if not hits:
        return 0
    if not port_open(IDE_PORT):
        log(data, "allowed-ide-down")
        return 0
    log(data, "blocked")
    print(HINT, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
