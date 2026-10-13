#!/usr/bin/env python3
"""check_ui_names.py - hold ui/index.html and nodes/van-core.yaml to each other.

Two ways the page and the firmware have drifted apart, both found on the van
rather than on a desk:

  1. The page asks for an entity the firmware does not have. The value stays
     "--" and its control does nothing, silently (the fail-safe chip and the
     drive-inhibit switch were misnamed for weeks before M18).
  2. The firmware has a tunable the page cannot reach. "Fridge rest block",
     "Fridge run block" and "Fridge probe filter" could only be changed from
     ESPHome's own page - and the rest block turned out to be what sets the
     number of fridge runs a day (2026-10-13 log).

So: every id in the page's E and NUMS tables must name an entity in the node's
config, and every `number:` in the config must be on the page, unless it is
listed in HIDDEN below with the reason it is not.

    python tools/check_ui_names.py        exit 0 = in step, 1 = drift

Stdlib only. Names are compared as ESPHome slugs them for object ids, which is
also how the page keys its state (key() in ui/index.html).
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "ui" / "index.html"
NODE = ROOT / "nodes" / "van-core.yaml"
COMMON = ROOT / "common"

# Numbers deliberately not on the page. Empty today; say why if one is added.
HIDDEN = {}


def slug(name):
    return re.sub(r"[^a-z0-9_-]", "_", name.lower())


def substitutions(yaml):
    m = re.search(r"^substitutions:\n((?:[ \t]+.*\n|\n)+)", yaml, re.M)
    subs = {}
    if m:
        for k, v in re.findall(r"^[ \t]+([A-Za-z_]+):\s*\"?([^\"#\n]*?)\"?\s*(?:#.*)?$", m.group(1), re.M):
            subs[k] = v
    return subs


def entity_names(text, subs):
    names = set()
    for n in re.findall(r"^\s*name:\s*\"?([^\"\n#]+?)\"?\s*(?:#.*)?$", text, re.M):
        n = re.sub(r"\$\{(\w+)\}", lambda m: subs.get(m.group(1), m.group(0)), n)
        names.add(n.strip())
    return names


def number_names(yaml):
    """Names of the entities under the node's top-level `number:` key."""
    m = re.search(r"^number:\n(.*?)(?=^\S)", yaml, re.M | re.S)
    if not m:
        return set()
    return set(re.findall(r"^\s{4}name:\s*\"([^\"]+)\"", m.group(1), re.M))


def page_ids(html):
    def table(name):
        body = html.split("const %s = {" % name, 1)[1].split("};", 1)[0]
        return re.findall(r'"((?:sensor|binary_sensor|text_sensor|switch|number|select|button)-[^"]+)"', body)
    return table("E"), table("NUMS")


def main():
    yaml = NODE.read_text(encoding="utf-8")
    subs = substitutions(yaml)
    names = entity_names(yaml, subs)
    for f in COMMON.glob("*.yaml"):
        names |= entity_names(f.read_text(encoding="utf-8"), subs)
    slugs = {slug(n) for n in names}

    e_ids, num_ids = page_ids(PAGE.read_text(encoding="utf-8"))
    problems = []
    for i in e_ids + num_ids:
        if i.split("-", 1)[1] not in slugs:
            problems.append(f"page uses {i}, which nothing in {NODE.name} or common/ is named")

    on_page = {i.split("-", 1)[1] for i in num_ids}
    for n in sorted(number_names(yaml)):
        if slug(n) not in on_page and n not in HIDDEN:
            problems.append(f'number "{n}" is tunable on the node but not on the page '
                            f"(add it to NUMS, or to HIDDEN with a reason)")

    if problems:
        print("ui/index.html and nodes/van-core.yaml are out of step:")
        for p in problems:
            print("  -", p)
        return 1
    print(f"in step: {len(e_ids)} entities, {len(num_ids)} numbers, "
          f"{len(number_names(yaml))} node numbers all reachable")
    return 0


if __name__ == "__main__":
    sys.exit(main())
