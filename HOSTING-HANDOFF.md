# Cartogenesis — brief for hosting on a personal website

*Written 2026-08-24 to be handed to a different Claude session that knows the website but not this
project. Everything in the "Facts" sections was measured on the actual build, not recalled — the
verification steps are given so they can be repeated.*

---

## What I'm asking for

I have a finished browser application and a personal website. I'd like help putting the first on the
second: where it should live, what the server needs to be told, how to keep the page weight sane,
and how to present it to a visitor.

I do **not** need help building or changing the application. It is done and it works. What I need is
the hosting decision and the glue around it.

---

## What the thing is

**Cartogenesis** is a procedural fantasy world-map generator. You give it a seed and it simulates a
world: noise becomes a gradient field, which is integrated into terrain, which is pushed around by
tectonic plates, worn down by erosion, flooded to a sea level, stirred by ocean currents, given a
climate, carved by rivers, then settled by realms and by peoples. It draws the result as a map you
can pan, zoom, restyle and export.

It runs as a desktop app and as a browser app from one shared engine. **Only the browser build is
relevant here.**

Written in Kotlin Multiplatform with Compose Multiplatform, compiled to **WebAssembly**. The whole
interface is drawn on a single `<canvas>` — it is not HTML underneath.

---

## Facts about the build (measured)

### The artefact

A directory of **static files**. No server-side anything: no backend, no database, no API, no
runtime configuration, no environment variables. It is as static as a folder of images.

```
index.html                          1 KB    entry point
cartogenesis.js                   582 KB    loader/glue
a9dc5e31fe6bde545a85.wasm       3,873 KB    the application
bccfa839aa4b38489c76.wasm       8,208 KB    Skia (the graphics engine)
cartogenesis.js.map             1,710 KB    source map — safe to delete, see below
cartogenesis.js.LICENSE.txt         4 KB    third-party licence notices
composeResources/                  empty    empty directories, safe to delete
```

**Total 15 MB on disk; 12.4 MB excluding the source map.**

I also have it as a zip: `Cartogenesis-1.0.0-web.zip`, 4.6 MB.

### Verified behaviour

Each of these was checked by serving the real bundle over plain HTTP and loading it in a browser:

| Question | Answer | How it was checked |
|---|---|---|
| Works from the domain root? | Yes | Served at `/`, app booted, self-test passed |
| Works from a **subdirectory**? | **Yes** | Served at `/apps/cartogenesis/`, app booted, self-test passed |
| Are asset paths relative? | **Yes** | Network log showed `GET /apps/cartogenesis/<hash>.wasm` — resolved against the script, not the domain root |
| Needs `Cross-Origin-Opener-Policy` / `Cross-Origin-Embedder-Policy`? | **No** | Python's `http.server` sends neither, and everything worked including the GPU path |
| Fetches anything from a CDN or third party at runtime? | **No** | The only URLs in the bundle are a webpack polyfill artefact and a link inside an error message |
| Correct wasm MIME type served? | Yes, by that server | `Content-Type: application/wasm` — **see the risk below** |

The in-page self-test (`?selftest` on the URL) checks the GPU path against the CPU path and printed:

```
SELFTEST device=nvidia cpu=3506ms gpu=488ms
meanDelta=8.1e-8  worstDelta=4.7e-4
```

That is a useful smoke test once it's live — append `?selftest` and read the browser console.

---

## What the hosting actually needs

Very little, but the items below are the ones that break it.

### 1. `application/wasm` MIME type — the main risk

Browsers use the streaming compiler, which **refuses a `.wasm` file served with the wrong
`Content-Type`**. If the host sends `application/octet-stream` or `text/plain`, the app shows the
loading message forever and the console complains about the MIME type.

Most modern static hosts get this right. Some older Apache/nginx configs and some shared hosting do
not. **Please check what my host actually sends** and, if it's wrong, add the mapping — for nginx
that's a line in `mime.types`, for Apache an `AddType` in `.htaccess`.

### 2. Compression — worth doing, big win

The wasm compresses extremely well:

| | Raw | gzip -9 |
|---|---|---|
| Application wasm | 3,873 KB | 1,152 KB |
| Skia wasm | 8,208 KB | 3,155 KB |
| Loader JS | 582 KB | 105 KB |
| **Total** | **12,665 KB** | **4,414 KB** |

**Roughly 12.4 MB → 4.4 MB, a 65% saving**, and brotli would do better still. If the host can
pre-compress or compress on the fly, please turn it on for `.wasm` and `.js`. This is the single
biggest thing affecting how the page feels to a first-time visitor.

### 3. Caching

The two `.wasm` files and the JS have **content hashes in their names**, so they can be cached
permanently and safely — `Cache-Control: public, max-age=31536000, immutable`. `index.html` should
**not** be cached that way, or a future deploy won't be picked up; something short, or
`no-cache`, is right.

### 4. HTTPS

Needed for the optional GPU acceleration (WebGPU requires a secure context). The app works without
it, just slower. Any normal HTTPS setup is fine — no special certificate requirements.

### 5. Files that can be deleted

- `cartogenesis.js.map` (1.7 MB) — a source map, only useful for debugging the minified JS. Dropping
  it saves bandwidth and stops the original Kotlin source being trivially readable. **Your call:
  I don't mind either way.**
- `composeResources/` — empty directories.

---

## Things worth deciding, where I'd like your opinion

These are genuine questions, not rhetorical ones.

1. **Where should it live?** A subdirectory is proven to work, so `/cartogenesis/` or similar is on
   the table alongside a subdomain. Which fits the site better?

2. **How should the page frame it?** The app fills the whole viewport and is a single canvas. So it
   probably wants either its own bare page, or a wrapper page that explains what it is with the app
   embedded or linked from it. I lean towards a short explanatory page that links through to the
   full-screen app, but I'd like your view given how the rest of the site reads.

3. **The 12 MB problem.** Even at 4.4 MB compressed this is heavy for a personal site, and a visitor
   sees "Loading the generator…" while it downloads. Options I can see: a better loading state, a
   click-to-load poster image so the download only starts on request, or simply accepting it.
   What would you do?

4. **Mobile.** The interface was designed for a desktop window and I have not tested it on a phone.
   Should the page detect small screens and suggest a desktop browser? I'd rather set expectations
   than have it look broken.

5. **The desktop downloads.** I also have a Windows portable zip (95 MB) and an MSI installer
   (96 MB). Should those be offered on the site too, and if so, is my host a sensible place for
   ~200 MB of binaries or should they go somewhere else (a release page, object storage)?

6. **Deploying updates.** I rebuild the bundle locally with a Gradle task. What's the least annoying
   way to get a new build onto the site given how the rest of it is deployed?

---

## Performance context, so the tradeoffs make sense

- A browser tab gets **one thread**, so the web build starts at a 512×512 grid where the desktop
  starts at 1024×1024. Generation at 512 takes a few seconds.
- Erosion is over half the work. There is an **opt-in GPU toggle** using WebGPU — measured at
  roughly 7× faster than the CPU path in-browser (3506 ms → 488 ms), agreeing with the CPU to
  within 5e-4.
- Everything is deterministic from a seed, so a saved world is a few kilobytes, not a bitmap.
- Requires a browser with **WebAssembly GC** support. That means a recent Chrome, Firefox or Safari.
  I have not established the exact minimum versions — **worth checking against my site's actual
  analytics** rather than trusting a general claim. It is definitely not going to work on anything
  old.

---

## What the app does, for writing copy about it

If the site needs a description, the honest pitch is that the geography is **simulated rather than
faked**, and that is the whole point:

- **Terrain** comes from integrating a gradient field, so slopes are real slopes.
- **Mountains** sit on plate boundaries classified from relative plate motion.
- **Erosion** is thermal and hydraulic: rock past a critical slope collapses and piles at the foot,
  and rivers cut valleys by stream-power incision.
- **Ocean currents** are solved for, not drawn — wind stress has a curl, and the stream function
  satisfying that curl inside a closed basin *is* a gyre.
- **Rainfall** is orographic: moist air marches along prevailing winds, soaks windward slopes and
  leaves rain shadows behind them.
- **Rivers** run downhill through filled depressions; basins the fill had to raise become lakes with
  an outlet.
- **Realms** are built from drainage catchments, so borders sit on watersheds and rivers because a
  catchment edge *is* a watershed — not because anything was told to draw them there.
- **Peoples** are a separate layer, grown by climate similarity rather than politics, so cultures
  cross borders the way real ones do.

Eleven map views: Fantasy, Political, Peoples, Elevation, Biomes, Temperature, Rainfall, Plates,
Ocean currents, Winds, Normal map. Nine visual styles from vellum to pen-and-ink. Exports to PNG or
WebP at up to 4096×4096.

---

## Summary of the ask

A static bundle, 12.4 MB raw and 4.4 MB compressed, no backend, works in a subdirectory, needs
`application/wasm` served correctly and compression turned on. Help me pick where it goes, how the
page around it should read, and how to redeploy it — and tell me if any of the six questions above
have an obviously better answer than the one I'm leaning towards.
