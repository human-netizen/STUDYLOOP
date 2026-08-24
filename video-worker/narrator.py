"""Speech, and the two things that come out of it: a duration and a caption track.

**The duration is the important one.** ZenLearn's composer loops a still image and cuts the scene
with ``-shortest`` against the model's own ``duration_estimate``, so any scene whose narration runs
longer than the model guessed is silently truncated mid-sentence. The estimate is a planning hint
and it is never a render input here: the audio is synthesized first, measured with ffprobe, and the
visual is built to fit what was measured.

**The captions come free and ZenLearn throws them away.** ``edge-tts`` emits a ``WordBoundary``
event for every word it speaks, with an offset and a duration; ZenLearn's ``synthesize_with_timing``
collects them into a list that no caller ever reads. Written out as WebVTT they make the video
accessible, and they make its content searchable text rather than pixels.
"""

from __future__ import annotations

import asyncio
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path

import edge_tts

import jobs

#: Words per cue. Short enough to read at speaking pace, long enough not to flicker.
_WORDS_PER_CUE = 8

#: edge-tts reports offsets in 100-nanosecond ticks, the Windows ``FILETIME`` unit the underlying
#: service is written against.
_TICKS_PER_SECOND = 10_000_000


@dataclass(frozen=True)
class Narration:
    ok: bool
    duration_seconds: float = 0.0
    detail: str = ""


def narrate(job_id: str, index: int, text: str, voice: str) -> Narration:
    """Speak one scene, measure it, and keep the word timings beside it."""

    directory = jobs.scene_dir(job_id, index)
    audio = directory / "narration.mp3"
    timings = directory / "timings.json"

    spoken = _clean(text)
    if not spoken:
        return Narration(False, detail="There is nothing to say in this scene.")

    try:
        words = asyncio.run(_synthesize(spoken, voice, audio))
    except Exception as error:  # noqa: BLE001 - edge_tts raises a wide family over the network
        return Narration(False, detail=f"Speech synthesis failed: {error}")

    if not audio.exists() or audio.stat().st_size == 0:
        return Narration(False, detail="The speech service returned no audio.")

    duration = probe_duration(audio)
    if duration <= 0:
        return Narration(False, detail="The rendered narration has no measurable length.")

    timings.write_text(json.dumps(words), encoding="utf-8")
    return Narration(True, duration_seconds=duration)


async def _synthesize(text: str, voice: str, target: Path) -> list[dict]:
    """Stream the audio to disk, collecting boundary events as they arrive.

    Streaming rather than ``Communicate.save`` because the boundaries only exist in the stream —
    ``save`` discards them, which is precisely how a caption track becomes "a feature we could add
    later" in a codebase that already has the data.

    ``boundary="WordBoundary"`` is asked for explicitly, and that is not a default worth trusting:
    edge-tts 7 changed it to ``SentenceBoundary``, and the symptom of not knowing that is a video
    with no captions and nothing in the logs — every event arrives, none of them matches, and the
    timings file is written as an empty list.

    Both kinds are kept anyway. A voice that only emits sentence boundaries still gives usable
    captions, one cue per sentence, and a degraded caption track is worth more than the honest
    absence of one.
    """

    communicate = edge_tts.Communicate(text, voice, boundary="WordBoundary")
    events: list[dict] = []
    with target.open("wb") as sink:
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                sink.write(chunk["data"])
            elif chunk["type"] in ("WordBoundary", "SentenceBoundary"):
                events.append({
                    "start": chunk["offset"] / _TICKS_PER_SECOND,
                    "end": (chunk["offset"] + chunk["duration"]) / _TICKS_PER_SECOND,
                    "text": chunk["text"],
                    "kind": chunk["type"],
                })
    return events


def probe_duration(path: Path) -> float:
    """Measure a media file with ffprobe. Zero when it cannot be read.

    The authority on how long a scene is. Reading the container's declared duration rather than
    decoding the whole file: an mp3 from a speech service is short and well formed, and decoding it
    to count samples would cost more than the synthesis did.
    """

    try:
        probe = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
            capture_output=True, text=True, timeout=30, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return 0.0
    try:
        return float(probe.stdout.strip())
    except ValueError:
        return 0.0


def _clean(text: str) -> str:
    """What is actually spoken.

    Markdown markers, citation brackets and stray asterisks are read aloud by a speech engine as
    "asterisk" or as a pause in the wrong place. The script prompt asks for none of them; this is
    what happens when the model includes them anyway.
    """

    stripped = text.replace("*", " ").replace("#", " ").replace("`", " ")
    for marker in ("[1]", "[2]", "[3]", "[4]", "[5]", "[6]"):
        stripped = stripped.replace(marker, " ")
    return " ".join(stripped.split())


# ── captions ────────────────────────────────────────────────────────────────────────────────


def build_vtt(job_id: str, scenes: list[tuple[int, float]]) -> str | None:
    """One WebVTT track for the whole film, from every scene's word timings.

    ``scenes`` is (index, start_seconds) in playing order — the offsets have to be applied here
    because each scene's timings are relative to its own audio, and a caption file is relative to
    the film. Getting that wrong produces subtitles that are correct for scene one and progressively
    wronger after it, which is worse than none.

    Returns None when no scene produced timings, and the caller then stores no caption track rather
    than an empty one: a ``<track>`` element pointing at an empty file is a subtitle button that
    does nothing.
    """

    cues: list[str] = []
    for index, offset in scenes:
        timings = jobs.scene_dir(job_id, index) / "timings.json"
        if not timings.exists():
            continue
        try:
            words = json.loads(timings.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        cues.extend(_cues(words, offset))

    if not cues:
        return None
    return "WEBVTT\n\n" + "\n\n".join(cues) + "\n"


def _cues(events: list[dict], offset: float) -> list[str]:
    """Words are grouped into cues; a sentence event is already a cue.

    Mixing the two in one list would produce a caption track that shows the sentence and then shows
    its words again underneath — so the kind decides the grouping rather than the position.
    """

    cues: list[str] = []
    words = [event for event in events if event.get("kind", "WordBoundary") == "WordBoundary"]
    sentences = [event for event in events if event.get("kind") == "SentenceBoundary"]

    for start in range(0, len(words), _WORDS_PER_CUE):
        group = words[start:start + _WORDS_PER_CUE]
        if not group:
            continue
        cues.append(_cue(offset + group[0]["start"], offset + group[-1]["end"],
                         " ".join(word["text"] for word in group)))

    if not words:
        for sentence in sentences:
            cues.append(_cue(offset + sentence["start"], offset + sentence["end"], sentence["text"]))
    return [cue for cue in cues if cue]


def _cue(begins: float, ends: float, text: str) -> str:
    stripped = " ".join(text.split())
    return f"{_timestamp(begins)} --> {_timestamp(ends)}\n{stripped}" if stripped else ""


def _timestamp(seconds: float) -> str:
    seconds = max(0.0, seconds)
    hours, remainder = divmod(int(seconds), 3600)
    minutes, whole = divmod(remainder, 60)
    milliseconds = int(round((seconds - int(seconds)) * 1000))
    return f"{hours:02d}:{minutes:02d}:{whole:02d}.{milliseconds:03d}"
