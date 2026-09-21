// Mocha's timeout for the browser tests, set where Karma reads it.
//
// The build file's `useMocha { timeout }` never reaches a browser run: Kotlin writes that figure
// into the Node runner only, and the browser tests go through Karma, whose generated
// configuration carried no mocha client options and so ran on mocha's own two-second default.
// That is why `GenerationProgressTest`, which runs a whole 128-cell generation in the browser,
// failed on the hosted runner (measured at 69 s) and now and then on a loaded machine here, while
// the build file said sixty seconds. Five minutes: above the test's own derived limit of 210 s,
// so a real hang still fails here and the arithmetic finishing never does.
config.set({
  client: {
    mocha: {
      timeout: 300000
    }
  }
});
