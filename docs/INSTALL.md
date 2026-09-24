# Installing Cartogenesis

Every way there is to run it, one section per platform. The short summaries here are the same
sentences cartogenesis.com's Download and Installation section shows and the release notes repeat;
the detail under each is what does not fit on a card.

The download bundles its own Java runtime. Nothing needs to be installed first on any platform.

The file names below write `<version>` where a release's own number goes — `3.0.2`, say. The
complete list is `site/downloads.txt`, which `SiteAssemblyTest` holds this file, the page and the
release-notes template to, so all three name the same files.

## Windows

> Unzip the portable zip and run `Cartogenesis.exe`. Nothing is installed. Or choose the MSI
> installer. It is unsigned, so Windows shows a SmartScreen warning. If you trust the download,
> choose "More info", then "Run anyway".

- **Portable** — `Cartogenesis-<version>-windows-portable.zip`. Unzip it anywhere and run
  `Cartogenesis.exe` from the folder. Nothing is written outside the folder except your saved
  worlds and settings. Deleting the folder uninstalls it.
- **Installer** — `Cartogenesis-<version>.msi`. It installs to Program Files and adds a Start menu
  entry, and it uninstalls through Settings ▸ Apps like anything else.

The MSI is not code-signed, so Windows SmartScreen shows a blue "Windows protected your PC" panel
the first time it runs. The **More info** link reveals a **Run anyway** button. Take that step only
if you trust the download: what SmartScreen is telling you is true, which is that nobody has paid
for a certificate vouching for this file. The portable zip avoids the panel because it is not an
installer.

Saved worlds live in `%USERPROFILE%\.cartogenesis\worlds`.

## Linux

> The apt repository is recommended. The three commands below fetch the signing key, add the
> source line and run apt install. Alternatively, install the `.deb` with apt, or unpack and run
> the portable tarball. Java is bundled, with nothing else to install. The open-source graphics
> stack (Mesa) was tested. The proprietary NVIDIA driver was not. Saved worlds live in
> `~/.cartogenesis/worlds`.

Debian, Ubuntu and their relatives. There is no RPM repository; on other distributions use the
portable tarball.

### The apt repository, which is the route that keeps you up to date

```bash
sudo curl -fsSL https://cartogenesis.com/apt/key.asc -o /etc/apt/keyrings/cartogenesis.asc
echo "deb [signed-by=/etc/apt/keyrings/cartogenesis.asc] https://cartogenesis.com/apt stable main" | sudo tee /etc/apt/sources.list.d/cartogenesis.list
sudo apt update && sudo apt install cartogenesis
```

`apt upgrade` picks up later releases from then on.

On a distribution older than Debian 12 or Ubuntu 22.04 the keyrings folder may not exist yet:
`sudo install -m 0755 -d /etc/apt/keyrings` before the first command.

`signed-by` is what makes the key apply to this one source and nothing else. A key added to the
system-wide trusted set would be allowed to sign packages from every repository on the machine,
which is far more than this one needs.

The repository serves `amd64` only, and one channel, `stable`, whose codename will not change.

### The package on its own

`Cartogenesis-<version>-linux-amd64.deb`, from the release page:

```bash
sudo apt install ./Cartogenesis-<version>-linux-amd64.deb
```

`apt install ./file.deb` rather than `dpkg -i` so that the path is resolved and dependencies are
handled. This route does not upgrade itself; a later release is another download.

### The portable tarball

`Cartogenesis-<version>-linux-portable.tar.gz`, for any distribution, and for running without root:

```bash
tar -xzf Cartogenesis-<version>-linux-portable.tar.gz
./Cartogenesis/bin/Cartogenesis
```

Nothing is installed and nothing is registered; deleting the folder removes it.

### Graphics

The desktop app, its tests and the Debian package were run on Ubuntu 24.04 with the open-source
graphics stack — Mesa, with nouveau, NVK and zink — where the GPU check found the card and the
accelerated erosion and export paths ran. The proprietary NVIDIA driver is untested on Linux: it is
expected to work and has not been shown to.

Acceleration is optional either way. Without a usable device the generator runs on the processor
and produces the same world, more slowly. To see which way it went before opening the window:

```bash
/opt/cartogenesis/bin/Cartogenesis --gpu-check
```

It names the device it found, or says why it found none, for the erosion sweeps and the export
raster in turn, and exits without opening a window. Finding no device is an answer, not a failure,
and it exits cleanly either way. From the tarball the command is
`./Cartogenesis/bin/Cartogenesis --gpu-check`.

### Where things live

- Saved worlds: `~/.cartogenesis/worlds`, or the folder chosen under Settings ▸ Library folder.
  Back that folder up and nothing else; choosing a folder a sync client keeps in step, as the
  README's "The library, and keeping it in the cloud" describes, backs it up as you go.
- Settings: alongside them, under `~/.cartogenesis`.
- From the package, the launcher is `/opt/cartogenesis/bin/Cartogenesis`, reached from the
  desktop environment's own menu. The package adds no symlink to `/usr/bin`, so the full path is
  what a terminal wants.

Removing the package leaves `~/.cartogenesis` where it is, on purpose: your worlds are not the
program's to delete.

## Browser

> Run Cartogenesis at cartogenesis.com. Nothing is installed and nothing is uploaded. You can also
> host the web zip yourself over HTTPS.

[cartogenesis.com](https://cartogenesis.com) runs the whole generator in the page. A recent Chrome,
Edge, Firefox or Safari will do. The first visit downloads the generator and caches it; nothing you
make leaves the machine, and worlds are saved to and opened from your own disk.

To host it yourself, take `Cartogenesis-<version>-web.zip` from the release page and unpack it onto
any static host. Serve it over HTTPS: without a secure context the browser withholds WebGPU and the
build falls back to the processor without saying so. `docs/DEPLOYMENT.md` has the rest, including
the one MIME type that matters.

Browser exports are capped below the desktop's, and on phones lower again, because a tab has far
less memory to work with than an application does.

## Setting up the apt repository's signing key

One-time setup for the maintainer, not for a reader installing the program. The repository's
indexes are signed by a GPG key whose private half lives in three repository secrets; without them
the site deploys with no `/apt` on it and says so in its log.

Do this on a machine you trust, once.

1. **Make the key.**

   ```bash
   gpg --full-generate-key
   ```

   Answer it:
   - *Please select what kind of key you want* — **1**, RSA and RSA.
   - *What keysize do you want* — **4096**.
   - *Key is valid for* — **0**, does not expire. An expiring signing key stops `apt update`
     working for everyone who added the source, on a date nobody remembers choosing.
   - *Real name* — `Cartogenesis`.
   - *Email address* — `dev@cartogenesis.com`.
   - *Comment* — leave it empty.
   - Then a passphrase. Choose a strong one and keep it; it becomes the second secret below.

2. **Find the key id.**

   ```bash
   gpg --list-secret-keys --keyid-format=long
   ```

   The line beginning `sec` reads something like `sec rsa4096/AB12CD34EF567890 2026-09-19 [SC]`.
   The part after the slash — `AB12CD34EF567890`, sixteen characters — is the key id. Write it
   down.

3. **Export the private half, armoured.**

   ```bash
   gpg --armor --export-secret-keys AB12CD34EF567890 > cartogenesis-apt-private.asc
   ```

   It asks for the passphrase. The file begins `-----BEGIN PGP PRIVATE KEY BLOCK-----`. Treat it
   the way you would treat a password: anyone holding it and the passphrase can sign packages that
   every machine with this repository added will install without question.

4. **Add the three repository secrets.** On GitHub, go to the repository ▸ **Settings** ▸
   **Secrets and variables** ▸ **Actions** ▸ **New repository secret**, three times:

   | Secret name | What goes in it |
   | --- | --- |
   | `APT_SIGNING_KEY` | The whole contents of `cartogenesis-apt-private.asc`, `-----BEGIN` line and `-----END` line included. Paste it; do not upload the file. |
   | `APT_SIGNING_PASSPHRASE` | The passphrase from step 1, and nothing else — no quotes, no trailing space. |
   | `APT_SIGNING_KEY_ID` | The sixteen characters from step 2, such as `AB12CD34EF567890`. |

5. **Delete the exported file** from the machine once the secret is saved:
   `shred -u cartogenesis-apt-private.asc`, or delete it and empty the trash. The key itself stays
   in your own GPG keyring, which is the copy to keep.

6. **Check it.** Run the site deploy by hand from the Actions tab. Its log prints what apt will
   serve; the site then has `key.asc`, `dists/` and `pool/` under `/apt`. If a secret is missing
   the log says which one by name and the site deploys without the repository.

**To rotate the key**, make a new one by the same steps, replace all three secrets, and deploy the
site. Everyone who added the source has the old key pinned in
`/etc/apt/keyrings/cartogenesis.asc`, so their next `apt update` fails to verify until they fetch
the new one — which is the first command of the three, run again. That is a thing worth announcing
in a release note rather than doing quietly, so rotate only when there is a reason to.
