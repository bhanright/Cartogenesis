package com.cartogenesis.worldgen

/**
 * The verdict of a clause that holds one world, or one drawing, to the same answer on two or more
 * grids, printed rather than asserted.
 *
 * The application makes every world on one grid (docs/DESIGN_LEDGER.md, G1), so a figure that
 * moves between grids no longer reaches a reader, and a clause that failed on it would hold the
 * generator to a promise the application stopped making. Each such clause keeps its measurement
 * and its bar and says, under `CROSS-GRID`, whether the bar holds and by what figures, and never
 * fails: the figures are there for the day the grid moves with the planet's size, which is a
 * planned input. What is wrong on any one grid is still the single-grid guards' to fail on.
 *
 * Shared by `:worldgen`, `:cartography` and `:desktop`'s tests; a common test, which cannot see
 * this directory, prints the same line itself.
 */
object CrossGridReport {

    /**
     * Prints [clause]'s verdict: whether its bar [holds] across the grids it compared, and the
     * [figures] it measured, as one line a run's output can be searched for.
     */
    fun report(clause: String, holds: Boolean, figures: String) {
        println("CROSS-GRID ${if (holds) "holds" else "departs"} [$clause] $figures")
    }
}
