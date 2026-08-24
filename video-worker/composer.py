"""ffmpeg: one scene at a time, then all of them end to end.

Two rules run through this file.

**Nothing is ever truncated to fit.** Each scene is as long as the longer of its narration and its
animation, with the shorter one padded — silence after the voice stops, or the last frame held
after the animation ends. ZenLearn's composer does the opposite: ``-loop 1 … -shortest`` against a
model-estimated duration, which cuts the narration off mid-word whenever the estimate was low. That
is a defect you can only hear, in the middle of a three-minute file, which is the kind that ships.

**Every scene is encoded identically, so the concatenation can be a copy.** Re-encoding the whole
film at the end would double the wall clock of the cheapest step in the pipeline for no visible
difference. The price is that the per-scene encode has to pin the pixel format, the sample rate and
the timebase explicitly rather than letting ffmpeg infer them — an inferred parameter that differs
between two scenes is a concat that plays the first one and stops.
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path

import jobs
import narrator

#: The still shown under the closing source list, and under any scene whose visual is a slide.
_SLIDE = "visual.png"
_ANIMATION = "visual.mp4"

#: Encoder settings, pinned. Identical for every scene so the final concat is a stream copy.
_VIDEO_ARGS = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"]
_AUDIO_ARGS = ["-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2"]

#: How long the closing source slide stays on screen. Long enough to read four filenames.
_SOURCES_SECONDS = 6.0


@dataclass
class Composition:
    ok: bool
    duration_seconds: float = 0.0
    captions: bool = False
    detail: str = ""
    starts: list[tuple[int, float]] = field(default_factory=list)


def compose(job_id: str, scene_indices: list[int], sources_slide: Path | None,
            width: int, height: int, fps: int) -> Composition:
    """Build one mp4 and one caption track out of what the scenes produced."""

    directory = jobs.job_dir(job_id)
    parts: list[Path] = []
    starts: list[tuple[int, float]] = []
    clock = 0.0

    for index in scene_indices:
        scene = jobs.scene_dir(job_id, index)
        audio = scene / "narration.mp3"
        if not audio.exists():
            # Narration is what a scene is timed against, so a scene without it has no length to
            # build. The backend has already recorded why; skipping here keeps the film continuous
            # rather than inserting a silent gap nobody asked for.
            continue
        visual = scene / _ANIMATION if (scene / _ANIMATION).exists() else scene / _SLIDE
        if not visual.exists():
            continue

        spoken = narrator.probe_duration(audio)
        part = scene / "part.mp4"
        result = _scene_part(visual, audio, spoken, part, width, height, fps)
        if not result:
            return Composition(False, detail=f"Scene {index} could not be encoded.")

        starts.append((index, clock))
        clock += narrator.probe_duration(part)
        parts.append(part)

    if not parts:
        return Composition(False, detail="No scene produced both a visual and narration.")

    if sources_slide is not None and sources_slide.exists():
        tail = directory / "sources.mp4"
        if _silent_part(sources_slide, _SOURCES_SECONDS, tail, width, height, fps):
            parts.append(tail)
            clock += _SOURCES_SECONDS

    output = directory / "video.mp4"
    if not _concatenate(parts, output, directory):
        return Composition(False, detail="The scenes could not be joined together.")

    vtt = narrator.build_vtt(job_id, starts)
    if vtt:
        (directory / "captions.vtt").write_text(vtt, encoding="utf-8")

    return Composition(True, duration_seconds=narrator.probe_duration(output),
                       captions=vtt is not None, starts=starts)


def _scene_part(visual: Path, audio: Path, spoken: float, target: Path,
                width: int, height: int, fps: int) -> bool:
    """One scene, as long as the longer of its two halves.

    A still image and an animation take different filter graphs but reach the same place: a clip of
    exactly ``max(animation, narration)`` seconds, with silence or a held frame making up whatever
    was short.
    """

    if visual.suffix == ".png":
        # A still: loop it for exactly as long as the voice talks. `-t` on the input rather than
        # `-shortest` on the output, because `-shortest` is the flag that cuts narration.
        command = [
            "ffmpeg", "-y", "-loop", "1", "-i", str(visual), "-i", str(audio),
            "-t", f"{spoken:.3f}",
            "-vf", f"scale={width}:{height},fps={fps},format=yuv420p",
            *_VIDEO_ARGS, *_AUDIO_ARGS,
            "-video_track_timescale", "90000",
            str(target),
        ]
        return _run(command)

    animated = narrator.probe_duration(visual)
    target_length = max(animated, spoken)
    hold = max(0.0, target_length - animated)
    # `tpad` holds the last frame; `apad` writes silence. Both are given the same explicit `-t`, so
    # the two streams end on the same frame and the concat does not drift.
    command = [
        "ffmpeg", "-y", "-i", str(visual), "-i", str(audio),
        "-filter_complex",
        f"[0:v]tpad=stop_mode=clone:stop_duration={hold:.3f},scale={width}:{height},fps={fps},"
        f"format=yuv420p[v];[1:a]apad[a]",
        "-map", "[v]", "-map", "[a]", "-t", f"{target_length:.3f}",
        *_VIDEO_ARGS, *_AUDIO_ARGS,
        "-video_track_timescale", "90000",
        str(target),
    ]
    return _run(command)


def _silent_part(visual: Path, seconds: float, target: Path,
                 width: int, height: int, fps: int) -> bool:
    """The closing slide: a still with a silent audio track.

    Silent rather than absent, because a concat of clips where one has no audio stream produces a
    file whose audio stops early on most players and desynchronises on the rest.
    """

    command = [
        "ffmpeg", "-y", "-loop", "1", "-i", str(visual),
        "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
        "-t", f"{seconds:.3f}",
        "-vf", f"scale={width}:{height},fps={fps},format=yuv420p",
        *_VIDEO_ARGS, *_AUDIO_ARGS,
        "-video_track_timescale", "90000",
        str(target),
    ]
    return _run(command)


def _concatenate(parts: list[Path], output: Path, directory: Path) -> bool:
    """Join the encoded parts without re-encoding them."""

    listing = directory / "concat.txt"
    listing.write_text(
        "".join(f"file '{part.as_posix()}'\n" for part in parts), encoding="utf-8")
    return _run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                 "-c", "copy", "-movflags", "+faststart", str(output)])


def _run(command: list[str]) -> bool:
    """ffmpeg, with its output kept only when it failed.

    A successful ffmpeg run prints a screenful of stream metadata that means nothing to anybody;
    a failed one prints the reason in the last two lines. Capturing both and logging neither on
    success is the difference between a readable log and a scrollback.
    """

    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=600, check=False)
    except (OSError, subprocess.SubprocessError):
        return False
    if result.returncode != 0:
        print(command[0], "failed:", result.stderr.strip()[-1500:], flush=True)
        return False
    return True
