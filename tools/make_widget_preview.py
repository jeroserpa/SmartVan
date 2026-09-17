#!/usr/bin/env python3
"""make_widget_preview.py - generate the Android widget picker preview.

app/src/main/res/layout/van_widget_preview.xml is van_widget.xml with sample
values filled in, so the picker shows what the widget looks like with data
instead of a row of dashes. Generated rather than hand-copied so the two
layouts cannot drift. Run after editing van_widget.xml:

    python tools/make_widget_preview.py

Stdlib only.
"""

import re
from pathlib import Path

LAYOUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "layout"

SAMPLE = {
    "w_state": "AC on",
    "w_soc": "78%",
    "w_out": "48 W out",
    "w_in": "310 W in",
    "w_fridge": "4.6 °C fridge",
    "w_cabin": "24.0 °C cabin",
    "w_reason": "fridge block",
    "w_age": "14:32",
}


def edit(s, vid, fn):
    m = re.search(r'<(\w+)\s[^>]*android:id="@\+id/%s"[^>]*/>' % vid, s, re.S)
    if not m:
        raise SystemExit(f"van_widget.xml has no view with id {vid}")
    return s[:m.start()] + fn(m.group(0)) + s[m.end():]


def indent_of(element, attr):
    """The leading whitespace of `attr`'s line, so inserted attributes line up
    whatever depth the view sits at. Hard-coding it broke silently the first
    time the layout was re-nested."""
    m = re.search(r"\n([ \t]*)" + re.escape(attr), element)
    return m.group(1) if m else "    "


def set_attr(attr, value, before):
    """Set `attr`, or insert it above `before` at that line's own indent."""
    def fn(e):
        if f"{attr}=" in e:
            return re.sub(re.escape(attr) + r'="[^"]*"', f'{attr}="{value}"', e)
        pad = indent_of(e, before)
        return e.replace(before, f'{attr}="{value}"\n{pad}{before}', 1)
    return fn


def set_text(text):
    return set_attr("android:text", text, "android:maxLines")


def main():
    s = (LAYOUT / "van_widget.xml").read_text(encoding="utf-8")
    s = re.sub(r"<!-- Full widget.*?-->",
               "<!-- GENERATED from van_widget.xml by tools/make_widget_preview.py, for the\n"
               "     widget picker. Do not edit; regenerate. -->", s, count=1, flags=re.S)
    for vid, text in SAMPLE.items():
        s = edit(s, vid, set_text(text))
    s = edit(s, "w_state", lambda e: e.replace("@color/muted", "@color/warn"))
    s = edit(s, "w_dot", lambda e: e.replace("@color/dim", "@color/warn"))
    # The liquid background is a bitmap the app draws at run time, which the
    # picker never gets to run. A static vector stands in for it at the same
    # 78 % the rest of the sample shows.
    s = edit(s, "w_fill", set_attr(
        "android:src", "@drawable/battery_fill_preview",
        "android:importantForAccessibility"))
    (LAYOUT / "van_widget_preview.xml").write_text(s, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
