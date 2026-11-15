#!/usr/bin/env python3
"""Append curated search keywords for the Emoji 16.0/17.0/18.0 additions to
emoji_keywords_en.tsv (loaded first by EmojiPanel, so these outrank the raw
CLDR-name rows in the generated catalog table). One keyword per line, one emoji
per line; duplicate (keyword, emoji) pairs are skipped, and keywords that already
exist simply get an extra row (the loader accumulates them)."""

import os

TSV = "/home/z/my-project/work/shade-extract/Shade-TheKeyboard-main/app/src/main/assets/emoji/emoji_keywords_en.tsv"

CURATED = [
    # Emoji 18.0 (September 2026)
    ("🫫", ["crumble", "crumbling", "shatter", "shattering", "falling apart",
            "breaking down", "facade", "reveal"]),
    ("🫹", ["thumb left", "left thumb", "thumbs left"]),
    ("🫺", ["thumb right", "right thumb", "thumbs right"]),
    ("🫌", ["monarch", "milkweed butterfly", "migration"]),
    ("🫝", ["gherkin", "dill", "brined", "kosher dill", "cornichon"]),
    ("🛙", ["beacon", "light tower", "navigation light", "maritime tower"]),
    ("🪋", ["fireball", "asteroid", "space rock", "bolide", "falling rock"]),
    ("🪌", ["rubber", "rub out", "stationery eraser", "wipe out", "correction"]),
    ("🪍", ["bug net", "butterfly net", "catch net", "scoop net"]),
    # Emoji 17.0 (2025)
    ("🫪", ["warp", "warped", "glitch", "melted", "dizzy face", "trippy"]),
    ("🫈", ["bigfoot", "sasquatch", "yeti", "cryptid", "abominable snowman"]),
    ("🫯", ["brawl", "scuffle", "cartoon fight", "dust cloud", "punch up", "rumble"]),
    ("🫍", ["killer whale", "grampus", "blackfish", "shamu"]),
    ("🧑‍🩰", ["ballet", "ballerina", "pirouette", "ballet shoes"]),
    ("🛘", ["rockslide", "mudslide", "falling rocks", "slope collapse"]),
    ("🪎", ["pirate chest", "loot", "gold chest", "buried treasure", "pirate gold"]),
    ("🪊", ["brass instrument", "brass horn", "slide horn", "big band"]),
    # Emoji 16.0 (2024)
    ("🫩", ["exhausted", "sleep deprived", "insomnia", "dark circles", "worn out",
            "panda eyes"]),
    ("🫆", ["thumbprint", "biometric", "dabs", "touch id"]),
    ("🪾", ["bare tree", "winter tree", "dead tree", "leaf fall"]),
    ("🫜", ["turnip", "beet", "swede", "rutabaga", "beetroot"]),
    ("🪉", ["string instrument", "harpist", "lyre", "concert harp"]),
    ("🪏", ["spade", "dig", "garden tool", "scoop", "entrench"]),
    ("🫟", ["paint splatter", "splash", "blob", "splodge", "paint blob", "gules"]),
    # Flag added to the chart with Emoji 15.1 subdivision flags
    ("🇨🇶", ["sark"]),
]


def main():
    existing = set()
    with open(TSV, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                existing.add((parts[0].strip().lower(), parts[1].strip()))

    add = []
    for emoji, keywords in CURATED:
        for kw in keywords:
            if (kw, emoji) not in existing:
                add.append(f"{kw}\t{emoji}")
    with open(TSV, "a", encoding="utf-8") as f:
        f.write("\n".join(add) + "\n")
    print(f"appended {len(add)} keyword rows to {os.path.basename(TSV)}")
    for row in add[:10]:
        print("  ", row)


if __name__ == "__main__":
    main()
