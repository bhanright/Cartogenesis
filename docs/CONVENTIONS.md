# Conventions

The rules code in this repository follows. Each is stated as a rule with one example; the examples
are real lines from `SeaLevelStage.kt`.

## 1. Names are words, not abbreviations or symbols

A reader should not have to hold a glossary.

```kotlin
val w = base.relativeElevation.width                     // no
val cellsAcross = beforeShelf.relativeElevation.width    // yes
```

## 2. A name carries its unit when it has one

The unit is the part that is wrong most often, so it belongs in the name rather than in a comment
three lines up.

```kotlin
fun apply(height: FloatField, seaLevel: Float, lowstand: Float = 0f)                       // no
fun apply(height: FloatField, seaLevelFraction: Float, lowstandShareOfRelief: Float = 0f)  // yes
```

`reachCells`, `lapseRateCPerKm`, `shorelineHeight`, `reliefAboveShoreline` and
`depthBelowShoreline` are all this rule.

## 3. A single letter lives only inside a loop of a few lines

A loop body of two or three lines may say `i`. A loop body of a dozen lines, or an index that
crosses a function boundary, may not.

```kotlin
for (i in 0 until size) { val v = height.data[i] ... }                       // no, twelve lines
for (cell in 0 until cellCount) { val groundHeight = height.data[cell] ... } // yes
```

## 4. A function's name says what it returns or what it does

```kotlin
private fun enclose(...)                     // no: encloses what, in what?
private fun markUnreachableWaterAsLand(...)  // yes
```

`thresholdAtRank` is already this rule: it says what it returns and at what.

## 5. A comment says why the code is as it is, and what invariant it keeps

One sentence to three. Not what the next line does — the next line already says that.

```kotlin
// Euclidean distance to the nearest land cell, by jump flooding: the three bands below are
// read straight off it, so the shelf break is one of this field's iso-contours.
```

## 6. A comment that narrates mechanics line by line is deleted

If the code is hard enough to need a running commentary, the fix is to name things — rules 1 to 4 —
not to annotate them.

## 7. Measurement history goes to the ledger or to GEOGRAPHY.md, and the code points at it

Which chunk did what, what was tried and reverted, and what a figure measured on which seed at
which resolution are not the code's job. Leave a pointer where the history was.

```kotlin
/**
 * Eight is where the retreat stops rather than where a guard turns green: the largest drowned
 * basin measured is already flat by the seventh pass and a ninth moves neither its area nor its
 * surface. Not more, because each pass is a priority flood and a D8 route over the whole grid.
 * See docs/DESIGN_LEDGER.md, H5b, for the pass-by-pass figures.
 */
```

Before deleting a passage, check that it is written down somewhere — in `docs/GEOGRAPHY.md`, in
the config property's own KDoc, or in the ledger row that owns it. If it is not, add it there
first.

Chunk labels do not belong in the code either: `// H5b: and the basins the line above ...` becomes
`// The basins the line above ...`. The exception is the pointer above, where the label is what
finds the row.

## 8. A magic number becomes a named constant with its derivation beside it

A constant's name is a bound or a role, not the number's story: `HISTOGRAM_BINS`, not `BINS`;
`MAX_POST_CUT_OUTLET_PASSES`, not `POST_CUT_PASSES`, because eight there is a ceiling and not a
count.

```kotlin
/**
 * Depth of the continental shelf right at the coast, in [SeaLevelResult.relativeElevation] units.
 *
 * Shallower than [SeaConfig.shelfDepth] at the shelf break, so the plateau slopes seaward instead
 * of being a dead-flat plain up to the shore; and shallower than the -0.12 that [ClimateStage]
 * uses for `SHALLOW_OCEAN`, so the whole plateau is drawn as shallow water.
 */
private const val SHELF_DEPTH_AT_COAST = -0.02f
```

## 9. KDoc on a public function states its inputs, its output, its units and its invariant

In prose or in `@param` and `@return`, whichever reads better — but all four, and nothing else. A
public data class earns a line per property: `val isLand: BooleanArray` with nothing beside it does
not say that it is row-major and one entry per cell.

```kotlin
/**
 * The percentile cut on its own, without the three rules that [apply] runs on top of it.
 *
 * [seaLevelFraction] is the share of the world's cells to put under water, clamped to 0..1.
 * [lowstandShareOfRelief] then drops the shoreline below where the percentile puts it, as a
 * fraction of the land's relief above it; at zero the arithmetic is the plain percentile cut,
 * to the last bit.
 */
```

## 10. Three kinds of name are not renamed

- **Shader source.** GLSL and WGSL identifiers are the shader's, and the uniform names the host
  code binds by string go with them.
- **Public entry points the launchers call.** The desktop launcher's and the web page's entry
  points are renamed only together with their callers, in the same commit.
- **Anything that would move a bit.** A rename that changes a generated world is not a rename.
  Check a change against `WorldFingerprintTest` before and after; if it cannot be made without
  moving a bit, leave it alone and say so.

## 11. Serialised names: when one moves, the format version moves with it

A `@Serializable` property's name is a wire name, and the section strings in `WorldSections` are
wire names too: rename one and every file written before it stops meaning what its keys say. The
header is parsed with unknown keys ignored, so an older file would not fail to open — it would open
with this build's *defaults* wherever a name has moved, which is a world quietly unlike the one
that was saved.

Nothing is distributed and there are no saves anyone needs to keep, so these names are swept like
any others rather than frozen behind `@SerialName`. The price is paid once, in the open: **when a
serialised name moves, `WorldCodec.FORMAT_VERSION` is bumped in the same commit**, and the codec
refuses every older file by name instead of misreading it. The checked-in gzip fixture is a whole
save, so it goes stale with the format and is regenerated in the same commit too.

Enforced by `WorldCodecTest`, whose cases *a save from an older format is refused rather than
misread* and *every per-cell array and every list comes back identical* both bind here, and by
`GzipInteroperabilityTest`, which reads the fixture on both platforms.

This rule is what changes if the program is ever distributed. From that point a wire name is
frozen, `@SerialName` holds the old one when the Kotlin name moves, and the version bump gives way
to a migration.

## 12. A new config section is declared to the reuse chain

If a stage reads a new section of `WorldGenConfig`, add it to that stage's guard in
`WorldGenerationEngine` and to the variant list in `IncrementalReuseTest`, which compares reuse
against fresh generation for every section. Without both, a settings edit that should recompute a
stage silently reuses the stored one.

## 13. Nothing on the map carries the grid's geometry

Rectangles, squares, edges running straight along a row, a column or a diagonal, right-angle
corners, perfect circles and arcs, concentric terraces and ruled lines are the shapes a reader
recognises at once as machine-made, and every one found in this project has had a cause in the
code, never in the geography. The causes were independent of one another, which is why this is a
rule about operators and outputs rather than about any one stage.

- **Shapes come from the terrain or from an isotropic operator.** A mask, a basin, a stamp, a
  margin or a boundary is shaped by the ground it lies on, or by an operator with no preferred
  direction: a Euclidean distance field, a disc or Gaussian kernel, a solved potential. It is not
  made by thresholding a square or octagonal window, by a chessboard or Manhattan distance, by a
  union of fixed blocks, by a breadth-first ring, by a nearest-seed partition, or by stamping a
  rectangle, a disc or any other primitive.
- **An axis you cannot avoid is guarded for isotropy.** The grid itself and D8 routing have
  preferred bearings. Where an operator with one is the right tool, its output is tested so that
  features at the grid's bearings are neither more common nor longer than features at other
  bearings, and the test is shown failing on a version that stamps the shape.
- **Every layer the map draws goes through the geometry guard.** Coasts, lakes, rivers, ice,
  basins, deltas, borders and any new layer are measured for straight runs by bearing,
  rectangularity, right angles and concentric rings, per merge at 512 and in the nightly tier at
  2048, where the artefacts show. The chunk that adds a layer adds it to the guard. A bar there is
  derived from the isotropy of natural outlines, not from what a current world happens to produce.
  The guard is `GeometryGuardTest` at 512 and `GeometryGuardAuditTest` at 2048 (`:cartography:audit`),
  its layers listed in `MapLayers` and its detectors shown on their controls in
  `GeometryControlTest`, all under `cartography/src/jvmTest`; what fails today is recorded by
  finding (`GeometryFindings`) and by the place and figure of each violation, and what is too small
  to measure on today's worlds is listed, in `GeometryExpectations`.
