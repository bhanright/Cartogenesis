package com.cartogenesis.worldgen

import kotlin.test.Test

/**
 * The 2048-scale case of [RealmIdRangeTest], split out in T1 into the on-demand / nightly audit
 * tier: the world the crash was originally reported on, at the size it was reported at. The cheap
 * 512/1024 cases stay in `jvmTest` via [RealmIdRangeTest].
 *
 * [authorsConfig] and [assertRealmIdsInRange] are shared with [RealmIdRangeTest] and live there as
 * top-level `internal` functions rather than being duplicated.
 */
class RealmIdRangeAuditTest {

    /** The world the crash was reported on, at the size it was reported at. */
    @Test
    fun `the author's world at 2048 numbers every cell inside its realm list`() {
        assertRealmIdsInRange(authorsConfig(718106L).atResolution(2048, 2048))
    }
}
