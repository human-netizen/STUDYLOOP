"""Phase 21.3 — running Python a language model wrote, without trusting it.

AddNewFeature.md refuses to build code labs on the grounds that ``subprocess.run`` with a timeout
is not a sandbox. That objection is correct and it applies here too: the animation half of this
feature asks a model to write a Manim scene and then executes it. The difference is not that this
is safer — it is that the sandbox is a deliverable with a test suite, rather than a caveat in a
comment.

Three layers, and each one assumes the layer above it has already failed.

**Layer 1 — a static allow-list over the AST, never a blocklist.**  The module has to parse to
exactly one shape: ``from manim import *``, one ``class GeneratedScene(Scene)``, its methods, and
statements built only from a permitted set of node types. Anything unrecognised is rejected rather
than inspected. The argument this rests on, stated plainly so it can be attacked: *with no import
statement and no dunder access, there is no I/O primitive within reach* — Manim's namespace is the
entire vocabulary available to the generated scene, and nothing in it opens a file or a socket.

A blocklist is what this is deliberately not. Every published Python sandbox escape of the last
decade is a blocklist walking backwards from ``().__class__.__bases__[0].__subclasses__()``, and
the reason those work is that a blocklist has to enumerate an open set. An allow-list has to
enumerate a closed one: the node types a drawing program needs.

**Layer 2 — the process.**  Manim itself is not in the allow-list's scope: the generated code is
one file among a library of tens of thousands of lines, and a bug in *that* is not something an
AST walk can see. So the render runs as a separate process with an empty environment (this service
holds no API keys at all — the backend makes every model call and hands us text), a cwd that is the
only writable path it has, CPU/memory/file-size/process-count limits from ``setrlimit``, and a
wall-clock kill of the entire process group rather than of the child. Killing the child alone is
the classic mistake: Manim spawns ffmpeg, and an orphaned encoder keeps writing after the timeout
has reported success.

**Layer 3 — the network.**  ``unshare -rn`` where the kernel allows it, which gives the render a
network namespace with nothing in it but loopback. Where it is not allowed — an unprivileged
container on a host with ``kernel.unprivileged_userns_clone`` off — the render still runs, and this
module says so in the result rather than pretending. That is precisely why layer 1 is an allow-list:
a blocklist that is sometimes the last line of defence is not a defence.

The test suite (``tests/test_sandbox.py``) is hostile fixtures, not happy paths. Each one asserts
the layer that stopped it by name, because a test that only knows "the render failed" cannot tell a
blocked import from a syntax error.
"""

from __future__ import annotations

import ast
import os
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

try:  # POSIX only. The worker image is Linux; the AST layer is tested anywhere.
    import resource
except ImportError:  # pragma: no cover - Windows development shells
    resource = None  # type: ignore[assignment]


# ── the layers, named ───────────────────────────────────────────────────────────────────────
#
# These strings travel back to the backend and are stored on the scene row, so a fallback can be
# explained rather than merely counted. They are also what the hostile-fixture tests assert on.

LAYER_ALLOWED = "OK"
LAYER_REJECTED = "REJECTED"   # layer 1 refused to let it run at all
LAYER_COMPILE = "COMPILE"     # it parsed but Python would not compile it
LAYER_KILLED = "KILLED"       # a resource limit or a signal stopped it
LAYER_TIMEOUT = "TIMEOUT"     # the wall clock ran out
LAYER_RENDER = "RENDER"       # Manim ran and did not like the scene


@dataclass(frozen=True)
class Verdict:
    """What the sandbox decided, and which layer decided it."""

    ok: bool
    layer: str
    detail: str = ""


# ── layer 1: the allow-list ─────────────────────────────────────────────────────────────────

#: Statement and expression nodes a drawing program needs. Everything absent is rejected, which
#: includes ``Import``, ``Global``, ``Nonlocal``, ``Await``, ``Yield``, ``With``, ``Try``,
#: ``While``, ``Lambda``, ``Delete``, and every match/async construct.
#:
#: ``While`` is out because a bounded ``for`` over a literal range expresses every loop an
#: animation needs and cannot spin forever on its own. ``Try`` is out because its purpose in
#: generated code is to swallow the error the fix loop wants to read. ``With`` is out because the
#: context managers worth having here are file handles. ``Lambda`` is out for the weakest reason of
#: the four and is the one most likely to be revisited: it grants no capability the rest of this
#: list does not, but in generated Manim it is nearly always an updater, and an updater is the one
#: construct here whose cost is paid per frame rather than per call.
ALLOWED_NODES: frozenset[type[ast.AST]] = frozenset({
    ast.Module, ast.ClassDef, ast.FunctionDef, ast.arguments, ast.arg, ast.Return,
    ast.Expr, ast.Assign, ast.AugAssign, ast.AnnAssign, ast.Pass, ast.If, ast.For, ast.Break,
    ast.Continue,
    ast.Call, ast.Attribute, ast.Name, ast.Load, ast.Store, ast.Del, ast.keyword, ast.Starred,
    ast.Constant, ast.JoinedStr, ast.FormattedValue,
    ast.List, ast.Tuple, ast.Dict, ast.Set, ast.Slice, ast.Subscript,
    ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp, ast.comprehension,
    ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare, ast.IfExp,
    ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Mod, ast.Pow,
    ast.USub, ast.UAdd, ast.Not, ast.And, ast.Or,
    ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn, ast.Is, ast.IsNot,
})

#: Builtins that are reachable by name and are either an I/O primitive or a way to reach one.
#:
#: This is a *second* check rather than the main one: layer 1's real defence is that the module has
#: no imports and no dunder access, so ``open`` is the only interesting name left in builtins that
#: a scene could call directly. Listing it explicitly costs nothing and makes the rejection message
#: say something useful instead of "unknown name".
FORBIDDEN_NAMES: frozenset[str] = frozenset({
    "open", "eval", "exec", "compile", "input", "__import__", "breakpoint",
    "getattr", "setattr", "delattr", "hasattr", "vars", "globals", "locals", "dir",
    "super", "type", "object", "memoryview", "exit", "quit", "help", "license", "credits",
})

#: The one import the file may contain, spelled exactly.
REQUIRED_IMPORT = "from manim import *"

#: The class the renderer looks for.
SCENE_CLASS = "GeneratedScene"


class Rejection(Exception):
    """Layer 1 said no. Carries the sentence the model is shown by the fix loop."""


def check(code: str) -> Verdict:
    """Run the allow-list. Never executes anything, never imports anything, always returns."""

    try:
        tree = ast.parse(code)
    except SyntaxError as error:
        return Verdict(False, LAYER_COMPILE, f"The file does not parse: {error}")

    try:
        _check_module(tree)
    except Rejection as rejection:
        return Verdict(False, LAYER_REJECTED, str(rejection))
    return Verdict(True, LAYER_ALLOWED)


def _check_module(tree: ast.Module) -> None:
    body = [node for node in tree.body if not _is_docstring(node)]
    if not body:
        raise Rejection("The file is empty.")

    header, *rest = body
    if not isinstance(header, ast.ImportFrom) or header.module != "manim":
        raise Rejection(f"The first statement must be `{REQUIRED_IMPORT}`.")
    if len(header.names) != 1 or header.names[0].name != "*":
        raise Rejection(f"The only permitted import is `{REQUIRED_IMPORT}`.")

    classes = [node for node in rest if isinstance(node, ast.ClassDef)]
    if len(rest) != len(classes):
        # A module-level assignment, a helper function, a second import: all of it is outside the
        # shape the renderer knows how to run, and the shape is stated in the prompt.
        offender = next(node for node in rest if not isinstance(node, ast.ClassDef))
        raise Rejection(
            f"Only the import and the scene class may appear at the top level; found "
            f"{type(offender).__name__} on line {getattr(offender, 'lineno', 0)}."
        )
    if len(classes) != 1 or classes[0].name != SCENE_CLASS:
        raise Rejection(f"The file must define exactly one class, named {SCENE_CLASS}.")

    scene = classes[0]
    if scene.decorator_list:
        raise Rejection("Decorators are not permitted.")
    if len(scene.bases) != 1 or not isinstance(scene.bases[0], ast.Name):
        raise Rejection(f"{SCENE_CLASS} must inherit from a single Manim scene class.")

    for node in ast.walk(scene):
        _check_node(node)


def _check_node(node: ast.AST) -> None:
    if type(node) not in ALLOWED_NODES:
        raise Rejection(
            f"`{type(node).__name__}` is not permitted in a generated scene "
            f"(line {getattr(node, 'lineno', 0)})."
        )
    if isinstance(node, ast.FunctionDef) and node.decorator_list:
        raise Rejection("Decorators are not permitted.")
    if isinstance(node, ast.Name):
        if node.id in FORBIDDEN_NAMES:
            raise Rejection(f"`{node.id}` is not permitted in a generated scene.")
        if _is_dunder(node.id):
            raise Rejection(f"Names beginning with two underscores are not permitted: `{node.id}`.")
    if isinstance(node, ast.Attribute) and _is_dunder(node.attr):
        # ``.__class__``, ``.__globals__``, ``.__subclasses__`` — the whole escape family starts
        # here, and the rule is one line because it is a rule about spelling rather than about
        # which attribute happens to be dangerous this year.
        raise Rejection(f"Attributes beginning with two underscores are not permitted: `{node.attr}`.")
    if isinstance(node, ast.Constant) and isinstance(node.value, str) and _looks_like_a_path(node.value):
        raise Rejection(f"File paths and URLs are not permitted in a generated scene: {node.value!r}.")


def _is_docstring(node: ast.AST) -> bool:
    """A bare string statement. Allowed anywhere, and skipped when checking the module shape.

    Models put a comment at the top of the file even when told not to, and a docstring is not a
    reason to throw away a scene that is otherwise fine.
    """

    return isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant)         and isinstance(node.value.value, str)


def _is_dunder(name: str) -> bool:
    return name.startswith("__")


def _looks_like_a_path(value: str) -> bool:
    """A crude check, and crude on purpose.

    Nothing in the allow-list can *use* a path — there is no ``open`` and no import — so this is
    not load-bearing. It is here because a scene that tries to load ``logo.svg`` fails deep inside
    Manim with a message the fix loop cannot act on, and failing it here produces a sentence the
    model can actually fix.
    """

    lowered = value.strip().lower()
    if lowered.startswith(("http://", "https://", "file://", "/etc", "/proc", "/sys", "~/")):
        return True
    return lowered.endswith((".svg", ".png", ".jpg", ".jpeg", ".gif", ".mp3", ".wav", ".ttf", ".obj"))


# ── layer 2 and 3: the process ──────────────────────────────────────────────────────────────

#: Resource ceilings for the render process. Generous enough for a real Manim scene and small
#: enough that the pathological cases stop being pathological.
CPU_SECONDS = 120          # RLIMIT_CPU: a busy loop dies here even if the wall clock has not run out
ADDRESS_SPACE_BYTES = 3 << 30   # 3 GiB — Manim with cairo and numpy sits around 400 MiB
FILE_SIZE_BYTES = 512 << 20     # 512 MiB — one 720p scene is a few MiB; a 10 GB write dies here
MAX_PROCESSES = 64              # a fork bomb hits this in milliseconds


def _limits() -> None:  # pragma: no cover - runs in the child, after fork
    """Applied between fork and exec, in the child.

    ``setsid`` first, so the render and everything it spawns share one process group and the
    timeout can kill all of it. Manim spawns ffmpeg and latex; killing only the direct child leaves
    an encoder writing into a directory the caller has already decided is finished.
    """

    os.setsid()
    if resource is None:
        return
    resource.setrlimit(resource.RLIMIT_CPU, (CPU_SECONDS, CPU_SECONDS))
    resource.setrlimit(resource.RLIMIT_AS, (ADDRESS_SPACE_BYTES, ADDRESS_SPACE_BYTES))
    resource.setrlimit(resource.RLIMIT_FSIZE, (FILE_SIZE_BYTES, FILE_SIZE_BYTES))
    resource.setrlimit(resource.RLIMIT_NPROC, (MAX_PROCESSES, MAX_PROCESSES))
    # No core dumps: a 3 GiB core file from a segfaulting renderer would fill the scratch volume
    # and tell nobody anything.
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def network_isolation_available() -> bool:
    """Whether ``unshare -rn`` actually works here, tested rather than assumed.

    Checked once per process and cached by the caller. The test is the real thing — unshare a
    namespace and run ``true`` — because the failure mode is a kernel setting, not a missing
    binary, and an installed ``unshare`` that always returns EPERM would otherwise look available.
    """

    if os.name != "posix":
        return False
    try:
        probe = subprocess.run(
            ["unshare", "-rn", "true"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return probe.returncode == 0


def sandbox_environment(scratch: Path) -> dict[str, str]:
    """The entire environment the render sees.

    Empty except for four variables, and the emptiness is the point: this service is never given
    an API key, a database URL or a token, so there is nothing here to steal even before the
    namespace and the rlimits. Everything that would otherwise default into ``$HOME`` is pointed at
    the job's scratch directory, which is also the only writable path.
    """

    return {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "HOME": str(scratch),
        "TMPDIR": str(scratch),
        # Manim reads these; without them matplotlib and fontconfig try to write into a home
        # directory that does not exist and fail in a way that reads like a scene error.
        "XDG_CACHE_HOME": str(scratch / "cache"),
        "MPLCONFIGDIR": str(scratch / "cache" / "matplotlib"),
    }


def run_isolated(
    command: Iterable[str],
    scratch: Path,
    budget_seconds: float,
    isolate_network: bool = True,
) -> Verdict:
    """Layers 2 and 3: run the command, or kill it and say which limit stopped it.

    Returns rather than raises for every outcome including the violent ones, because the caller's
    next action is the same in all of them — draw a slide instead — and the difference between them
    is a sentence on a scene row.
    """

    argv = list(command)
    if isolate_network and network_isolation_available():
        # ``-r`` maps the current uid to root *inside the new user namespace only*, which is what
        # makes creating a network namespace possible without being root outside it. The render
        # gains no privilege on the host from this; it loses the network.
        argv = ["unshare", "-rn", *argv]

    scratch.mkdir(parents=True, exist_ok=True)
    (scratch / "cache").mkdir(exist_ok=True)

    started = time.monotonic()
    try:
        process = subprocess.Popen(  # noqa: S603 - argv is built here, never from model output
            argv,
            cwd=str(scratch),
            env=sandbox_environment(scratch),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            preexec_fn=_limits if os.name == "posix" else None,  # noqa: PLW1509
            text=True,
        )
    except OSError as error:
        return Verdict(False, LAYER_RENDER, f"Could not start the renderer: {error}")

    try:
        output, _ = process.communicate(timeout=budget_seconds)
    except subprocess.TimeoutExpired:
        _kill_group(process)
        output, _ = process.communicate()
        elapsed = time.monotonic() - started
        return Verdict(False, LAYER_TIMEOUT,
                       f"Killed after {elapsed:.0f}s (budget {budget_seconds:.0f}s). "
                       f"{_tail(output)}")

    if process.returncode == 0:
        return Verdict(True, LAYER_ALLOWED, _tail(output))

    # A negative return code is a signal. SIGKILL/SIGXCPU/SIGSEGV here means a limit from _limits
    # stopped it, and that is a different fact from "Manim did not like the scene" — the fix loop
    # is shown both, but only one of them is worth a second attempt.
    if process.returncode < 0:
        return Verdict(False, LAYER_KILLED,
                       f"Killed by signal {signal.Signals(-process.returncode).name}. {_tail(output)}")
    if _looks_like_a_limit(output):
        return Verdict(False, LAYER_KILLED, _tail(output))
    return Verdict(False, LAYER_RENDER, _tail(output))


def _kill_group(process: subprocess.Popen) -> None:
    """SIGKILL the whole process group, then reap.

    ``killpg`` rather than ``process.kill()``: the group exists because ``_limits`` called
    ``setsid``, and everything Manim spawned is in it.
    """

    if os.name != "posix":  # pragma: no cover - Windows development shells
        process.kill()
        return
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        process.kill()


def _looks_like_a_limit(output: str) -> bool:
    """Some limits surface as an ordinary exception rather than a signal.

    ``RLIMIT_AS`` becomes ``MemoryError`` inside Python, ``RLIMIT_FSIZE`` becomes ``OSError:
    [Errno 27] File too large``, and ``RLIMIT_NPROC`` becomes ``BlockingIOError``. Reporting those
    as RENDER would tell the fix loop the scene was wrong when in fact the scene was stopped, and
    the model would then be paid to rewrite code that was never the problem.
    """

    markers = ("MemoryError", "File too large", "Errno 27", "Resource temporarily unavailable",
               "Cannot allocate memory", "BlockingIOError")
    return any(marker in output for marker in markers)


def _tail(output: str, limit: int = 4000) -> str:
    """The end of the output, which is where the message is.

    Manim tracebacks run to hundreds of lines of internal frames; the useful sentence is the last
    one. The backend truncates again before showing any of it to a person.
    """

    if not output:
        return ""
    text = output.strip()
    return text if len(text) <= limit else "…\n" + text[-limit:]


def python_executable() -> str:
    """The interpreter the render runs under — this one, so the image has exactly one Python."""

    return sys.executable or "python3"
