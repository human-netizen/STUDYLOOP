"""The hostile-input suite. This file, not the mp4, is Phase 21.3's deliverable.

Every test below is an attack, and every assertion names the layer that stopped it. That naming is
the whole design of the suite: a test that only knew "the render failed" could not tell a blocked
import from a syntax error, and a sandbox whose tests cannot tell those apart is a sandbox nobody
can reason about. When one of these starts failing, the assertion says which layer moved.

Two groups, and they run in different places.

* **Layer 1** is pure AST work. It needs no Manim, no ffmpeg and no POSIX, so it runs anywhere —
  including on the Windows machine this project is developed on, where the rest of this service
  cannot run at all.
* **Layers 2 and 3** fork processes and set rlimits. They are skipped off POSIX, and they are the
  reason the image carries ``util-linux``.

Run them where the worker lives::

    docker compose --profile video run --rm --entrypoint pytest video-worker -v

**That must be a container with an init process at PID 1**, which is why the compose service sets
``init: true``. These fixtures deliberately orphan processes; something has to reap them, or the
zombies keep counting against ``RLIMIT_NPROC`` and a later test fails to fork for reasons that have
nothing to do with what it is testing. Running the image by hand needs ``docker run --init`` and
``--security-opt seccomp=unconfined`` for the same reasons the compose service does.
"""

from __future__ import annotations

import os
import sys
import textwrap
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import sandbox  # noqa: E402

posix_only = pytest.mark.skipif(os.name != "posix", reason="layer 2 needs fork, setsid and rlimits")


def scene(body: str) -> str:
    """A well-formed generated scene with the given ``construct`` body."""

    return "from manim import *\n\nclass GeneratedScene(Scene):\n    def construct(self):\n" + \
        textwrap.indent(textwrap.dedent(body).strip(), " " * 8) + "\n"


# ── layer 1 · the allow-list ────────────────────────────────────────────────────────────────


def test_an_ordinary_scene_is_allowed():
    """The suite has to be able to say yes, or every other test below is trivially satisfied."""

    verdict = sandbox.check(scene("""
        title = Text("Breadth-first search").scale(0.8)
        self.play(Write(title), run_time=1.5)
        for step in range(3):
            dot = Dot(point=RIGHT * step)
            self.play(FadeIn(dot), run_time=0.4)
        self.wait(1)
    """))
    assert verdict.ok, verdict.detail
    assert verdict.layer == sandbox.LAYER_ALLOWED


def test_a_second_import_is_rejected():
    code = "from manim import *\nimport os\n\nclass GeneratedScene(Scene):\n    def construct(self):\n        pass\n"
    verdict = sandbox.check(code)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_dunder_import_is_rejected():
    """``__import__("os")`` is the shortest way around a rule that only bans the keyword."""

    verdict = sandbox.check(scene('module = __import__("os")'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED
    assert "__import__" in verdict.detail


def test_getattr_on_builtins_is_rejected():
    verdict = sandbox.check(scene('reader = getattr(__builtins__, "open")'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_open_is_rejected():
    verdict = sandbox.check(scene('handle = open("/etc/passwd")'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED
    assert "open" in verdict.detail


def test_the_subclasses_escape_is_rejected():
    """``().__class__.__bases__[0].__subclasses__()`` — the canonical Python sandbox escape.

    It is caught by the rule about spelling rather than by a rule about this expression: no
    attribute starting with two underscores, full stop. A blocklist that named ``__subclasses__``
    would be one attribute behind the next published escape.
    """

    verdict = sandbox.check(scene('found = ().__class__.__bases__[0].__subclasses__()'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED
    # Which of the four dunders it names is an artefact of walk order and not worth pinning; that
    # it names one of them is the assertion, because that is what makes the rejection explainable.
    assert "two underscores" in verdict.detail


def test_eval_is_rejected():
    verdict = sandbox.check(scene('value = eval("1 + 1")'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_while_loop_is_rejected():
    """An unbounded loop is a wall-clock attack that costs the attacker one line."""

    verdict = sandbox.check(scene("""
        while True:
            self.wait(0.1)
    """))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED
    assert "While" in verdict.detail


def test_try_except_is_rejected():
    """A generated `try` exists to swallow the error the fix loop wants to read."""

    code = ("from manim import *\n\nclass GeneratedScene(Scene):\n"
            "    def construct(self):\n"
            "        try:\n            self.wait(1)\n"
            "        except Exception:\n            pass\n")
    verdict = sandbox.check(code)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_decorators_are_rejected():
    code = ("from manim import *\n\nclass GeneratedScene(Scene):\n"
            "    @staticmethod\n    def helper():\n        pass\n"
            "    def construct(self):\n        pass\n")
    verdict = sandbox.check(code)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_file_paths_are_rejected():
    """Not a security rule — a usability one, and the docstring in sandbox.py says so.

    Nothing in the allow-list can open a file. This exists so that a scene reaching for an asset
    fails with a sentence the fix loop can act on, instead of failing four layers deep in Manim.
    """

    verdict = sandbox.check(scene('logo = SVGMobject("assets/logo.svg")'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_a_url_is_rejected():
    verdict = sandbox.check(scene('label = Text("https://example.com/data")'))
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_module_level_code_is_rejected():
    code = ("from manim import *\n\nconfig.background_color = BLACK\n\n"
            "class GeneratedScene(Scene):\n    def construct(self):\n        pass\n")
    verdict = sandbox.check(code)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_a_second_class_is_rejected():
    code = ("from manim import *\n\nclass GeneratedScene(Scene):\n    def construct(self):\n        pass\n\n"
            "class Other(Scene):\n    def construct(self):\n        pass\n")
    verdict = sandbox.check(code)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_REJECTED


def test_a_syntax_error_is_compile_not_rejected():
    """The layers have to be distinguishable, or the fix loop cannot decide whether to retry.

    A syntax error is worth another model call; a forbidden construct usually is not.
    """

    verdict = sandbox.check("from manim import *\n\nclass GeneratedScene(Scene)\n")
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_COMPILE


def test_a_docstring_does_not_break_the_shape():
    """Models add a comment even when told not to, and that is not worth a fallback."""

    code = ('"""A scene about graphs."""\nfrom manim import *\n\n'
            'class GeneratedScene(Scene):\n    def construct(self):\n        self.wait(1)\n')
    assert sandbox.check(code).ok


# ── layer 2 · the process ───────────────────────────────────────────────────────────────────


def run_python(tmp_path: Path, source: str, budget: float = 15.0,
               isolate_network: bool = False) -> sandbox.Verdict:
    """Run a script through layers 2 and 3, skipping layer 1.

    Deliberately skipping it: these fixtures are what happens *if* the allow-list has already been
    defeated. A suite that only tested layer 1 would be testing the layer that is easiest to get
    right.
    """

    script = tmp_path / "attack.py"
    script.write_text(textwrap.dedent(source), encoding="utf-8")
    return sandbox.run_isolated(
        [sandbox.python_executable(), str(script)], tmp_path, budget, isolate_network)


@posix_only
def test_an_ordinary_script_succeeds(tmp_path):
    verdict = run_python(tmp_path, "print('hello')")
    assert verdict.ok, verdict.detail


@posix_only
def test_an_infinite_loop_is_killed_on_the_wall_clock(tmp_path):
    verdict = run_python(tmp_path, "while True:\n    pass\n", budget=5.0)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_TIMEOUT


@posix_only
def test_a_ten_gigabyte_write_is_killed(tmp_path):
    """RLIMIT_FSIZE. The scratch volume is shared with every other job on the machine."""

    verdict = run_python(tmp_path, """
        with open('big', 'wb') as sink:
            block = b'x' * (1024 * 1024)
            for _ in range(10 * 1024):
                sink.write(block)
        print('wrote 10GB')
    """, budget=60.0)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_KILLED
    assert "wrote 10GB" not in verdict.detail


@posix_only
def test_a_fork_bomb_is_killed(tmp_path):
    """RLIMIT_NPROC, which is why the render is a separate uid from anything that matters.

    The bomb is real rather than simulated — the limit is what makes running it safe, and a
    simulated one would be testing the simulation.
    """

    verdict = run_python(tmp_path, """
        import os
        while True:
            os.fork()
    """, budget=10.0)
    assert not verdict.ok
    assert verdict.layer in (sandbox.LAYER_KILLED, sandbox.LAYER_TIMEOUT)


@posix_only
def test_the_environment_is_empty(tmp_path):
    """No API key, no database URL, nothing inherited.

    The claim this asserts is bigger than the assertion: the backend makes every model call, so
    there is no key in this process to leak even before the sandbox. If a future change gives this
    service a credential, this test is what fails.
    """

    verdict = run_python(tmp_path, """
        import os
        print(sorted(os.environ))
    """)
    assert verdict.ok, verdict.detail
    assert "'PATH'" in verdict.detail
    for secret in ("API_KEY", "COHERE", "GOOGLE", "DB_", "JWT", "TOKEN", "PASSWORD"):
        assert secret not in verdict.detail


@posix_only
def test_the_whole_process_group_is_killed(tmp_path):
    """Killing the child alone is the classic mistake.

    Manim spawns ffmpeg. A timeout that kills only the direct child leaves an encoder writing into
    a directory the caller has already decided is finished — and the file it produces arrives after
    the job has been marked failed. The grandchild here touches a file twice a second; after the
    kill, that file must stop changing.
    """

    beacon = tmp_path / "beacon"
    verdict = run_python(tmp_path, f"""
        import subprocess, time
        subprocess.Popen([
            'sh', '-c',
            'while true; do date +%s%N > {beacon.name}; sleep 0.2; done'
        ])
        time.sleep(60)
    """, budget=4.0)
    assert not verdict.ok
    assert verdict.layer == sandbox.LAYER_TIMEOUT

    assert beacon.exists(), "the grandchild never started, so this proves nothing"
    first = beacon.read_text(encoding="utf-8")
    time.sleep(1.5)
    assert beacon.read_text(encoding="utf-8") == first, "the grandchild outlived the process group"


# ── layer 3 · the network ───────────────────────────────────────────────────────────────────


@posix_only
def test_network_isolation_is_reported_honestly():
    """The one layer that can be silently absent.

    An unprivileged container on a host that forbids user namespaces cannot unshare a network. The
    honest behaviour is to say so — the health endpoint does, and the allow-list is then the only
    barrier, which is why the allow-list is an allow-list.
    """

    assert isinstance(sandbox.network_isolation_available(), bool)


@pytest.mark.skipif(
    os.name != "posix" or not sandbox.network_isolation_available(),
    reason="this kernel does not allow unprivileged network namespaces",
)
def test_a_socket_cannot_reach_the_network(tmp_path):
    verdict = run_python(tmp_path, """
        import socket
        socket.create_connection(('1.1.1.1', 80), timeout=5)
        print('connected')
    """, budget=30.0, isolate_network=True)
    assert not verdict.ok
    assert "connected" not in verdict.detail
