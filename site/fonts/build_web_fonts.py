#!/usr/bin/env python3
"""Builds self-hosted web font subsets for all nine landing pages.

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
import re
import html

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

    with open(os.path.join(OUTPUT_DIRECTORY, "faces.json"), encoding="utf-8") as file:
        previous = json.load(file)
    catalogs = {}
    for language in ("en", "zh", "hi", "es", "ar", "fr", "bn", "pt", "ru"):
        with open(f"site/i18n/{language}.json", encoding="utf-8") as file:
            catalogs[language] = json.load(file)

    def characters(languages):
        with open("site/index.html", encoding="utf-8") as file:
            template = file.read()
        visible_template = re.sub(r"<(script|style)\b[^>]*>[\s\S]*?</\1>", "", template)
        texts = [visible_template, "English 简体中文 हिन्दी Español العربية Français বাংলা Português Русский", "0123456789٠١٢٣٤٥٦٧٨٩০১২৩৪৫৬৭৮৯०१२३४५६७८९.,:;–—−×+%() ↗ ▸ ✓"]
        for language in languages:
            catalog = catalogs[language]
            texts.extend(catalog["strings"].values())
            for release, rows in catalog["roadmap"].items():
                texts.append(release)
                texts.extend(rows)
        plain = html.unescape(re.sub(r"<[^>]*>", "", " ".join(texts)))
        return sorted({ord(character) for character in plain if ord(character) >= 32})

    record = {}
    all_faces = [(face, family, os.path.join(SOURCE_DIRECTORY, face + ".ttf"), list(catalogs)) for face, family in FACES]
    all_faces.extend((face.removesuffix(".woff2"), entry["family"], entry["builtFrom"], entry["languages"])
                     for face, entry in previous.items() if "languages" in entry)
    for face, family, source, languages in all_faces:
        output = os.path.join(OUTPUT_DIRECTORY, face + ".woff2")
        # The source's own timestamps are kept, so the same face always cuts to the same bytes.
        font = TTFont(source, recalcTimestamp=False)
        subsetter = subset.Subsetter(options)
        subsetter.populate(unicodes=characters(languages))
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
        if face + ".woff2" in previous and "languages" in previous[face + ".woff2"]:
            record[face + ".woff2"]["languages"] = languages
            record[face + ".woff2"]["sourceUrl"] = previous[face + ".woff2"]["sourceUrl"]
        print(f"{face}: {os.path.getsize(source)} bytes of TrueType to {os.path.getsize(output)} of WOFF2, "
              f"{len(kept)} characters, published as {family}")

    with open(os.path.join(OUTPUT_DIRECTORY, "faces.json"), "w", encoding="utf-8") as file:
        json.dump(record, file, ensure_ascii=False, indent=2)
        file.write("\n")


if __name__ == "__main__":
    build()
