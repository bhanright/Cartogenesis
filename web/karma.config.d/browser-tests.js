// Karma's settings for the browser front end's tests, set where Karma reads them.
//
// Mocha's timeout: as in `ui/karma.config.d/mocha-timeout.js`, a `useMocha { timeout }` in the build
// file never reaches a Wasm run, and mocha's own two-second default is shorter than generating the
// small worlds these tests save. Five minutes, the interface tests' figure, so a real hang still
// fails and a loaded machine does not.
//
// `REGENERATE_INTEROP_FIXTURES` in the environment passes one argument through to the page, which
// `FolderInteropTest` reads to print the save a folder library wrote, for the desktop's test to
// read back. See that test for the whole procedure; nothing else here depends on it.
const args = process.env.REGENERATE_INTEROP_FIXTURES ? ['regenerate-interop-fixtures'] : [];
config.set({
  client: {
    args: args,
    mocha: {
      timeout: 300000
    }
  }
});
