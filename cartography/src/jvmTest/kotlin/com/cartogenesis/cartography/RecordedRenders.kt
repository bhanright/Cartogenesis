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
        MapStyle.ATLAS to 1115341893,
        MapStyle.VELLUM to -397856816,
        MapStyle.INK_WASH to 1409884873,
        MapStyle.NAUTICAL to -1638965323,
        MapStyle.MIDNIGHT to 1667264350,
        MapStyle.SCHOOLROOM to 137460771,
        MapStyle.VERDANT to 886726118,
        MapStyle.SCROLL to -1624599346,
        MapStyle.PEN_AND_INK to 1652986039,
        MapStyle.MARS to -769999750,
        MapStyle.NATURAL to 1901784110,
        MapStyle.CLEAR to -1759632953
    )
}
