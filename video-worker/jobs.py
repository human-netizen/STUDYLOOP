"""Per-job scratch space, and the only state this service keeps.

The worker is deliberately amnesiac. A job directory exists from the first request that mentions
its id until the backend has fetched the finished file and said so; nothing survives a restart, and
nothing is a source of truth. The database row lives on the other side of the wire and Spring is
its only writer — see the backend's VideoJob for why two writers to one status column across two
languages is a status column that eventually lies.

What that buys is that this service can be killed, rebuilt or scaled to zero between jobs without
anything needing reconciliation. The one case it does not cover — a render interrupted halfway — is
covered on the other side, by the startup sweep that fails jobs the last process left in flight.
"""

from __future__ import annotations

import re
import shutil
from pathlib import Path

#: Everything lives under here. A tmpfs or an ephemeral container layer is fine: nothing in this
#: tree is worth keeping once the mp4 has been handed over.
ROOT = Path("/tmp/studyloop-video")

#: Job ids are UUIDs from the backend. Validated anyway, because this string becomes a path and a
#: service that builds paths out of unvalidated request input is one ``../`` away from being a file
#: server. The backend is trusted, and trusted input is still input.
_JOB_ID = re.compile(r"^[0-9a-fA-F-]{8,64}$")


class BadJobId(ValueError):
    """The id in the URL is not something this service will turn into a directory."""


def job_dir(job_id: str) -> Path:
    """The scratch directory for a job, created on first use."""

    if not _JOB_ID.match(job_id):
        raise BadJobId(f"Not a usable job id: {job_id!r}")
    path = ROOT / job_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def scene_dir(job_id: str, index: int) -> Path:
    """One scene's own directory — its code, its render, its audio, its timings.

    Per scene rather than per job because it is also the sandbox's cwd, and the sandbox's cwd is
    the only writable path the generated code has. Sharing one directory across scenes would mean a
    scene that misbehaves can overwrite the previous scene's finished render.
    """

    if index < 0 or index > 999:
        raise BadJobId(f"Not a usable scene index: {index}")
    path = job_dir(job_id) / f"scene-{index:03d}"
    path.mkdir(parents=True, exist_ok=True)
    return path


def discard(job_id: str) -> None:
    """Remove everything for a job. Best effort — a leftover directory is not a correctness bug."""

    try:
        shutil.rmtree(job_dir(job_id), ignore_errors=True)
    except BadJobId:
        return
