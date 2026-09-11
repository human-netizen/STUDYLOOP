"""The blank-scene check: the one failure Manim itself is happy about.

Layer 1 reads the code and layer 2 watches the process, and neither of them can see this. A scene
that shifts its objects off the frame, scales them to nothing, or fades them out in the first
second is valid Python, uses only allowed names, exits 0, and produces a file. What it produces is
black frames — and the composer then holds that last black frame for as long as the narration keeps
talking, which turns a bad ten-second scene into half a minute of black with a voice over it.

These tests build their clips with ffmpeg rather than with Manim, because what is under test is the
measurement, not the animation: a black clip must be caught and a bright one must not.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import renderer  # noqa: E402

# `shutil.which` rather than a trial run: asking ffmpeg for its version to find out whether
# ffmpeg exists raises FileNotFoundError when it does not, and `check=False` suppresses only a
# non-zero exit, not a missing executable. At module scope a raise is a collection error rather
# than a skip, so the module that meant to step aside takes the whole run down with it.
needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="the blank check measures with ffmpeg, which lives in the worker image",
)


#: An empty frame and a busy one, as lavfi sources. `color` takes its value as `c=`, `testsrc`
#: takes none at all — the separator differs between the two, which is worth spelling out once
#: here rather than getting wrong in four places.
BLACK = "color=c=black:size=320x180:rate=10"
PICTURE = "testsrc=size=320x180:rate=10"


def clip(path: Path, source: str, seconds: float = 4.0) -> Path:
    """A short silent clip from an ffmpeg lavfi source."""

    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", source,
         "-t", f"{seconds}", "-pix_fmt", "yuv420p", str(path)],
        capture_output=True, check=True,
    )
    return path


@needs_ffmpeg
def test_a_black_clip_is_measured_as_blank(tmp_path):
    seconds = renderer._blank_seconds(clip(tmp_path / "black.mp4", BLACK))
    assert seconds > 3.0


@needs_ffmpeg
def test_a_nearly_empty_frame_with_something_drawn_on_it_is_not_blank(tmp_path):
    """The regression that cost a working scene, as a test.

    A Manim frame is line art on black: outlines, an arrow, two labels — well under two per cent of
    the pixels. ffmpeg's default `pic_th` of 0.98 calls that a black frame, so the first version of
    this check rejected a scene that showed exactly what it was supposed to. The box below is a
    quarter of one per cent of the frame, which is the same order as a real one.
    """

    path = tmp_path / "sparse.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", BLACK, "-t", "4",
         "-vf", "drawbox=x=150:y=85:w=12:h=12:color=white:t=fill",
         "-pix_fmt", "yuv420p", str(path)],
        capture_output=True, check=True,
    )

    assert renderer._blank_seconds(path) == 0.0


@needs_ffmpeg
def test_a_clip_with_a_picture_in_it_is_not(tmp_path):
    """The other half, and the one that matters: a real scene must not be thrown away."""

    seconds = renderer._blank_seconds(clip(tmp_path / "picture.mp4", PICTURE))
    assert seconds == 0.0


@needs_ffmpeg
def test_a_dark_opening_is_not_enough_to_fail_a_scene(tmp_path):
    """Fading in from black is ordinary film-making, which is why the limit is two thirds.

    A second of black in front of three seconds of picture is 25% — well under `BLANK_LIMIT`, and
    a scene like that has to survive.
    """

    black = clip(tmp_path / "lead-in.mp4", BLACK, seconds=1.0)
    picture = clip(tmp_path / "content.mp4", PICTURE, seconds=3.0)
    listing = tmp_path / "parts.txt"
    listing.write_text(f"file '{black}'\nfile '{picture}'\n", encoding="utf-8")
    joined = tmp_path / "joined.mp4"
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-c", "copy", str(joined)], capture_output=True, check=True)

    assert renderer._blank_seconds(joined) / 4.0 < renderer.BLANK_LIMIT


def test_the_measurement_says_zero_when_it_cannot_measure(tmp_path):
    """An unreadable answer has to mean "keep the scene".

    A false negative costs one dull scene. A false positive throws away a scene that was fine and
    spends two model calls trying to fix what was never broken.
    """

    assert renderer._blank_seconds(tmp_path / "does-not-exist.mp4") == 0.0


# ── how much of the frame the scene uses ────────────────────────────────────────────────────
#
# The failure the blank check cannot see. A scene can draw a correct, legible, perfectly bright
# animation inside a hundredth of the frame and every check above it passes: it is not black, it
# renders, it exits 0. Measured on the first film this feature produced unaided, the drawing
# occupied 0.6% of a 1280x720 frame — a row of 30-pixel numbers along the bottom edge — while the
# reference render it is held against occupies 52%.
#
# A bounding box rather than a pixel count, because Manim output is line art: counting lit pixels
# calls every correct animation empty, and how far apart they are does not.


@needs_ffmpeg
def test_a_drawing_in_one_corner_is_measured_as_too_small(tmp_path):
    path = tmp_path / "corner.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", BLACK, "-t", "3",
         "-vf", "drawbox=x=20:y=150:w=60:h=8:color=white:t=fill",
         "-pix_fmt", "yuv420p", str(path)],
        capture_output=True, check=True,
    )

    assert renderer._content_fraction(path, 320, 180) < renderer.CONTENT_LIMIT


@needs_ffmpeg
def test_line_art_spread_across_the_frame_is_not_too_small(tmp_path):
    """The regression the blank check taught us to write first.

    Two small boxes in opposite corners light up 0.6% of the pixels — less than the corner clip
    above — and are a perfectly good composition. What separates them is the box that contains
    them, which is why this is measured with `cropdetect` and not with a histogram.
    """

    path = tmp_path / "spread.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", BLACK, "-t", "3",
         "-vf", "drawbox=x=20:y=20:w=60:h=10:color=white:t=fill,"
                "drawbox=x=240:y=150:w=60:h=10:color=white:t=fill",
         "-pix_fmt", "yuv420p", str(path)],
        capture_output=True, check=True,
    )

    assert renderer._content_fraction(path, 320, 180) > renderer.CONTENT_LIMIT


@needs_ffmpeg
def test_the_box_is_the_union_over_the_whole_clip(tmp_path):
    """A scene that draws in three places at three different times has used all three.

    `reset=0` accumulates, so a build-up that ends with everything on screen and a build-up that
    clears as it goes measure the same. Anything else would fail the second one for good practice.
    """

    first = tmp_path / "first.mp4"
    second = tmp_path / "second.mp4"
    for path, box in ((first, "x=20:y=20:w=60:h=10"), (second, "x=240:y=150:w=60:h=10")):
        subprocess.run(
            ["ffmpeg", "-y", "-f", "lavfi", "-i", BLACK, "-t", "2",
             "-vf", f"drawbox={box}:color=white:t=fill", "-pix_fmt", "yuv420p", str(path)],
            capture_output=True, check=True,
        )
    listing = tmp_path / "parts.txt"
    listing.write_text(f"file '{first}'\nfile '{second}'\n", encoding="utf-8")
    joined = tmp_path / "joined.mp4"
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-c", "copy", str(joined)], capture_output=True, check=True)

    # Neither half alone spans the frame; together they do.
    assert renderer._content_fraction(joined, 320, 180) > renderer.CONTENT_LIMIT


def test_an_unmeasurable_clip_keeps_the_scene(tmp_path):
    """None, not zero. Zero would mean "an empty frame" and throw the render away."""

    assert renderer._content_fraction(tmp_path / "does-not-exist.mp4", 320, 180) is None


@needs_ffmpeg
def test_a_drawing_too_thin_to_measure_keeps_the_scene(tmp_path):
    """The limitation of this measurement, written down as a test rather than discovered later.

    cropdetect thresholds whole rows and columns, not pixels. A ten-pixel-wide mark leaves every
    row it touches under the limit, the scan for the top border runs past the scan for the bottom,
    and the filter reports a negative height. That is not "the scene is empty" — it is "this
    measurement does not apply" — and it has to keep the render.
    """

    path = tmp_path / "thin.mp4"
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", BLACK, "-t", "2",
         "-vf", "drawbox=x=30:y=20:w=10:h=10:color=white:t=fill",
         "-pix_fmt", "yuv420p", str(path)],
        capture_output=True, check=True,
    )

    assert renderer._content_fraction(path, 320, 180) is None
