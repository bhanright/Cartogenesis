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

    /** Every style's 512 fantasy render of `PenAndInkTest.WORLD`, hashed. */
    val STYLES_AT_512: Map<MapStyle, Int> = mapOf(
        MapStyle.ATLAS to -1908380166,
        MapStyle.VELLUM to -974630935,
        MapStyle.INK_WASH to -85653603,
        MapStyle.NAUTICAL to 1305198837,
        MapStyle.MIDNIGHT to -2135143110,
        MapStyle.SCHOOLROOM to 1902146570,
        MapStyle.VERDANT to -1304692232,
        MapStyle.SCROLL to 1105212535,
        MapStyle.PEN_AND_INK to 1524051648,
        MapStyle.MARS to -1180875458,
        MapStyle.NATURAL to -604368194,
        MapStyle.CLEAR to 827121804
    )
}
