# Deploying the web build

How the site is assembled and published, and the mechanics that are easy to break silently. The
README carries the commands and the secrets' names; this file carries the reasons. `site/README.md`
covers the site's own contents and how to deploy by hand.

## What the deploy does

`./gradlew :web:assembleSite` builds the application and assembles it with `site/` into
`web/build/site`: it drops the source map and the build's own emitted `index.html`, and stamps the
loader's URL with the commit. `./gradlew :desktop:siteTest` does the same and then checks the tree
it produced.

`.github/workflows/site.yml` runs both on any pushed `v*` tag, or by hand from the Actions tab, and
uploads `web/build/site` to the Cloudflare Pages project `cartogenesis` using two repository
secrets, `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.

## The two names the shell depends on

`site/app/index.html` reaches into the application for two names. Breaking either one fails
silently: the application keeps working and the page around it never finds out.

- **`VIEWPORT_ID`** in `web/.../Main.kt` must stay `composeTarget`. The shell creates the div;
  Compose mounts into it.
- **`hideLoadingMessage()`** in `web/.../Browser.kt` must keep removing `#loading`, and must keep
  being called on startup. The shell keeps an empty div with that id so this can delete it, and
  treats the deletion as its "the app is ready" signal.

The second exists because **Compose does not put its canvas in the page.** It attaches a shadow root
to the viewport div and puts the canvas inside it. From outside, `document.querySelector("canvas")`
is null, the div reports no children, and a MutationObserver on it never fires — all while a live
canvas is generating a world. There is no other exact readiness signal from outside that shadow
root.

`WebDeploymentContractTest` pins both, and was shown to fail on each in turn. It reads the web
module's source text, because the contract is an id inside a `@JsFun` body that no type system sees.
`desktop/build.gradle.kts` declares those sources as test inputs; without that, Gradle keeps the
task up to date and the build cache restores a stale pass.

## Stamping the loader

The two `.wasm` files carry content hashes; `cartogenesis.js` does not. A new build therefore lands
under new wasm names while the loader keeps its old URL, so a returning visitor with a cached loader
asks for a wasm hash the deploy has just deleted — a 404 and a dead app, not a stale one.

The site loads `cartogenesis.js?v=<stamp>` and stamps it on every deploy. `:web:assembleSite` does
the stamping and fails the build rather than shipping an unstamped shell.

## Cloudflare Pages

The workflow asks the Pages API which branch the project treats as production, rather than assuming
`main`. A custom domain serves the project's production deployment, and a deployment counts as
production only if its branch name matches the project's production branch. A Direct Upload project
keeps whatever branch name it was created with, so uploading under a guessed name would quietly
produce a preview deployment the domain never shows.

The custom domain is attached by hand, once, in the Cloudflare dashboard under Workers & Pages →
`cartogenesis` → Custom domains. Nothing in this repository configures it, and nothing here needs to
change if it moves.

The host must serve `.wasm` as `application/wasm`, or the browser's streaming compiler refuses it.
Compression is worth turning on.

## Hosting it anywhere else

Upload the contents of `web/build/dist/wasmJs/productionExecutable` to any static host; there is no
server side to it.

- Paths are relative, so hosting from a subfolder works unchanged.
- The `.wasm` MIME type does not matter here: the build instantiates from a buffer rather than
  streaming.
- Serve HTTPS. Without it WebGPU is unavailable and the build falls back to the processor silently.
- `cartogenesis.js.map`, a 1.7 MB source map fetched only when devtools are open, can be deleted
  from the upload.

Sizes are in the README, measured rather than typed: the assembly measures the loader and the two
wasm modules it is about to publish and writes the figure into both pages, and `SiteAssemblyTest`
recomputes it and compares.

## The PNG encoder, and why it is ours

Both data-export PNGs come out of a small encoder in `:cartography` rather than either host's
imaging library, because neither will write what these need. Skia is eight bits a channel
everywhere, a browser canvas is eight-bit RGBA by construction, and neither writes an indexed image
at all.

The image data is a zlib stream, which common Kotlin cannot build, so the deflate comes out of the
gzip each host already has behind `Compressor` — the same stream in a different envelope — with a
stored-deflate fallback for a host that has neither. The fallback is about a third larger and every
reader still opens it. `DataExportTest` checks that both paths produce identical samples.

## One zip, not two downloads

The desktop writes a data export's two files side by side from one save dialog. The browser sends a
single zip holding both, and that is a decision rather than a shortcut: two downloads from one click
raises Chrome's unexplained "download multiple files" prompt, and has historically lost the second
file in Safari, while a heightmap that arrives without its metre scale is a grey rectangle.

The archive stores rather than deflates: the PNG inside is already compressed, and the page has one
thread.
