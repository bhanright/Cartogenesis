# Worldforge

An Android app that procedurally generates fantasy world maps.

## The pipeline

Each stage feeds the next, and all of them are deterministic for a given seed.

1. **Normal map** — seeded Perlin noise generates a random surface-gradient field.
2. **Height map** — the gradient field is integrated into elevation using
   [Frankot–Chellappa](https://doi.org/10.1109/34.3909) least-squares integration via a 2D FFT.
3. **Tectonics** — the world is divided into drifting Voronoi plates. Boundaries are classified as
   convergent, divergent, or transform from the plates' relative motion, and elevation is deformed
   accordingly: mountain ranges where continents collide, volcanic arcs and trenches at subduction
   zones, ridges and rift valleys where plates separate.
4. **Sea level** — everything below a chosen elevation percentile floods.
5. **Climate** — temperature from latitude and altitude; rainfall by marching moist air along
   prevailing wind bands, so windward slopes soak and leeward slopes fall into rain shadow. Biomes
   come from the resulting temperature/rainfall pairing.
6. **Rivers** — depressions are filled with priority-flood so no water dead-ends inland, flow is
   routed downhill (D8), rainfall accumulates downstream, and channels are traced to the coast.

## Modules

- **`:worldgen`** — the whole generation pipeline as Kotlin Multiplatform, with no platform
  dependencies at all. Builds for the JVM, WebAssembly and JS; see **Multiplatform status**.
- **`:app`** — Android: Compose UI, rendering, the atlas, saving, and HD export.

## Building

Built and verified against Android Studio 2026.1.3 (JDK 25), AGP 9.3.1, Kotlin 2.4.10,
Gradle 9.7.1, compileSdk 37.

Note that AGP 9 has **built-in Kotlin support**, so `:app` deliberately does not apply the
`org.jetbrains.kotlin.android` plugin — applying it is now an error. `:worldgen` uses
`kotlin.multiplatform`; the Android app consumes its `jvm` target, which resolves with no extra
configuration.

```bash
./gradlew :worldgen:jvmTest
```

```bash
./gradlew :worldgen:wasmJsNodeTest
```

```bash
./gradlew :app:assembleDebug
```

## Realms, the atlas, and saving

After the physical world is generated, realms are settled on it. Origins are seeded on the most
liveable ground and grown outwards by cheapest-cost expansion, where mountains, deserts, ice and
open water all cost more to cross than farmland — so borders end up on ranges, rivers and coasts
without ever being told to follow them. `WildernessMode` decides whether the leftovers get carved
up too (`CLAIM_ALL_LAND`) or stay unclaimed (`LEAVE_WILDERNESS`); it only changes the leftovers,
since cheapest-path assignment gives the same borders between settled regions either way.

Landmarks — monster lairs, ruins, hazards, resources — are then placed on terrain that suits them
and biased hard toward country no realm claims, which is what gives unclaimed wilderness a point.

The atlas derives what it can from the map: population from the carrying capacity of the land
actually held, exports from the biomes people actually farm (habitability-weighted, not raw area —
a realm can be mostly polar waste and still be a temperate farming nation), imports from the
staples it cannot supply. Names come from per-culture syllable inventories, so neighbouring realms
sound like different peoples.

**All of it is a starting point.** Generated values sit under a `WorldOverrides` layer and every
one can be replaced by the user. Anything untouched keeps following the generator, including after
a regeneration, so a new setting does not wipe out edits. Any new generated attribute needs an
override path and a line in `WorldStore`, or it will not survive a save.

A save records the seed, the settings and the overrides — not the world, which is rebuilt from
them. A whole world is about 1.5KB. `WorldStore` reads forgivingly, falling back to current
defaults for any missing field, so saves made before a setting existed still open.

## Installing on a phone

Requires Android 10 (API 29) or newer. The APK is universal — one file covers every phone ABI.

### 1. Create a signing key (once)

This key is the app's permanent identity: Android only allows an update to install over an
existing app if both were signed with the same key. Keep the `.jks` file and its password safe and
backed up — losing them means future versions can only be installed by uninstalling first.

```bash
keytool -genkeypair -v -keystore worldforge-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias worldforge
```

`keytool` lives in Android Studio's JDK, so on this machine run it as
`"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"`. It will prompt for a password and
a few identity fields; only the password matters for a personal build.

### 2. Point the build at it

Create `keystore.properties` in the project root (it is gitignored, along with `*.jks`):

```properties
storeFile=worldforge-release.jks
storePassword=<the password you chose>
keyAlias=worldforge
keyPassword=<the same password, unless you set a separate key password>
```

### 3. Build

```bash
./gradlew :app:assembleRelease
```

The signed APK lands at `app/build/outputs/apk/release/app-release.apk`, around 2.4MB. Without
`keystore.properties` the same command still works but produces `app-release-unsigned.apk`, which
phones will refuse to install.

### 4. Get it onto the phone

Over USB with developer options and USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Otherwise copy the APK across by any means — cable, Drive, email — open it in the phone's file
manager, and allow install from unknown sources when prompted.

## Resolution

Export re-runs the whole pipeline at the target size rather than upscaling the preview, so a
bigger map means genuinely more detail. That only holds because `WorldGenConfig.atResolution`
rescales the settings that are measured in cells — the mountain-belt falloff and the per-cell
rainfall rate. Anything new that is expressed in cells rather than as a frequency or a fraction of
the map needs adding there, or exports will drift in character from what the preview showed.

Export is capped at 2048. Generation holds a dozen or so full-resolution float fields plus the
integrator's FFT buffers; at 4096 a single one of those buffers is 134MB and the process runs out
of heap even with `largeHeap`. Going higher needs the pipeline reworked to run in tiles.

## Looking at the output

`DebugMapDump` in the `:worldgen` test source set renders worlds straight to PNGs under
`worldgen/build/maps/`, so generation can be inspected without an emulator — one image per
pipeline stage (normals, elevation, plates, biome, rainfall, temperature) plus the composed map.
It also prints river-network statistics, and includes a parameter sweep for judging the trade-off
between terrain roughness and tectonic influence by eye.

Since it runs as part of `:worldgen:jvmTest`, the maps refresh on every JVM test run.

## Multiplatform status

`:worldgen` is a Kotlin Multiplatform module targeting **jvm** (what the Android app consumes),
**wasmJs**, and **js**. The whole correctness suite lives in `commonTest` and runs on every target;
`DebugMapDump` stays in `jvmTest` because it renders PNGs through `java.awt`.

`WorldFingerprintTest` prints a checksum of a generated world, built from raw float bits so it
catches a difference in the last bit. Run it on two targets and compare — it is the check that says
whether a world saved on one platform reopens identically on another.

Measured on 2026-08-23, seed 42 at 128x128:

| Target | Shared suite | Elevation fingerprint |
|---|---|---|
| jvm | 16/16 pass | `4283446780793226894` |
| wasmJs | 16/16 pass | `4283446780793226894` |
| js | 15/16 pass | `-2412777715130564537` |

**Wasm is bit-identical to the JVM. Kotlin/JS is not.** JS routes `sin`/`cos`/`pow` through
JavaScript's `Math`, which differs from the JVM in the last bit; the FFT compounds that, and the
same seed produces a different world — different enough to fail the resolution-consistency guard.
Since a save stores a seed rather than a world, **Wasm is the target that keeps saves portable**,
and that matters more here than the usual performance argument.
