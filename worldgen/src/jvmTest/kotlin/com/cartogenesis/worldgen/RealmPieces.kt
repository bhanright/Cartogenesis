package com.cartogenesis.worldgen

/**
 * Every connected run of one realm's land, and which of them are stranded: a piece other than its
 * realm's largest that a person could walk out of into another realm. An island a realm holds
 * across the water is not stranded; a pocket of it inside a neighbour, or a sliver of it along a
 * neighbour's border, is.
 *
 * Pieces are walked over the eight neighbours, as the routing and the realm stage's own enclave
 * pass walk them, and the world wraps east to west. [realmOf] is one id per cell, row-major, with
 * [unclaimed] over water and wilderness.
 */
internal class RealmPieces(
    cellsAcross: Int,
    cellsDown: Int,
    isLand: BooleanArray,
    realmOf: IntArray,
    unclaimed: Int
) {
    class Piece(
        val realm: Int,
        val cells: Int,
        val touchesAnotherRealmByLand: Boolean,
        val touchesTheSea: Boolean,
        /** Every other realm this piece meets by land, ascending. */
        val landNeighbours: List<Int>
    )

    val pieces: List<Piece>

    /** Pieces other than their realm's largest (one per realm, the first found where two tie). */
    val outlying: List<Piece>

    /** [outlying] pieces a person could walk out of into another realm. */
    val stranded: List<Piece>

    /** [stranded] pieces with no coast: wholly inside other realms' land. */
    val inland: List<Piece>

    /**
     * Realms whose largest piece meets exactly one other realm by land: a country held inside
     * another, all its land frontier with the one neighbour. The coast does not rescue it; a realm
     * on a stretch of shore with one neighbour wrapped round the rest of it reads the same way.
     */
    val enclosedRealms: List<Piece>

    init {
        val found = ArrayList<Piece>()
        val seen = BooleanArray(isLand.size)
        val stack = ArrayDeque<Int>()
        for (start in isLand.indices) {
            if (seen[start] || !isLand[start] || realmOf[start] == unclaimed) continue
            val realm = realmOf[start]
            var cells = 0
            var touchesRealm = false
            val met = java.util.TreeSet<Int>()
            var touchesSea = false
            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                cells++
                val x = cell % cellsAcross
                val y = cell / cellsAcross
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val ny = y + dy
                    if (ny !in 0 until cellsDown) continue
                    val neighbour = ny * cellsAcross + (x + dx + cellsAcross) % cellsAcross
                    when {
                        !isLand[neighbour] -> touchesSea = true
                        realmOf[neighbour] == realm -> if (!seen[neighbour]) { seen[neighbour] = true; stack.addLast(neighbour) }
                        realmOf[neighbour] != unclaimed -> { touchesRealm = true; met.add(realmOf[neighbour]) }
                    }
                }
            }
            found += Piece(realm, cells, touchesRealm, touchesSea, met.toList())
        }
        pieces = found
        val mainland = HashMap<Int, Piece>()
        for (piece in found) {
            val best = mainland[piece.realm]
            if (best == null || piece.cells > best.cells) mainland[piece.realm] = piece
        }
        outlying = found.filter { mainland[it.realm] !== it }
        stranded = outlying.filter { it.touchesAnotherRealmByLand }
        inland = stranded.filter { !it.touchesTheSea }
        enclosedRealms = mainland.values.filter { it.landNeighbours.size == 1 }.sortedBy { it.realm }
    }

    fun describe(): String =
        "%d pieces, %d outlying, %d stranded (%d inland), stranded cells %d, largest stranded %d; %d realms inside one neighbour (%d without coast): %s".format(
            pieces.size, outlying.size, stranded.size, inland.size, stranded.sumOf { it.cells },
            stranded.maxOfOrNull { it.cells } ?: 0, enclosedRealms.size, enclosedRealms.count { !it.touchesTheSea },
            enclosedRealms.joinToString(" ") { "${it.realm}:${it.cells}${if (it.touchesTheSea) "c" else ""}" }
        )
}
