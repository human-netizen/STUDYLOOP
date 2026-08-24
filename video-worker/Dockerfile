# The sidecar that keeps Manim, LaTeX, ffmpeg and a speech engine out of the deploy target.
#
# This image is the answer to the first of AddNewFeature.md §4's four objections. None of what is
# installed below goes anywhere near the Spring image or the cloud instance: the backend treats the
# absence of this container as a feature flag, and 23.4 deploys exactly what it deployed before.
#
# It is also why the phase needs nothing installed on the developer's machine. Manim's graphics
# stack has no wheels for the Python 3.14 on that box, so a native install would mean building
# cairo and pango from source on Windows.
FROM python:3.12-slim

# ffmpeg composes; the cairo/pango pair is what Manim draws with; the fonts matter more than they
# look. DejaVu covers Latin, Noto Bengali is here because Phase 19 made Bangla a first-class
# language and a Bangla slide in a Latin-only font is a row of empty boxes.
#
# util-linux carries `unshare`, which is sandbox layer 3.
RUN apt-get update && apt-get install --no-install-recommends -y \
        ffmpeg \
        libcairo2 \
        libpango-1.0-0 \
        libpangocairo-1.0-0 \
        libgl1 \
        fonts-dejavu-core \
        fonts-noto-core \
        util-linux \
    && rm -rf /var/lib/apt/lists/*

# **No LaTeX.** A full TeX Live is ~2 GB and would be pulled in for `MathTex`, which the scene
# prompt limits to symbols, fractions, sums and Greek letters. Without it, a scene that reaches for
# LaTeX fails inside the sandbox and becomes a slide — a counted, visible degradation rather than a
# two-gigabyte layer every build has to carry. Install texlive-latex-base and dvisvgm here if the
# fallback rate turns out to be dominated by MathTex, which is a measurement this phase reports.

WORKDIR /app

COPY requirements.txt .

# **pycairo has no wheel and never will**, because it links against whatever cairo is installed on
# the machine — so this install needs a compiler and the cairo/pango *headers*, not just the
# libraries above. The build toolchain is then purged in the same layer: keeping it would carry
# ~250 MB of gcc into an image whose only job is to run a renderer, and leaving it in a separate
# `RUN` would keep it in the layer below regardless of the purge.
#
# The runtime libraries survive the purge because they were installed explicitly in the layer above,
# which marks them manually-installed and puts them out of `--auto-remove`'s reach. That the purge
# did not break the compiled extension is not assumed — the image runs its own test suite.
RUN apt-get update && apt-get install --no-install-recommends -y \
        build-essential \
        pkg-config \
        python3-dev \
        libcairo2-dev \
        libpango1.0-dev \
    && pip install --no-cache-dir -r requirements.txt \
    && apt-get purge -y --auto-remove \
        build-essential pkg-config python3-dev libcairo2-dev libpango1.0-dev \
    && rm -rf /var/lib/apt/lists/*

COPY *.py ./
COPY tests ./tests

# Non-root, and this is sandbox layer 2's first line: the render inherits this uid, so generated
# code cannot write outside the scratch directory even before the rlimits are applied.
RUN useradd --create-home --uid 10001 renderer \
    && mkdir -p /tmp/studyloop-video \
    && chown -R renderer:renderer /app /tmp/studyloop-video
USER renderer

EXPOSE 8000

# One worker process. The backend renders one job at a time by construction (a single-slot
# executor), so a second uvicorn worker would only add a way for two renders to contend for the
# same cores.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
