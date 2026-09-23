package com.cartogenesis.desktop

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.opentest4j.TestAbortedException

/*
 * The two reasons a test here stands aside, each reported as a skip rather than as a pass.
 *
 * A test that returned early used to pass, so a run on a machine with no graphics device, or one
 * that did not ask for the benchmarks, counted every such case as checked. JUnit reports an aborted
 * test as skipped, so the count now says which of them did not run.
 */

/**
 * Ends the calling test as skipped, because this machine has no graphics device.
 *
 * A headless runner is not a broken build, so this is not a failure; but a kernel nothing compared
 * with the processor has not been checked either. [unavailableBecause] is the probe's own reason,
 * and is carried into the skip's message.
 */
internal fun skipWithoutDevice(unavailableBecause: String?): Nothing =
    throw TestAbortedException("no graphics device here: $unavailableBecause")

/**
 * Skips the calling test unless the run asked for measurements with `-Pbenchmark=true`, which
 * `desktop/build.gradle.kts` hands the test JVM as the `cartogenesis.benchmark` property.
 */
internal fun skipUnlessBenchmarking() {
    assumeTrue(
        System.getProperty("cartogenesis.benchmark") == "true",
        "run with -Pbenchmark=true to measure"
    )
}
