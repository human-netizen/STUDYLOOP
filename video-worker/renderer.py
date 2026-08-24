"""Two ways to put something on screen: Manim, or a slide.

The second one is not a failure mode. Four of six scenes attempt animation and the rest are slides
by design, because a definition and a recap are not improved by movement — and because a pipeline
whose every scene is a render is a pipeline whose wall clock is unbounded.

What matters here is the *accounting*. AddNewFeature.md §4's objection to ZenLearn's video pipeline
was not that it falls back to slides; it was that it falls back silently, so a job that produced
seven animations and a job that produced seven slides are indistinguishable from the outside. Every
function below returns the layer that decided the outcome, and the backend writes it onto the scene
row.
"""

from __future__ import annotations

import glob
import re
import subprocess
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import jobs
import narrator
import sandbox

#: The product's palette, from frontend/src/index.css. A fallback slide has to look like StudyLoop
#: rather than like an error page — the same dark ground, the same ink, the same one accent — which
#: is the difference between "this scene is a diagram" and "something went wrong here".
GROUND = "#0a0c0e"
SURFACE = "#161b1f"
INK = "#e7edf2"
INK_2 = "#9aa8b4"
ACCENT = "#26c9c0"

#: DejaVu ships with the base image and covers Latin. Bengali needs a font with the Bengali block;
#: Noto is installed in the Dockerfile for exactly this, because a Bangla slide rendered in DejaVu
#: is a row of empty boxes — a failure that looks like a rendering bug and is really a font bug.
_LATIN_FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
_LATIN_FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
_BENGALI_FONT = "/usr/share/fonts/truetype/noto/NotoSansBengali-Regular.ttf"


def render_animation(job_id: str, index: int, code: str, width: int, height: int,
                     fps: int, budget_seconds: float) -> sandbox.Verdict:
    """Check the generated code, then run Manim on it inside the sandbox.

    The two steps are separate calls and the order is not negotiable: the allow-list runs before a
    process exists, so code that was never going to be allowed costs no fork, no import of Manim,
    and no time from the scene's budget.
    """

    verdict = sandbox.check(code)
    if not verdict.ok:
        return verdict

    directory = jobs.scene_dir(job_id, index)
    source = directory / "scene.py"
    source.write_text(code, encoding="utf-8")

    # `-r W,H` and `--fps` rather than a quality preset, because the presets pin both together and
    # the backend owns the resolution. `--disable_caching` because the cache is keyed on the scene
    # source and every scene here is written once, so the cache is pure overhead — and it writes
    # into a directory the sandbox would rather keep empty.
    command = [
        sandbox.python_executable(), "-m", "manim", "render",
        "--media_dir", str(directory / "media"),
        "--disable_caching",
        "--format", "mp4",
        "-r", f"{width},{height}",
        "--fps", str(fps),
        "-o", "scene",
        str(source), sandbox.SCENE_CLASS,
    ]
    verdict = sandbox.run_isolated(command, directory, budget_seconds)
    if not verdict.ok:
        return verdict

    produced = _find_render(directory)
    if produced is None:
        # Manim exits 0 and writes nothing when the scene has no animations in it — a `construct`
        # that only builds mobjects and never calls `self.play`. Reported as RENDER rather than as
        # success, because a zero-length scene concatenated into the film is a jump cut.
        return sandbox.Verdict(False, sandbox.LAYER_RENDER,
                               "Manim produced no video. The scene has no animations in it.")
    produced.replace(directory / "visual.mp4")

    # **A scene that renders is not the same as a scene that shows something**, and until this
    # check existed the difference was invisible to every layer above. Manim will happily animate
    # objects that were shifted off the frame, scaled to a hundredth of their size, or faded out in
    # the first second, exit 0, and hand back a file of black frames. It then gets worse rather than
    # better downstream: the composer holds the final frame for as long as the narration keeps
    # talking, so a scene that ends empty becomes half a minute of black with a voice over it.
    #
    # Measured on the first animation this feature ever rendered successfully: 30.84 seconds of
    # black in a 30.86-second scene.
    blank = _blank_seconds(directory / "visual.mp4")
    length = narrator.probe_duration(directory / "visual.mp4")
    if length > 0 and blank / length >= BLANK_LIMIT:
        return sandbox.Verdict(False, sandbox.LAYER_RENDER, (
            f"The scene rendered, but {blank:.1f} of its {length:.1f} seconds are a blank frame. "
            f"Whatever it draws is off the frame, scaled to nothing, or removed too early. Keep the "
            f"picture inside the frame and on screen, and leave it there at the end — the last "
            f"frame is held while the narration finishes."))

    # **Not blank is not the same as worth watching**, and the two failures are different enough to
    # need different measurements. The blank check counts *dark seconds*; this one measures *how
    # much of the picture is used*, which is the failure that survived it — a correct little
    # animation drawn in one corner passes blackdetect at every threshold and is unwatchable.
    used = _content_fraction(directory / "visual.mp4", width, height)
    if used is not None and used < CONTENT_LIMIT:
        return sandbox.Verdict(False, sandbox.LAYER_RENDER, (
            f"The scene rendered, but everything it draws fits inside {used * 100:.1f}% of the "
            f"frame. Make the drawing fill the frame: boxes about 1.0 units on a side, groups sized "
            f"with `.set_width(11)` and centred with `.move_to(ORIGIN)`, a title at "
            f"`.to_edge(UP)`. It has to span at least half the frame in each direction."))

    spoken = narrator.probe_duration(directory / "narration.mp3")
    if spoken > 0 and length > 0 and length < spoken * NARRATION_LIMIT:
        return sandbox.Verdict(False, sandbox.LAYER_RENDER, (
            f"The scene animates for {length:.1f} seconds, but the narration over it runs "
            f"{spoken:.1f} seconds. The remaining {spoken - length:.1f} seconds are a frozen frame "
            f"with a voice over them. Give each step of the narration its own beat and add up the "
            f"`run_time`s and `wait`s until they come to {spoken:.0f} seconds."))
    return sandbox.Verdict(True, sandbox.LAYER_ALLOWED)


#: How much of a scene may be a blank frame before it is treated as a failed render.
#:
#: Not a small number, deliberately. Fading in from black and out to black is ordinary film-making,
#: and a scene that spends a couple of seconds either side of its content on an empty frame is fine.
#: Two thirds is the point where there is no reading of the clip under which a viewer saw the
#: explanation.
BLANK_LIMIT = 0.66


def _blank_seconds(video: Path) -> float:
    """How many seconds of the clip are an essentially empty frame, per ffmpeg's `blackdetect`.

    Zero when ffmpeg cannot be run or says nothing — this decides whether to throw a render away,
    so an unreadable answer has to mean "keep it". A false negative costs one dull scene; a false
    positive costs a scene that was fine and two model calls trying to fix it.
    """

    try:
        probe = subprocess.run(
            ["ffmpeg", "-hide_banner", "-nostats", "-i", str(video), "-vf",
             f"blackdetect=d=0.2:pix_th={BLANK_PIXEL_THRESHOLD}:pic_th={BLANK_PICTURE_THRESHOLD}",
             "-an", "-f", "null", "-"],
            capture_output=True, text=True, timeout=60, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return 0.0
    # blackdetect reports one line per run of black frames, on stderr, as `black_duration:12.34`.
    return sum(float(match) for match in re.findall(r"black_duration:([0-9.]+)", probe.stderr))


#: How dark a pixel counts as black. Manim's default background is #000000, so anything drawn on it
#: lifts the frame well clear of this; 0.10 leaves room for a dark fade without calling it content.
BLANK_PIXEL_THRESHOLD = 0.10

#: How much of the frame has to be those dark pixels before the frame counts as empty.
#:
#: **ffmpeg's default is 0.98, and 0.98 is wrong here** — that is the first version of this check,
#: and it threw away a working scene. A Manim frame is line art on black: ten square outlines, an
#: arrow and two labels light up well under two per cent of a 1280x720 frame, so "98% dark" is the
#: normal condition of a correct animation rather than a symptom.
#:
#: Measured on the two clips that matter, which is the only reason to trust the number. The scene
#: that really was empty: 30.84s flagged at 0.98, still 29.97 of its 30.86 seconds at 0.999. The
#: sparse scene that was fine: 10.0 seconds flagged at 0.98, and **zero** at 0.999. One number
#: separates them cleanly, so the check keeps the failure it was written for and stops taking the
#: scenes it was not.
BLANK_PICTURE_THRESHOLD = 0.999


def _content_fraction(video: Path, width: int, height: int) -> float | None:
    """How much of the frame the drawing occupies, as a fraction of its area.

    ``cropdetect`` with ``reset=0`` accumulates over the whole clip and reports the union bounding
    box of everything that was ever brighter than ``limit`` — which is exactly the question, because
    a scene that draws in three places at three different times has used all three.

    **A bounding box rather than a pixel count, and the difference is the whole point.** Manim
    output is line art: ten square outlines and two labels light up under two per cent of the
    pixels, so any measure of *how many* pixels are lit calls a correct animation empty. How far
    apart they are does separate the cases — sparse line art spread across the frame has a large
    box, and a row of small numbers along one edge has a tiny one.

    None when ffmpeg cannot answer, which keeps the render. A false positive here costs a scene
    that was fine and the model calls spent re-doing it.
    """

    try:
        probe = subprocess.run(
            ["ffmpeg", "-hide_banner", "-nostats", "-i", str(video), "-vf",
             f"cropdetect=limit={CONTENT_PIXEL_THRESHOLD}:round=2:reset=0",
             "-an", "-f", "null", "-"],
            capture_output=True, text=True, timeout=120, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    boxes = re.findall(r"crop=(-?\d+):(-?\d+):", probe.stderr)
    if not boxes or width <= 0 or height <= 0:
        return None
    box_width, box_height = int(boxes[-1][0]), int(boxes[-1][1])
    if box_width <= 0 or box_height <= 0:
        # cropdetect reports a negative dimension when it could not find the border at all — it
        # thresholds whole rows and columns rather than pixels, so a drawing thin enough in one
        # axis leaves every row under the limit and the scan runs past itself. Unmeasurable, and
        # unmeasurable keeps the scene.
        return None
    return (box_width * box_height) / float(width * height)


#: How small the drawing may be before the scene is treated as a failed render.
#:
#: Measured on two files rather than chosen, because the last check written here was given a
#: threshold from ffmpeg's documentation and immediately threw away a working scene. ZenLearn's
#: reference render — a bar chart with a title, the standard this is aiming at — occupies **52%** of
#: the frame. The first animation this feature produced unaided, a row of 30-pixel numbers along the
#: bottom edge, occupies **0.6%**. Anywhere between those two separates them; 12% is low enough to
#: pass a tall narrow drawing (a stack, at 20% by 70%) or a wide flat one (a timeline, at 90% by
#: 15%), both of which are legitimate compositions rather than failures.
CONTENT_LIMIT = 0.12

#: How bright a line has to be to count as part of the drawing, on cropdetect's 0-255 scale.
#:
#: A *line* and not a pixel: cropdetect sums a whole row or column and compares that, which is why
#: this cannot simply be BLANK_PIXEL_THRESHOLD in the other filter's units. Measured across the
#: three clips that matter — the reference render, the bad scene, and a synthetic sparse one — 24 is
#: the only value tried that separates them: at 12 and below every clip reports the full frame,
#: because encoder noise on a black background clears the limit and the filter finds no border at
#: all. The cost of 24 is that a drawing thin enough in one axis is not measurable, and that case
#: returns None above rather than a small number.
CONTENT_PIXEL_THRESHOLD = 24

#: How short an animation may be relative to its narration.
#:
#: The composer holds a scene's last frame for as long as the voice keeps talking, so an animation
#: that ends early does not fail — it freezes, which is worse, because from the outside it is a film
#: that races through the explanation and then stops dead while somebody is still explaining it.
#: Measured on the first watchable film: twelve seconds of animation under thirty-two seconds of
#: narration, a ratio of 0.37.
#:
#: Half, and not three quarters. A couple of seconds resting on the finished picture is ordinary
#: film-making, and every rejection here costs a model call.
NARRATION_LIMIT = 0.5


def _find_render(directory: Path) -> Path | None:
    """Manim buries its output under media/videos/<source>/<quality>/. Take the newest mp4."""

    candidates = [Path(match) for match in glob.glob(str(directory / "media" / "**" / "*.mp4"),
                                                     recursive=True)]
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def render_slide(job_id: str, index: int, title: str, bullets: list[str],
                 width: int, height: int) -> sandbox.Verdict:
    """Draw a slide with Pillow — once finished, and once per bullet on the way there.

    Pillow rather than Manim, even though Manim can obviously draw text: this path is the one that
    runs when Manim has already failed, and a fallback that depends on the thing it is falling back
    from is not a fallback. It is also two orders of magnitude faster, which matters when it is the
    common case for four of six scenes.

    **The stages are why this writes more than one file.** A slide is on screen for as long as its
    narration runs, which the script writer happily makes thirty-five seconds — and a still image
    held for thirty-five seconds is the thing that makes a generated video feel like a slideshow
    rather than like an explanation. Writing the same slide with one bullet, then two, then three
    lets the composer cut between them in time with the voice, at the cost of two more Pillow
    renders and no model call at all.

    Every stage is drawn from the *full* layout with the later bullets left out, so nothing moves
    when the next one appears. Reflowing the block each time would make the text jump, which reads
    as a rendering fault rather than as a reveal.
    """

    directory = jobs.scene_dir(job_id, index)
    shown = [bullet for bullet in bullets[:4] if bullet and bullet.strip()]
    try:
        _draw_slide(width, height, title, shown, len(shown)).save(directory / _SLIDE)
        if len(shown) > 1:
            for stage in range(1, len(shown) + 1):
                _draw_slide(width, height, title, shown, stage).save(
                    directory / f"{_SLIDE_STEM}-{stage}.png")
    except OSError as error:
        return sandbox.Verdict(False, sandbox.LAYER_RENDER, f"Could not draw the slide: {error}")
    return sandbox.Verdict(True, sandbox.LAYER_ALLOWED)


#: The finished slide, and the stem the partial ones are numbered from. The composer knows both.
_SLIDE = "visual.png"
_SLIDE_STEM = "visual"


def _draw_slide(width: int, height: int, title: str, bullets: list[str], revealed: int) -> Image.Image:
    """One slide, with the first `revealed` bullets drawn and the rest left as empty space."""

    image = Image.new("RGB", (width, height), GROUND)
    draw = ImageDraw.Draw(image)

    margin = int(width * 0.09)
    title_font = _font(title, int(height * 0.085), bold=True)
    body_font = _font(" ".join(bullets), int(height * 0.052))

    # A card behind the text, one step lighter than the ground. The elevation ladder in the
    # app adds light to raise a surface; a slide that inverted that would read as a different
    # product.
    draw.rounded_rectangle(
        [(margin // 2, margin // 2), (width - margin // 2, height - margin // 2)],
        radius=int(height * 0.03), fill=SURFACE)

    title_lines = _wrap(title, title_font, width - 2 * margin, draw)[:2]
    bullet_lines = [_wrap(bullet, body_font, width - 2 * margin - 40, draw)[:2]
                    for bullet in bullets]

    # **Measured first, then drawn, so the block sits in the middle of the card.** Starting at a
    # fixed top margin looks right for a four-bullet scene and leaves a two-bullet one hanging
    # off the top of an empty rectangle — and scene length here is decided by how much the model
    # had to say, so both are ordinary.
    #
    # Measured over *every* bullet rather than over the revealed ones, which is what keeps the
    # block still while it fills in.
    rule_gap = int(height * 0.015)
    rule_height = max(2, height // 240)
    block = len(title_lines) * int(title_font.size * 1.25) + rule_gap + rule_height \
        + int(height * 0.06) \
        + sum(len(lines) * int(body_font.size * 1.35) + int(body_font.size * 0.35)
              for lines in bullet_lines)
    y = max(margin, (height - block) // 2)

    for line in title_lines:
        draw.text((margin, y), line, font=title_font, fill=INK)
        y += int(title_font.size * 1.25)

    # The accent rule under the title is the one place colour is spent, matching the app's
    # single-accent rule.
    y += rule_gap
    draw.rectangle([(margin, y), (margin + int(width * 0.08), y + rule_height)], fill=ACCENT)
    y += int(height * 0.06)

    for position, lines in enumerate(bullet_lines):
        for offset, line in enumerate(lines):
            if position < revealed:
                if offset == 0:
                    draw.ellipse([(margin, y + body_font.size * 0.42),
                                  (margin + 10, y + body_font.size * 0.42 + 10)], fill=ACCENT)
                draw.text((margin + 32, y), line, font=body_font, fill=INK_2 if offset else INK)
            y += int(body_font.size * 1.35)
        y += int(body_font.size * 0.35)
    return image


def render_sources(job_id: str, sources: list[str], width: int, height: int) -> Path | None:
    """The closing slide: what this video was made from.

    Not decoration. A video whose claims cannot be traced to a page is the artifact §4 said was
    worse than the PDF viewer, and the per-scene citations only exist in the player's rail — this
    is the copy that survives the file being downloaded and sent to somebody.
    """

    if not sources:
        return None
    verdict = render_slide(job_id, 999, "Sources", sources[:4], width, height)
    if not verdict.ok:
        return None
    return jobs.scene_dir(job_id, 999) / "visual.png"


def _font(sample: str, size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    """Pick a face that can actually draw this text.

    Chosen per string rather than per job because a Bangla course still has English headings in it,
    and the alternative to checking is a slide of empty boxes that looks like a rendering bug.
    """

    path = _BENGALI_FONT if _has_bengali(sample) else (_LATIN_FONT_BOLD if bold else _LATIN_FONT)
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        try:
            return ImageFont.truetype(_LATIN_FONT, size)
        except OSError:
            # Pillow's built-in bitmap font ignores `size`, so this produces a legible but ugly
            # slide rather than a crash. It only happens in an image missing its fonts.
            return ImageFont.load_default()


def _has_bengali(text: str) -> bool:
    return any("ঀ" <= character <= "৿" for character in text)


def _wrap(text: str, font: ImageFont.FreeTypeFont, max_width: int, draw: ImageDraw.ImageDraw) -> list[str]:
    """Greedy wrap measured in pixels rather than characters.

    Character counts are wrong for the two scripts this has to support at once — Bengali conjuncts
    and Latin capitals are different widths — and the failure is text running off the slide.
    """

    words = text.split()
    if not words:
        return []
    lines: list[str] = []
    current = words[0]
    for word in words[1:]:
        candidate = f"{current} {word}"
        if draw.textlength(candidate, font=font) <= max_width:
            current = candidate
        else:
            lines.append(current)
            current = word
    lines.append(current)
    return lines


def shorten(text: str, width: int = 90) -> str:
    return textwrap.shorten(text, width=width, placeholder="…")
