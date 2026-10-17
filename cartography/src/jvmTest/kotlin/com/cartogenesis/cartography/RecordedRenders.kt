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
        MapStyle.SATELLITE to -719974167,
        MapStyle.ATLAS to -1139279109,
        MapStyle.VELLUM to -1032314751,
        MapStyle.INK_WASH to -727621339,
        MapStyle.NAUTICAL to 226319712,
        MapStyle.MIDNIGHT to 302229847,
        MapStyle.SCHOOLROOM to 12240504,
        MapStyle.VERDANT to 71702400,
        MapStyle.SCROLL to -764089195,
        MapStyle.PEN_AND_INK to 1792347341,
        MapStyle.MARS to -1177216953,
        MapStyle.NATURAL to -1489754931,
        MapStyle.CLEAR to 1477035965
    )
}
