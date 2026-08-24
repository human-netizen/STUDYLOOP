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
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import jobs
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
    return sandbox.Verdict(True, sandbox.LAYER_ALLOWED)


def _find_render(directory: Path) -> Path | None:
    """Manim buries its output under media/videos/<source>/<quality>/. Take the newest mp4."""

    candidates = [Path(match) for match in glob.glob(str(directory / "media" / "**" / "*.mp4"),
                                                     recursive=True)]
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def render_slide(job_id: str, index: int, title: str, bullets: list[str],
                 width: int, height: int) -> sandbox.Verdict:
    """Draw a slide with Pillow.

    Pillow rather than Manim, even though Manim can obviously draw text: this path is the one that
    runs when Manim has already failed, and a fallback that depends on the thing it is falling back
    from is not a fallback. It is also two orders of magnitude faster, which matters when it is the
    common case for four of six scenes.
    """

    directory = jobs.scene_dir(job_id, index)
    try:
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
                        for bullet in bullets[:4]]

        # **Measured first, then drawn, so the block sits in the middle of the card.** Starting at a
        # fixed top margin looks right for a four-bullet scene and leaves a two-bullet one hanging
        # off the top of an empty rectangle — and scene length here is decided by how much the model
        # had to say, so both are ordinary.
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

        for lines in bullet_lines:
            for offset, line in enumerate(lines):
                if offset == 0:
                    draw.ellipse([(margin, y + body_font.size * 0.42),
                                  (margin + 10, y + body_font.size * 0.42 + 10)], fill=ACCENT)
                draw.text((margin + 32, y), line, font=body_font, fill=INK_2 if offset else INK)
                y += int(body_font.size * 1.35)
            y += int(body_font.size * 0.35)

        image.save(directory / "visual.png")
    except OSError as error:
        return sandbox.Verdict(False, sandbox.LAYER_RENDER, f"Could not draw the slide: {error}")
    return sandbox.Verdict(True, sandbox.LAYER_ALLOWED)


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
