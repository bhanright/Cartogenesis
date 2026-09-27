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
        MapStyle.ATLAS to 442544023,
        MapStyle.VELLUM to -20084895,
        MapStyle.INK_WASH to -1739493389,
        MapStyle.NAUTICAL to -1855887935,
        MapStyle.MIDNIGHT to 460929282,
        MapStyle.SCHOOLROOM to -954242509,
        MapStyle.VERDANT to -975801195,
        MapStyle.SCROLL to 1273187120,
        MapStyle.PEN_AND_INK to 125278323,
        MapStyle.MARS to -443663868,
        MapStyle.NATURAL to -713589118,
        MapStyle.CLEAR to 73428629
    )
}
