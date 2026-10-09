package com.cartogenesis.cartography

/**
 * The pinned render records: the one place a chunk that moves the ground re-takes them, in one
 * commit, with [RegenerateRecordedRenders] writing this file.
 *
 * A record is a change detector and not a claim about any colour. What it is good for is the
 * *shape* of a change: a chunk meaning to redraw one style can see here that it redrew only that
 * one, and a chunk that moves the land under all twelve - a new sky, a climate that reaches the
 * land ramp, a shoreline cut a different way - shows up as all twelve moving together. One entry
 * out of step with the rest is the thing to look at. No property captures that shape without a
 * record, which is why these stay as pins where C5 turned the others into properties; what the
 * picture itself must satisfy is asserted in `PenAndInkTest`, `ClearStyleTest` and the style
 * guards beside them, and how far a coastline moved is measured in `LittoralCoastTest`.
 *
 * Which chunk moved which record, and why, is the ledger's business: `docs/DESIGN_LEDGER.md`.
 */
internal object RecordedRenders {

    /** Every style's fantasy render of `PenAndInkTest.WORLD`, the gallery's world at 512 rows, hashed. */
    val STYLES_AT_512: Map<MapStyle, Int> = mapOf(
        MapStyle.ATLAS to 182972612,
        MapStyle.VELLUM to 1363790381,
        MapStyle.INK_WASH to 1448979620,
        MapStyle.NAUTICAL to -550133633,
        MapStyle.MIDNIGHT to -153689060,
        MapStyle.SCHOOLROOM to -1564993557,
        MapStyle.VERDANT to -223312161,
        MapStyle.SCROLL to 531787241,
        MapStyle.PEN_AND_INK to -736462826,
        MapStyle.MARS to 321753021,
        MapStyle.NATURAL to 1976404480,
        MapStyle.CLEAR to -818791346
    )
}
