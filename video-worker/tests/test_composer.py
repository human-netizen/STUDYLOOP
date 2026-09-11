"""The slide that fills in, and the arithmetic that decides when.

A slide is on screen for as long as its narration runs, and the script writer routinely writes
thirty-five seconds for one scene. A still image held that long is the thing that makes a generated
video feel like a slideshow rather than an explanation — so the bullets arrive one at a time, cut
to the voice.

There is nothing here about how the slide *looks*; that is Pillow's problem and a person's eye.
What is worth asserting is that the stages exist, that they differ, that a scene with nothing to
reveal does not get a reveal, and that the finished clip is exactly as long as what is said over it
— because the whole point of the feature is timing, and timing is the thing that is silently wrong.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import composer  # noqa: E402
import jobs  # noqa: E402
import narrator  # noqa: E402
import renderer  # noqa: E402

# `shutil.which` rather than a trial run: asking ffmpeg for its version to find out whether
# ffmpeg exists raises FileNotFoundError when it does not, and `check=False` suppresses only a
# non-zero exit, not a missing executable. At module scope a raise is a collection error rather
# than a skip, so the module that meant to step aside takes the whole run down with it.
needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="composition is ffmpeg, which lives in the worker image",
)

BULLETS = ["Halve the search region.", "Compare the middle value.", "Repeat until it is found."]


def _slide(job: str, index: int, bullets: list[str]) -> Path:
    verdict = renderer.render_slide(job, index, "Binary search", bullets, 640, 360)
    assert verdict.ok, verdict.detail
    return jobs.scene_dir(job, index) / "visual.png"


def _silence(path: Path, seconds: float) -> Path:
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
         "-t", f"{seconds}", str(path)],
        capture_output=True, check=True,
    )
    return path


@needs_ffmpeg
def test_a_slide_with_several_bullets_is_written_once_per_bullet(tmp_path, monkeypatch):
    monkeypatch.setattr(jobs, "ROOT", tmp_path, raising=False)
    slide = _slide("aaaa0001", 1, BULLETS)

    stages = composer._stages(slide)

    assert [stage.name for stage in stages] == ["visual-1.png", "visual-2.png", "visual-3.png"]
    # Each stage differs from the one before it, which is the only claim worth making about
    # pixels here: if two stages were identical the reveal would be invisible.
    contents = [stage.read_bytes() for stage in stages]
    assert len(set(contents)) == len(contents)


@needs_ffmpeg
def test_a_slide_with_one_bullet_has_nothing_to_reveal(tmp_path, monkeypatch):
    monkeypatch.setattr(jobs, "ROOT", tmp_path, raising=False)
    slide = _slide("aaaa0002", 1, ["One line and that is all."])

    assert composer._stages(slide) == []


@needs_ffmpeg
def test_the_reveal_lasts_exactly_as_long_as_the_narration(tmp_path, monkeypatch):
    """The rule the whole composer is built on: nothing is ever truncated to fit.

    ZenLearn's composer loops a still under `-shortest` against a model-estimated duration, which
    cuts the narration off mid-word whenever the estimate was low. Here the audio is measured and
    the picture is fitted to it, so this assertion is on the audible half.
    """

    monkeypatch.setattr(jobs, "ROOT", tmp_path, raising=False)
    slide = _slide("aaaa0003", 1, BULLETS)
    audio = _silence(jobs.scene_dir("aaaa0003", 1) / "narration.mp3", 9.0)
    target = jobs.scene_dir("aaaa0003", 1) / "part.mp4"

    assert composer._scene_part(slide, audio, 9.0, target, 640, 360, 24)
    assert narrator.probe_duration(target) == pytest.approx(9.0, abs=0.25)


@needs_ffmpeg
def test_a_slide_that_cannot_be_revealed_is_still_held(tmp_path, monkeypatch):
    """One bullet takes the still path, and the still path has to produce the same length."""

    monkeypatch.setattr(jobs, "ROOT", tmp_path, raising=False)
    slide = _slide("aaaa0004", 1, ["Only one thing to say."])
    audio = _silence(jobs.scene_dir("aaaa0004", 1) / "narration.mp3", 6.0)
    target = jobs.scene_dir("aaaa0004", 1) / "part.mp4"

    assert composer._scene_part(slide, audio, 6.0, target, 640, 360, 24)
    assert narrator.probe_duration(target) == pytest.approx(6.0, abs=0.25)
