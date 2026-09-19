# Release notes template

The shape a GitHub release's notes take, so that a reader coming to the release page for the third
time finds the same things in the same places. Copy everything below the rule, replace `<version>`
throughout, and cut the sections a release has nothing to say in — except Downloads and Installing,
which every release has.

`SiteAssemblyTest` reads the Downloads table's file names and the Installing section's apt source
line, and holds both to `site/downloads.txt` and `docs/INSTALL.md`: a table that has drifted from
the page fails the build. The three card summaries under Installing are the page's own sentences,
word for word, for the same reason — one copy, checked, rather than three that look alike.

Sizes in the table are measured off the files that were uploaded, not carried over from the last
release. The two Linux rows are uploaded by `.github/workflows/release-linux.yml`; the rest are
built and uploaded by hand.

---

<One line saying what this release is for.>

## The window

- <What changed in the application.>

## The map

- <What changed in the generated world, with the seed anything was measured on.>

## cartogenesis.com

- <What changed on the site.>

<Whether saves from the previous release open unchanged.>

## Downloads

| File | Size | What it is |
|---|---|---|
| `Cartogenesis-<version>-windows-portable.zip` | <n> MB | Unzip and run `Cartogenesis.exe`. No installation. |
| `Cartogenesis-<version>.msi` | <n> MB | Windows installer. Unsigned, so SmartScreen will warn. |
| `Cartogenesis-<version>-linux-amd64.deb` | <n> MB | Debian package for Debian, Ubuntu and relatives. |
| `Cartogenesis-<version>-linux-portable.tar.gz` | <n> MB | Untar and run `bin/Cartogenesis`. No installation. |
| `Cartogenesis-<version>-web.zip` | <n> MB | Static browser build for any web host. |

## Installing

Every download bundles its own Java runtime. Nothing needs to be installed first.

**Windows.** Unzip the portable zip and run `Cartogenesis.exe`. Nothing is installed. Or choose the
MSI installer. It is unsigned, so Windows shows a SmartScreen warning. If you trust the download,
choose "More info", then "Run anyway".

**Linux.** The apt repository is recommended. The three commands below fetch the signing key, add
the source line and run apt install. Alternatively, install the `.deb` with apt, or unpack and run
the portable tarball. Java is bundled, with nothing else to install. The open-source graphics stack
(Mesa) was tested. The proprietary NVIDIA driver was not. Saved worlds live in
`~/.cartogenesis/worlds`.

```bash
sudo curl -fsSL https://cartogenesis.com/apt/key.asc -o /etc/apt/keyrings/cartogenesis.asc
echo "deb [signed-by=/etc/apt/keyrings/cartogenesis.asc] https://cartogenesis.com/apt stable main" | sudo tee /etc/apt/sources.list.d/cartogenesis.list
sudo apt update && sudo apt install cartogenesis
```

`apt upgrade` picks up later releases from then on. For the plain `.deb`:
`sudo apt install ./Cartogenesis-<version>-linux-amd64.deb`.

**Browser.** Run Cartogenesis at cartogenesis.com. Nothing is installed and nothing is uploaded.
You can also host the web zip yourself over HTTPS.

Longer instructions, including where saves live on each platform and what to do about graphics
drivers, are in [docs/INSTALL.md](INSTALL.md).
