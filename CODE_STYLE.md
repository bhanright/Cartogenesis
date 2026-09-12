# Code style

Almost all of this code was written by agents, and it showed: single-letter names, dense
arithmetic, and KDoc that carried a whole chunk's history — what was tried, what was reverted, what
was measured on which seed — instead of the intent and the invariant. These are the rules the C2
sweep applies to fix that, and the rules anything written after it follows. `REALISM_PLAN.md`
ground rule 9 is the short form; this is the long one.

Every before/after below is a real line from `SeaLevelStage.kt`, the file the sweep was calibrated
on.

## 1. Names are words

Not abbreviations, not symbols. A reader should not have to hold a glossary.

```kotlin
val w = base.relativeElevation.width          // before
val cellsAcross = beforeShelf.relativeElevation.width   // after
```

## 2. A name carries its unit when it has one

The unit is the part that is wrong most often, so it belongs in the name rather than in a comment
three lines up.

```kotlin
val cap = (size * sea.enclosedSeaMaxShare).toInt()                        // before
val largestLakeCells = (cellCount * seaConfig.enclosedSeaMaxShare).toInt()  // after

fun apply(height: FloatField, seaLevel: Float, lowstand: Float = 0f)                   // before
fun apply(height: FloatField, seaLevelFraction: Float, lowstandShareOfRelief: Float = 0f)  // after
```

`reachCells`, `lapseRateCPerKm`, `shorelineHeight`, `reliefAboveShoreline`, `depthBelowShoreline`
are all this rule.

## 3. A single letter lives only inside a loop of a few lines

A loop body of two or three lines may say `i`. A loop body of a dozen lines, or an index that
crosses a function boundary, may not.

```kotlin
for (i in 0 until size) { val v = height.data[i] ... }        // before, twelve lines
for (cell in 0 until cellCount) { val groundHeight = height.data[cell] ... }   // after
```

## 4. A function's name says what it returns or what it does

```kotlin
private fun cutAt(...)      // before: cuts what, and returns what?
private fun landAndWaterAt(...)   // after

private fun enclose(...)                    // before
private fun markUnreachableWaterAsLand(...)  // after
```

`thresholdAtRank` was already right and did not move: it says what it returns and at what.

## 5. A comment says why the code is as it is, and what invariant it keeps

One sentence to three. Not what the next line does — the next line already says that.

```kotlin
// before
// Euclidean distance to the nearest land cell, by jump flooding: the three bands below are
// read straight off it, so the shelf break is one of this field's iso-contours and used to
// inherit the octagon the chamfer transform's contours are. See [JumpFloodDistance].

// after
// Euclidean distance to the nearest land cell, by jump flooding: the three bands below are
// read straight off it, so the shelf break is one of this field's iso-contours.
```

## 6. A comment that narrates mechanics line by line is deleted

If the code is hard enough to need a running commentary, the fix is to name things (rules 1 to 4),
not to annotate them.

## 7. Measurement history goes to the ledger or to `GEOGRAPHY.md`, and the code points at it

Which chunk did what, what was tried and reverted, what a figure measured on which seed at which
resolution: none of that is the code's job, and all of it is already written down. Leave a pointer
where it was.

```kotlin
// before, on a private const
 * Eight, from where the retreat stops rather than from where any guard turns green. Measured on
 * seed 718106 at 512, the largest drowned basin's filled area over the passes runs 1883, 1195,
 * 985, 838, 663, 515, 405, 366, 366 cells, its surface coming down from 0.227 of the land's
 * relief above the shoreline to 0.018 — flat from the seventh, and a ninth moves neither
 * figure. Seed 99's largest goes 1486 cells to 141 in a single pass, because [...]

// after
 * Eight is where the retreat stops rather than where a guard turns green: the largest drowned
 * basin measured is already flat by the seventh pass and a ninth moves neither its area nor its
 * surface. Not more, because each pass is a priority flood and a D8 route over the whole grid.
 * See REALISM_PLAN.md, H5b, for the pass-by-pass figures.
```

Before deleting a passage, check that it is written down somewhere. Most of it already is —
in `GEOGRAPHY.md`, in the config property's own KDoc, or in the plan's ledger row. If it is not,
add it there first.

Drop the chunk labels from the code too: `// H5b: and the basins the line above ...` becomes
`// The basins the line above ...`. The chunk that wrote a line is in the git history and in the
ledger, and a reader two years from now will not know what H5b was.

## 8. A magic number becomes a named constant with its derivation beside it

```kotlin
val coastDepth = -0.02f    // before, inline in a function

// after, a private const with the reason it is that number and not another
/**
 * Depth of the continental shelf right at the coast, in [SeaLevelResult.relativeElevation] units.
 *
 * Shallower than [SeaConfig.shelfDepth] at the shelf break, so the plateau slopes seaward instead
 * of being a dead-flat plain up to the shore; and shallower than the -0.12 that [ClimateStage]
 * uses for `SHALLOW_OCEAN`, so the whole plateau is drawn as shallow water.
 */
private const val SHELF_DEPTH_AT_COAST = -0.02f
```

A constant's *name* is a bound or a role, not just the number's story: `BINS` became
`HISTOGRAM_BINS`, `POST_CUT_PASSES` became `MAX_POST_CUT_OUTLET_PASSES` because eight is a ceiling
and not a count.

## 9. KDoc on a public function states its inputs, its output, its units and its invariant

In prose or in `@param`/`@return`, whichever reads better — but all four, and nothing else.

```kotlin
/**
 * The percentile cut on its own, without the three rules that [apply] runs on top of it.
 *
 * [seaLevelFraction] is the share of the world's cells to put under water, clamped to 0..1.
 * [lowstandShareOfRelief] then drops the shoreline below where the percentile puts it, as a
 * fraction of the land's relief above it; at zero the arithmetic is the plain percentile cut,
 * to the last bit.
 * [...]
 */
```

A public data class earns a line per property. `val isLand: BooleanArray` with nothing beside it
does not say that it is row-major and one entry per cell.

## What does not change

- **Serialised names.** Every `@Serializable` property in `WorldGenConfig`, the save header and the
  overrides keeps its wire name. If the Kotlin name has to move, `@SerialName` holds the old one,
  so every 1.x and 2.x save opens unchanged. The section names in `WorldSections` are wire names
  too.
- **Shader source.** GLSL and WGSL identifiers are the shader's, and the uniform names the host
  code binds by string go with them.
- **Public entry points the launchers call.** The desktop launcher's and the web page's entry
  points are renamed only together with their callers, in the same commit.
- **Anything that would move a bit.** A rename that changes a generated world is not a rename.
  Every sweep commit is checked against `WorldFingerprintTest` before and after; if a change cannot
  be made without moving a bit, it is left alone and reported.

## Order of the sweep

The shared model first — `WorldMap`, `WorldGenConfig`, the stage results — by one agent, because
those names reach every file. Then the pipeline stages, cartography, ui and the two launchers in
parallel, each merged behind a fingerprint check. A stage file does not rename a shared-model
property on its own; it lists the rename it wants and the model pass makes it.
