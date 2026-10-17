# Landing-page translation catalogs

`../index.html` is the English template. `data-i18n` identifies an element's inner
HTML; `data-i18n-alt`, `data-i18n-aria-label`, and `data-i18n-content` identify
attributes. Keys are stable identifiers; adding text must not renumber existing
keys. Update the English template and `en.json` together, then all eight catalogs.

Text entries can contain inline markup. Keep tags, attributes, links, commands,
and code unchanged. Translate whole sentences. Attribute entries must not contain
markup or double quotation marks. Preserve substitution tokens such as `{step}`,
`{name}`, and `__BUNDLE_MB__`.

`roadmap` groups rows by release in `ROADMAP.md`. Each value is an ordered array:
a release may have multiple rows. English rows must match the source exactly.
`glossary` records recurring terms. The locale variants are simplified Chinese,
Hindi, general Spanish, Modern Standard Arabic, French, Bengali, Brazilian
Portuguese, and Russian. Release numbers, seed identifiers, commands, and file
names remain unchanged. Other displayed quantities use the page's locale.

The Kotlin functions in `desktop/build.gradle.kts` validate catalogs and render
all nine pages in `assembleSite`. They preserve markup and code and remove all
translation markers. JavaScript only runs the page's interactive controls; it
is not used to render or validate catalogs during the build. `/app/` remains
English. Language links never redirect based on the browser's preferences.

`SiteTranslationTemplateTest` checks catalog completeness and source invariants.
`SiteLocalizationAssemblyTest` checks the generated pages, language alternates,
structured data, downloads, fonts, shared contrast palette, and responsive layout.
Run the complete guard with `./gradlew :desktop:siteTest --no-daemon --console=plain`.
Catalogs and build inputs are not published.

Font sources are specified in `../fonts/faces.json`. Download the official Noto
source files and their SIL Open Font License texts before running
`python3 site/fonts/build_web_fonts.py` from the repository root. Source TrueType
files live in `fonts/sources/`, are ignored by version control, and are excluded
from publication. Publish the generated WOFF2 subsets with their licenses.
The generator collects each script's characters from its catalog, the template,
and the native language names; it also regenerates the shared Latin/Cyrillic faces.
Keep shaping tables when subsetting Arabic, Devanagari, and Bengali.
