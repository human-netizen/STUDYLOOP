from manim import *


class GeneratedScene(Scene):
    def construct(self):
        ink = "#e7edf2"
        dim = "#5b6976"
        off = "#2f3a44"
        panel = "#161b1f"
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
        cells = VGroup()
        for value in values:
            box = Square(side_length=1.1, stroke_width=3, color=dim,
                         fill_color=panel, fill_opacity=1.0)
            label = Text(str(value), font_size=30, color=ink)
            label.move_to(box.get_center())
            cells.add(VGroup(box, label))
        cells.arrange(RIGHT, buff=0.16)
        cells.set_width(min(12.2, 1.3 * len(values)))
        cells.move_to(ORIGIN).shift(DOWN * 0.35)

        marks = VGroup()
        for position in range(len(values)):
            mark = Text(str(position), font_size=20, color=off)
            mark.next_to(cells[position], DOWN, buff=0.28)
            marks.add(mark)

        goal = Text({{goal}}, font_size=30, color=amber)
        goal.next_to(cells, UP, buff=1.15)

        pointer = Triangle(color=amber, fill_color=amber, fill_opacity=1.0)
        pointer.set_width(0.34)
        pointer.rotate(PI)
        pointer.next_to(cells[0], UP, buff=0.24)

        note = Text({{opening}}, font_size=26, color=dim)
        note.next_to(cells, DOWN, buff=1.0)

        self.play(Create(cells), run_time=1.2)
        self.play(FadeIn(marks, shift=UP * 0.12), FadeIn(goal, shift=DOWN * 0.12), run_time=0.6)
        self.play(FadeIn(note), run_time=0.4)
        self.wait({{settle}})

        steps = {{steps}}
        for step in steps:
            low = step[0]
            high = step[1]
            focus = step[2]
            done = step[3]
            words = step[4]

            moves = []
            for position in range(len(values)):
                inside = position >= low and position <= high
                if position == focus and done == 1:
                    moves.append(cells[position][0].animate.set_stroke(good, width=7).set_fill(good, opacity=0.28))
                    moves.append(cells[position][1].animate.set_color(ink))
                elif position == focus:
                    moves.append(cells[position][0].animate.set_stroke(amber, width=7).set_fill(amber, opacity=0.18))
                    moves.append(cells[position][1].animate.set_color(ink))
                elif inside:
                    moves.append(cells[position][0].animate.set_stroke(dim, width=3).set_fill(panel, opacity=1.0))
                    moves.append(cells[position][1].animate.set_color(ink))
                else:
                    moves.append(cells[position][0].animate.set_stroke(off, width=2).set_fill(panel, opacity=1.0))
                    moves.append(cells[position][1].animate.set_color(off))

            fresh = Text(words, font_size=26, color=ink)
            fresh.move_to(note)
            moves.append(Succession(FadeOut(note, shift=DOWN * 0.2),
                                    FadeIn(fresh, shift=DOWN * 0.2)))
            moves.append(pointer.animate.next_to(cells[focus], UP, buff=0.24))

            self.play(*moves, run_time={{stepTime}})
            note = fresh
            self.wait({{stepPause}})

        self.play(FadeOut(pointer), run_time=0.4)
        self.wait({{tail}})
