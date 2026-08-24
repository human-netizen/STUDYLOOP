"""The renderer's HTTP surface — text in, files out, and nothing else.

**This service holds no secrets and reaches no database.** It has no API key, no connection string
and no notion of a course or a user; it is handed a scene's code or a scene's narration by the
backend and answers whether that worked. Two consequences follow, and both are deliberate. The
backend is the only writer of a job's status, so that column cannot be made to disagree with itself
across two languages and two connection pools. And the sandbox in ``sandbox.py`` is guarding a
process whose environment is genuinely empty, rather than one that merely promises not to read the
credentials sitting next to it.

**Nothing here is authenticated**, and that is a deployment property rather than an oversight: the
worker binds a port on the compose network and is never published to the internet. It is stated
here because it is exactly the sort of thing that stops being true when somebody adds a port
mapping, and the honest place for that sentence is the file that would be exposed.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

import composer
import jobs
import narrator
import renderer
import sandbox

app = FastAPI(title="StudyLoop video worker", docs_url=None, redoc_url=None)


# ── the wire ────────────────────────────────────────────────────────────────────────────────
#
# Field names are the Java record component names, camelCase and all, so both sides of this wire
# can be read against each other without a mapping table in between. There is no shared schema
# file to have between a Spring service and a FastAPI one; six flat shapes on one screen is the
# next best thing.


class AnimateRequest(BaseModel):
    code: str
    width: int = 1280
    height: int = 720
    fps: int = 30
    budgetSeconds: float = Field(default=180.0, ge=5.0, le=1800.0)  # noqa: N815


class SlideRequest(BaseModel):
    title: str
    bullets: list[str] = []
    width: int = 1280
    height: int = 720


class NarrateRequest(BaseModel):
    text: str
    voice: str = "en-US-AriaNeural"


class ComposeRequest(BaseModel):
    scenes: list[int]
    sources: list[str] = []
    width: int = 1280
    height: int = 720
    fps: int = 30


# ── health ──────────────────────────────────────────────────────────────────────────────────


@app.get("/health")
def health() -> dict:
    """What this container can actually do, checked rather than assumed.

    The backend calls this before accepting a job, so a machine where the image is up but ffmpeg is
    missing answers "unavailable" at the door instead of failing four minutes into a render. It
    also reports whether network isolation is available, because that is the one layer of the
    sandbox that can be silently absent — and a claim about isolation that nobody verified is worse
    than no claim.
    """

    ffmpeg = shutil.which("ffmpeg")
    manim = _manim_version()
    isolated = sandbox.network_isolation_available()
    ready = bool(ffmpeg) and manim is not None
    detail = "ready" if ready else "missing: " + ", ".join(
        name for name, present in (("ffmpeg", bool(ffmpeg)), ("manim", manim is not None))
        if not present)
    if ready and not isolated:
        detail = ("ready, but this kernel does not allow unprivileged network namespaces — "
                  "generated code runs behind the allow-list and the process limits only")
    return {"ready": ready, "ffmpeg": ffmpeg, "manim": manim, "detail": detail}


def _manim_version() -> str | None:
    try:
        probe = subprocess.run([sandbox.python_executable(), "-m", "manim", "--version"],
                               capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    return probe.stdout.strip() or None if probe.returncode == 0 else None


# ── one scene ───────────────────────────────────────────────────────────────────────────────


@app.post("/jobs/{job_id}/scenes/{index}/animate")
def animate(job_id: str, index: int, request: AnimateRequest) -> dict:
    """One attempt at one generated scene.

    ``ok: false`` is not an error and never a 500: a rejected or failed animation is the case this
    whole phase is built around, and the layer that stopped it is the answer the backend wants.
    """

    verdict = renderer.render_animation(
        _job(job_id), index, request.code, request.width, request.height,
        request.fps, request.budgetSeconds)
    return {"ok": verdict.ok, "layer": verdict.layer, "detail": verdict.detail}


@app.post("/jobs/{job_id}/scenes/{index}/slide")
def slide(job_id: str, index: int, request: SlideRequest) -> dict:
    verdict = renderer.render_slide(
        _job(job_id), index, request.title, request.bullets, request.width, request.height)
    return {"ok": verdict.ok, "layer": verdict.layer, "detail": verdict.detail}


@app.post("/jobs/{job_id}/scenes/{index}/narrate")
def narrate(job_id: str, index: int, request: NarrateRequest) -> dict:
    result = narrator.narrate(_job(job_id), index, request.text, request.voice)
    return {"ok": result.ok, "durationSeconds": result.duration_seconds, "detail": result.detail}


# ── the film ────────────────────────────────────────────────────────────────────────────────


@app.post("/jobs/{job_id}/compose")
def compose(job_id: str, request: ComposeRequest) -> dict:
    job = _job(job_id)
    sources_slide = renderer.render_sources(job, request.sources, request.width, request.height)
    result = composer.compose(job, request.scenes, sources_slide,
                              request.width, request.height, request.fps)
    return {
        "ok": result.ok,
        "durationSeconds": result.duration_seconds,
        "captions": result.captions,
        "detail": result.detail,
    }


@app.get("/jobs/{job_id}/video")
def video(job_id: str) -> FileResponse:
    return _file(jobs.job_dir(_job(job_id)) / "video.mp4", "video/mp4")


@app.get("/jobs/{job_id}/captions")
def captions(job_id: str) -> FileResponse:
    return _file(jobs.job_dir(_job(job_id)) / "captions.vtt", "text/vtt")


@app.delete("/jobs/{job_id}")
def discard(job_id: str) -> dict:
    """Everything for this job, gone.

    Called once the backend has the bytes. Not called on failure — the backend fetches nothing from
    a job that failed, but it does keep the generated code, and that copy lives on its side of the
    wire so that this directory can disappear whenever the container does.
    """

    jobs.discard(_job(job_id))
    return {"ok": True}


def _job(job_id: str) -> str:
    try:
        jobs.job_dir(job_id)
    except jobs.BadJobId as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    return job_id


def _file(path: Path, media_type: str) -> FileResponse:
    if not path.exists():
        raise HTTPException(status_code=404, detail=f"No {path.name} for this job.")
    return FileResponse(path, media_type=media_type, filename=path.name)
