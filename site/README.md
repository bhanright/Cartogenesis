# cartogenesis.com

Everything the website is, apart from the application itself:

```
site/
  index.html    the description page, served at /
  app/
    index.html  the loading shell, served at /app/ — see "What the shell depends on"
  _headers      Cloudflare Pages response headers
  _redirects    Cloudflare Pages redirects
```

That is the whole of it: two pages and two host files. Four things the site serves are *not* in
here, because keeping a second copy of any of them is how a copy goes stale:

- **The application**, built from `:web` and dropped in under `app/` at assembly time.
- **The typefaces**, under `fonts/`. The five faces the page sets its type in are copied out of
  `ui/src/commonMain/composeResources/font`, which is where the application keeps them, so the
  page and the app cannot drift on to different cuts of Spectral. (The sixth bundled face, Plex
  Mono bold, is not copied: the page never asks for it.)
- **Every picture**, under `img/`. There are no image files in this folder at all. They are
  rendered from the engine at assembly time by `:desktop:renderSiteImagery` — seed 718106 at 2048
  with the author's settings, cut to fixed windows — so a release that changes what a coastline looks
  like changes the coastline the page shows. See `SiteImagery.kt` for the seed, the windows and
  how to pick a new one.
- **Any version number or file size.** The download cards link each file to `/releases/latest`,
  and the page's script asks GitHub's API for the latest release once a visit and turns each link
  into the file itself with its size; if the API refuses or fails, the links stay as they are.

## Assembling it

```bash
./gradlew :web:assembleSite
```

That runs `:web:wasmJsBrowserDistribution` and `:desktop:renderSiteImagery`, then syncs
`web/build/site` to this folder plus the faces, the figures and the build output under `app/`,
which is the tree a host serves:

- **`Sync`, not a copy.** The two `.wasm` filenames carry content hashes, so a new build lands
  *beside* the old one rather than replacing it. A tree that is only ever added to grows about
  12 MB of orphans per build and publishes all of them.
- **`cartogenesis.js.map` is dropped.** 1.7 MB, debug-only, and it publishes the original Kotlin.
- **The build's own `index.html` is dropped.** `site/app/index.html` replaces it.
- **`composeResources/` is kept when it has files.** Since 2.0 it carries the six bundled interface
  faces. Empty directories are not copied, so if it ever goes back to being hollow it stops being
  published without anyone having to remember.
- **The loader is stamped.** `site/app/index.html` asks for `cartogenesis.js?v=__STAMP__`; assembly
  replaces the placeholder with the short commit. The replacement is scoped to that one `src`
  attribute, so writing the placeholder anywhere else in the file leaves it unreplaced — and the
  build fails rather than shipping it.

Why the stamp exists at all: the `.wasm` files are content-hashed and can be cached for a year, but
the loader is always `cartogenesis.js`. Without a fresh query string per deploy, a returning
visitor's cached loader asks for a wasm hash the deploy has just replaced — a 404 and a dead app
rather than a stale one. That is not a theory; it happened on the v1.0.1 deploy of the earlier host.
`_headers` therefore caches the loader immutably *and* `/app/index.html` not at all: a visitor
always reads a current shell, so always asks for a current loader, and an old cached shell can only
ask for the old loader, which is still there under its old query.

## The guard

```bash
./gradlew :desktop:siteTest
```

Assembles the site and then runs `SiteAssemblyTest` over the tree that would be uploaded: two
`.wasm` files and no third left behind, exactly one of them the application, the six fonts the app
bundles and the five the page sets its type in, no source map, a loader stamp that is a commit
rather than the placeholder, `_headers` and `_redirects` present, every figure the page shows
rendered at the size the page reserves for it, no request to a font host, and no link to any site
but this project's (ground rule 10). For the living figures it holds the six steps to one window
and one scale, the slider's twelve styles to one window with only two asked for as the page loads,
the band the opening drifts, at both its sizes, to joining itself end to end and beginning at the
link preview's window, the page's release lookup, run in Node against the list of files a release
carries and against a malformed answer, a missing file, a refusal and a failure, and the bytes the
page fetches as it loads to a stated ceiling. It lives in `:desktop` because the web module compiles
to wasm and cannot read files. It is deliberately excluded from `:desktop:test`, which has no reason
to build 12 MB of WebAssembly and would otherwise be judging whatever an earlier run left behind.

## What the shell depends on

`site/app/index.html` reaches into the application for two names, and breaking either one fails
*silently* — the app still works and the page around it never finds out:

- **`composeTarget`** — the div Compose mounts into. `VIEWPORT_ID` in
  `web/src/wasmJsMain/kotlin/com/cartogenesis/web/Main.kt`.
- **`#loading`** — an invisible, zero-size div that exists only so `hideLoadingMessage()` in
  `Browser.kt` can remove it. Its removal is the shell's "the app is ready" signal.

The second one is not laziness. Compose attaches a **shadow root** to the viewport div and puts the
canvas inside it, so from the page `document.querySelector('canvas')` is null, the div reports no
children, and a MutationObserver on it never fires — all while a canvas is alive and generating a
world. There is no other exact readiness signal from outside that shadow root.

`WebDeploymentContractTest` pins both names in the Kotlin source and `SiteAssemblyTest` pins them in
the deployed page.

## Deploying

`.github/workflows/site.yml` does it: on any pushed `v*` tag, or by hand from the Actions tab
(`workflow_dispatch`, with an optional `ref` so any branch or tag can be published). It assembles the
site, runs the guard, and uploads `web/build/site` to the Cloudflare Pages project `cartogenesis`
with `cloudflare/wrangler-action@v3`. Two repository secrets:

| Secret | What it is |
|---|---|
| `CLOUDFLARE_API_TOKEN` | An API token with the **Cloudflare Pages: Edit** permission |
| `CLOUDFLARE_ACCOUNT_ID` | The account id from the Cloudflare dashboard URL |

The workflow reads the project's **production branch** from the Pages API before uploading, rather
than assuming `main`. This matters: a custom domain serves the project's production deployment, and
a deployment counts as production only if its branch name matches the project's production branch.
A Direct Upload project keeps whatever branch name it was created with, so uploading under a guessed
name would quietly produce a preview deployment that cartogenesis.com never shows. If the project
does not exist at all the workflow creates it with `--production-branch=main` and says so.

The concurrency group is `site` with cancelling off, so two tags cut in quick succession queue
instead of racing to upload.

**The custom domain is attached by hand, once**, in the Cloudflare dashboard: Workers & Pages →
`cartogenesis` → Custom domains → Set up a domain → `cartogenesis.com` (and `www.cartogenesis.com`
if wanted). Cloudflare adds the DNS records itself when the domain is on the same account. Nothing
in this repository configures it, and nothing here needs to change if it moves.

### By hand, if Actions is down

Assemble locally, then upload with wrangler. The two secrets are passed as environment variables;
wrangler reads them from there and never wants them on the command line:

```bash
./gradlew :desktop:siteTest                       # assembles, and checks what it assembled

export CLOUDFLARE_API_TOKEN=...                   # the same token as the repository secret
export CLOUDFLARE_ACCOUNT_ID=...
npx wrangler pages project list                   # confirm the production branch of `cartogenesis`
npx wrangler pages deploy web/build/site \
  --project-name=cartogenesis \
  --branch=<that production branch> \
  --commit-dirty=true
```

Deploying to any other branch name produces a preview URL rather than the live site. On Windows the
exports are `$env:CLOUDFLARE_API_TOKEN = '...'`.

## Worth checking on the first deploy

`_headers` uses `/app/*.wasm` and `/app/*.js`. Cloudflare documents a single greedy `*` anywhere in
a pattern but has no documented example of a splat followed by a file extension, so confirm the
headers actually arrive:

```bash
curl -sI https://cartogenesis.com/app/<hash>.wasm | grep -i -E 'cache-control|content-type|content-encoding'
curl -sI https://cartogenesis.com/app/index.html  | grep -i cache-control
```

The first should say `public, max-age=31536000, immutable` and `application/wasm`; the second
`no-cache`. If the wildcard turns out not to match, widen it to `/app/*` and give `/app/index.html`
its own rule — but note that Pages *joins* duplicate headers with a comma rather than letting the
later rule win, so the two rules must not both set `Cache-Control`.
