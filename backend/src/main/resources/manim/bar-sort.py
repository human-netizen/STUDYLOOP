from manim import *


class GeneratedScene(Scene):
    def construct(self):
        ink = "#e7edf2"
        dim = "#5b6976"
        off = "#2f3a44"
        base = "#3f7fa6"
        accent = "#26c9c0"
        amber = "#f2b134"
        good = "#3ddc84"

        title = Text({{title}}, font_size=40, color=ink)
        title.to_edge(UP, buff=0.5)
        rule = Line(LEFT, RIGHT, color=accent, stroke_width=4)
        rule.set_width(title.width + 0.5)
        rule.next_to(title, DOWN, buff=0.2)
        self.play(Write(title), run_time=0.9)
        self.play(Create(rule), run_time=0.35)

        values = {{values}}
        tallest = max(values)
        pitch = min(1.15, 11.0 / len(values))
        baseline = -2.3

        bars = VGroup()
        for position in range(len(values)):
            height = 0.55 + 3.5 * values[position] / tallest
            bar = Rectangle(width=pitch * 0.72, height=height, stroke_width=2,
                            color=base, fill_color=base, fill_opacity=0.85)
            bar.move_to([0.0, baseline + height / 2.0, 0.0])
            label = Text(str(values[position]), font_size=22, color=dim)
            label.move_to([0.0, baseline - 0.36, 0.0])
            item = VGroup(bar, label)
            item.shift(RIGHT * (position - (len(values) - 1) / 2.0) * pitch)
            bars.add(item)

        floor = Line(LEFT, RIGHT, color=off, stroke_width=3)
        floor.set_width(pitch * len(values) + 0.4)
        floor.move_to([0.0, baseline - 0.06, 0.0])

        note = Text({{opening}}, font_size=26, color=dim)
        note.move_to([0.0, baseline - 1.15, 0.0])

        self.play(Create(floor), run_time=0.4)
        self.play(GrowFromEdge(bars, DOWN), run_time=1.3)
        self.play(FadeIn(note), run_time=0.4)
        self.wait({{settle}})

        order = []
        for position in range(len(values)):
            order.append(bars[position])

        steps = {{steps}}
        for step in steps:
            left = step[0]
            right = step[1]
            kind = step[2]
            words = step[3]

            fresh = Text(words, font_size=26, color=ink)
            fresh.move_to(note)
            swap_in = [Succession(FadeOut(note, shift=DOWN * 0.2),
                                  FadeIn(fresh, shift=DOWN * 0.2))]

            if kind == 1:
                gap = (right - left) * pitch
                self.play(order[left].animate.shift(RIGHT * gap + UP * 0.6),
                          order[right].animate.shift(LEFT * gap + UP * 0.6),
                          *swap_in,
                          run_time={{stepTime}})
                self.play(order[left].animate.shift(DOWN * 0.6),
                          order[right].animate.shift(DOWN * 0.6),
                          run_time=0.25)
                held = order[left]
                order[left] = order[right]
                order[right] = held
            elif kind == 2:
                self.play(order[left][0].animate.set_stroke(good, width=3).set_fill(good, opacity=0.85),
                          *swap_in,
                          run_time={{stepTime}})
            else:
                self.play(order[left][0].animate.set_stroke(amber, width=3).set_fill(amber, opacity=0.9),
                          order[right][0].animate.set_stroke(amber, width=3).set_fill(amber, opacity=0.9),
                          *swap_in,
                          run_time={{stepTime}})
                self.play(order[left][0].animate.set_stroke(base, width=2).set_fill(base, opacity=0.85),
                          order[right][0].animate.set_stroke(base, width=2).set_fill(base, opacity=0.85),
                          run_time=0.2)
            note = fresh
            self.wait({{stepPause}})

        finish = []
        for item in order:
            finish.append(item[0].animate.set_stroke(good, width=3).set_fill(good, opacity=0.85))
        self.play(*finish, run_time=0.8)
        self.wait({{tail}})
