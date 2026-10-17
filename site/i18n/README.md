# Landing-page translation catalogs

`../index.html` is the English template. `data-i18n` identifies an element's inner
HTML; `data-i18n-alt`, `data-i18n-aria-label`, and `data-i18n-content` identify
attributes. Keys are stable identifiers: adding text must not renumber existing
keys. The catalog's `strings` object keeps the corresponding English text.

Text entries can contain inline markup. Keep tags, attributes, links, commands,
and code unchanged. Translate complete sentences, moving inline links with their
phrases where needed. Attribute entries must not contain markup or quotation
marks. Preserve placeholders such as `{step}`, `{name}`, and `__BUNDLE_MB__`.

`roadmap` groups rows by the release column in `ROADMAP.md`. A release can occur
more than once, so each value is an ordered array, including releases with only
one row. The English rows must match the source exactly. `glossary` records the
recurring terms used throughout each language.

`template.mjs` renders marked text and attributes and checks catalog shape. It
rejects changed markup, changed code, missing strings, and missing placeholders.
The template checks run with:

```
node --test desktop/src/test/kotlin/com/cartogenesis/desktop/SiteTranslationTemplateTest.mjs
```

They are also a prerequisite of `:desktop:siteTest`. Catalogs and these build
helpers are excluded from the published site.

The catalogs are currently an implementation checkpoint: only English exists.
Page generation, dynamic script strings, structured data, localized roadmap
insertion, language navigation, and the eight translated catalogs still need to
be connected to assembly. The `script.*`, `structured.*`, `language.*`, and
`translation.*` entries reserve those text surfaces; the template renderer does
not yet replace them.
