#!/usr/bin/env python3
"""Builds the landing page's five web fonts from the application's own faces.

The page sets its type in the same five faces the application bundles, under
ui/src/commonMain/composeResources/font. Served as they are, those TrueType files are about 1.1 MB,
most of what the page fetches as it loads. This writes each one as a WOFF2 cut to the characters a
Latin-script page can use, which is about an eighth of that, and records in faces.json which
application file each was built from and which characters it keeps. SiteFontsTest reads the record:
it fails when an application face changes and this has not been run again, and when the page or
the roadmap uses a character a subset has dropped.

Two families are renamed, because a subset is a Modified Version under the SIL Open Font License
and IBM Plex reserves the name "Plex": a Modified Version may not carry a Reserved Font Name without
the copyright holder's written permission. Spectral reserves none and keeps its name. The copyright
and licence fields of every face are kept, and the licence texts are published beside the files.

Needs fontTools and brotli (pip install fonttools brotli). Run from the repository's root:

    python3 site/fonts/build_web_fonts.py
"""

import hashlib
import json
import os

from fontTools import subset
from fontTools.ttLib import TTFont

SOURCE_DIRECTORY = "ui/src/commonMain/composeResources/font"
OUTPUT_DIRECTORY = "site/fonts"

# Each face the page sets type in, with the family name it is published under: the application's
# own for Spectral, a neutral one for the two IBM Plex families (see the licence note above).
FACES = [
    ("spectral_regular", "Spectral"),
    ("spectral_semibold", "Spectral"),
    ("plex_sans_regular", "Cartogenesis Sans"),
    ("plex_sans_medium", "Cartogenesis Sans"),
    ("plex_mono_regular", "Cartogenesis Mono"),
]

# What a Latin-script page can use: ASCII, Latin-1 and Latin Extended-A, general punctuation, the
# arrows, the minus sign, the euro and the few symbols the page draws its controls with. Wider than
# the page uses today, so an edit to the copy rarely needs this run again; SiteFontsTest says when
# one does.
KEPT_CODE_POINTS = (
    list(range(0x20, 0x7F))
    + list(range(0xA0, 0x180))
    + list(range(0x2010, 0x2028))
    + list(range(0x2030, 0x203B))
    + list(range(0x2190, 0x2200))
    + [0x2212, 0x20AC, 0x25B4, 0x25B8, 0x25BE, 0x2713, 0x2715]
)

# The name records a family's name is written in: family, full name, PostScript name, and the
# typographic family, whose style name (17) and subfamily (2) are left as they are.
FAMILY = 1
UNIQUE_ID = 3
FULL_NAME = 4
POSTSCRIPT_NAME = 6
TYPOGRAPHIC_FAMILY = 16
TYPOGRAPHIC_STYLE = 17
SUBFAMILY = 2


def sha256_of(path):
    with open(path, "rb") as file:
        return hashlib.sha256(file.read()).hexdigest()


def ranges_of(code_points):
    """[code_points], sorted, as inclusive [first, last] runs, which is how faces.json keeps them."""
    runs = []
    for code_point in code_points:
        if runs and runs[-1][1] == code_point - 1:
            runs[-1][1] = code_point
        else:
            runs.append([code_point, code_point])
    return runs


def renamed(font, family):
    """Writes [family] into every name record that carries the family's name."""
    name_table = font["name"]
    style = name_table.getDebugName(TYPOGRAPHIC_STYLE) or name_table.getDebugName(SUBFAMILY) or "Regular"
    full_name = f"{family} {style}"
    postscript = f"{family.replace(' ', '')}-{style.replace(' ', '')}"
    for record in name_table.names:
        if record.nameID in (FAMILY, TYPOGRAPHIC_FAMILY):
            record.string = family
        elif record.nameID in (FULL_NAME, UNIQUE_ID):
            record.string = full_name
        elif record.nameID == POSTSCRIPT_NAME:
            record.string = postscript
    # The FAMILY record (1) and SUBFAMILY (2) of a face outside the four classic styles name the
    # weight in the family ("IBM Plex Sans Medium", "Regular"); keep that split under the new name.
    for record in name_table.names:
        if record.nameID == FAMILY and style not in ("Regular", "Italic", "Bold", "Bold Italic"):
            record.string = f"{family} {style}"


def build():
    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.hinting = True
    options.notdef_outline = True

    record = {}
    for face, family in FACES:
        source = os.path.join(SOURCE_DIRECTORY, face + ".ttf")
        output = os.path.join(OUTPUT_DIRECTORY, face + ".woff2")
        # The source's own timestamps are kept, so the same face always cuts to the same bytes.
        font = TTFont(source, recalcTimestamp=False)
        subsetter = subset.Subsetter(options)
        subsetter.populate(unicodes=KEPT_CODE_POINTS)
        subsetter.subset(font)
        if family != font["name"].getDebugName(TYPOGRAPHIC_FAMILY) and family != font["name"].getDebugName(FAMILY):
            renamed(font, family)
        font.flavor = "woff2"
        font.save(output)
        kept = sorted(TTFont(output).getBestCmap().keys())
        record[face + ".woff2"] = {
            "family": family,
            "builtFrom": source,
            "sourceSha256": sha256_of(source),
            "sha256": sha256_of(output),
            "codePointRanges": ranges_of(kept),
        }
        print(f"{face}: {os.path.getsize(source)} bytes of TrueType to {os.path.getsize(output)} of WOFF2, "
              f"{len(kept)} characters, published as {family}")

    # One face to a block and one run to a line, so a change to what a face keeps reads as a diff.
    lines = ["{"]
    for index, (face, entry) in enumerate(record.items()):
        lines.append(f' "{face}": {{')
        for key in ("family", "builtFrom", "sourceSha256", "sha256"):
            lines.append(f'  "{key}": {json.dumps(entry[key])},')
        lines.append('  "codePointRanges": [')
        runs = entry["codePointRanges"]
        lines += [f"   [{first}, {last}]" + ("," if i < len(runs) - 1 else "") for i, (first, last) in enumerate(runs)]
        lines.append("  ]")
        lines.append(" }" + ("," if index < len(record) - 1 else ""))
    lines.append("}")
    with open(os.path.join(OUTPUT_DIRECTORY, "faces.json"), "w", encoding="utf-8") as file:
        file.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    build()
