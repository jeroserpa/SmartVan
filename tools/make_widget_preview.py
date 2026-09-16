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
    "w_out": "48 W",
    "w_in": "310 W",
    "w_fridge": "4.6 °C",
    "w_reason": "fridge block",
    "w_age": "Updated 14:32",
}


def edit(s, vid, fn):
    m = re.search(r'<(\w+)\s[^>]*android:id="@\+id/%s"[^>]*/>' % vid, s, re.S)
    if not m:
        raise SystemExit(f"van_widget.xml has no view with id {vid}")
    return s[:m.start()] + fn(m.group(0)) + s[m.end():]


def set_text(text):
    def fn(e):
        if "android:text=" in e:
            return re.sub(r'android:text="[^"]*"', f'android:text="{text}"', e)
        return e.replace("android:maxLines", f'android:text="{text}"\n            android:maxLines', 1)
    return fn


def main():
    s = (LAYOUT / "van_widget.xml").read_text(encoding="utf-8")
    s = re.sub(r"<!-- Full widget.*?-->",
               "<!-- GENERATED from van_widget.xml by tools/make_widget_preview.py, for the\n"
               "     widget picker. Do not edit; regenerate. -->", s, count=1, flags=re.S)
    for vid, text in SAMPLE.items():
        s = edit(s, vid, set_text(text))
    s = edit(s, "w_state", lambda e: e.replace("@color/muted", "@color/warn"))
    s = edit(s, "w_dot", lambda e: e.replace("@color/dim", "@color/warn"))
    s = edit(s, "w_bar_ok", lambda e: e.replace('android:visibility="gone"', 'android:progress="78"'))
    s = edit(s, "w_bar_dim", lambda e: e.replace('android:progress="0"', 'android:visibility="gone"'))
    (LAYOUT / "van_widget_preview.xml").write_text(s, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
