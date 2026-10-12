package com.cartogenesis.worldgen.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The eigenvalues and orthonormal eigenvectors of a small real symmetric matrix, by Jacobi's cyclic
 * rotations: each sweep zeroes every off-diagonal pair once, and the sweeps stop when what is left
 * off the diagonal is rounding. Meant for matrices of a few dozen rows (an atmosphere's levels), where
 * its simplicity and its accuracy on small eigenvalues matter more than its cubic cost per sweep.
 *
 * [values] come out in ascending order and [vectors] holds eigenvector `n` as its column `n`,
 * row-major `size * size`, each of unit length.
 */
class SymmetricEigen(matrix: DoubleArray, val size: Int) {

    val values: DoubleArray
    val vectors: DoubleArray

    init {
        require(matrix.size == size * size) { "a symmetric matrix of ${matrix.size} entries is not $size by $size" }
        for (row in 0 until size) for (column in 0 until row) {
            val upper = matrix[row * size + column]
            val lower = matrix[column * size + row]
            require(abs(upper - lower) <= SYMMETRY_TOLERANCE * (abs(upper) + abs(lower) + Double.MIN_VALUE)) {
                "the matrix is not symmetric at ($row, $column): $upper against $lower"
            }
        }
        val work = matrix.copyOf()
        val rotations = DoubleArray(size * size).also { for (index in 0 until size) it[index * size + index] = 1.0 }
        var sweep = 0
        while (sweep < MAX_SWEEPS && offDiagonalNorm(work) > ROUNDING * diagonalNorm(work)) {
            for (first in 0 until size - 1) for (second in first + 1 until size) rotate(work, rotations, first, second)
            sweep++
        }
        check(offDiagonalNorm(work) <= ROUNDING * diagonalNorm(work) || diagonalNorm(work) == 0.0) {
            "Jacobi's rotations did not settle in $MAX_SWEEPS sweeps"
        }
        val order = (0 until size).sortedBy { work[it * size + it] }
        values = DoubleArray(size) { work[order[it] * size + order[it]] }
        vectors = DoubleArray(size * size)
        for ((target, source) in order.withIndex()) {
            for (row in 0 until size) vectors[row * size + target] = rotations[row * size + source]
        }
    }

    /** Eigenvector [index] as its own array. */
    fun vector(index: Int): DoubleArray = DoubleArray(size) { vectors[it * size + index] }

    /** One rotation in the plane of [first] and [second] that zeroes their off-diagonal entry. */
    private fun rotate(work: DoubleArray, rotations: DoubleArray, first: Int, second: Int) {
        val offDiagonal = work[first * size + second]
        if (offDiagonal == 0.0) return
        val firstDiagonal = work[first * size + first]
        val secondDiagonal = work[second * size + second]
        // The rotation angle's cotangent, and its tangent by the smaller root (Rutishauser's form).
        val theta = (secondDiagonal - firstDiagonal) / (2.0 * offDiagonal)
        val tangent = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
        val cosine = 1.0 / sqrt(tangent * tangent + 1.0)
        val sine = tangent * cosine
        for (index in 0 until size) {
            val atFirst = work[index * size + first]
            val atSecond = work[index * size + second]
            work[index * size + first] = cosine * atFirst - sine * atSecond
            work[index * size + second] = sine * atFirst + cosine * atSecond
        }
        for (index in 0 until size) {
            val atFirst = work[first * size + index]
            val atSecond = work[second * size + index]
            work[first * size + index] = cosine * atFirst - sine * atSecond
            work[second * size + index] = sine * atFirst + cosine * atSecond
        }
        for (index in 0 until size) {
            val atFirst = rotations[index * size + first]
            val atSecond = rotations[index * size + second]
            rotations[index * size + first] = cosine * atFirst - sine * atSecond
            rotations[index * size + second] = sine * atFirst + cosine * atSecond
        }
    }

    private fun offDiagonalNorm(work: DoubleArray): Double {
        var sum = 0.0
        for (row in 0 until size) for (column in 0 until size) if (row != column) sum += work[row * size + column] * work[row * size + column]
        return sqrt(sum)
    }

    private fun diagonalNorm(work: DoubleArray): Double {
        var sum = 0.0
        for (index in 0 until size) sum += work[index * size + index] * work[index * size + index]
        return sqrt(sum)
    }

    companion object {
        /** Off-diagonal mass, as a share of the diagonal's, at which the matrix is diagonal to rounding. */
        private const val ROUNDING = 1e-15

        /** Jacobi converges quadratically once close; fifty sweeps is far beyond what a few dozen rows take. */
        private const val MAX_SWEEPS = 50

        /** How far apart two mirrored entries may be, as a share of their size, and still be the same entry. */
        private const val SYMMETRY_TOLERANCE = 1e-12
    }
}
