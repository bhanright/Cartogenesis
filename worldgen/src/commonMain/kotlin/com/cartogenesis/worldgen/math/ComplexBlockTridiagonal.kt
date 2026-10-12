package com.cartogenesis.worldgen.math

import kotlin.math.abs

/**
 * A block-tridiagonal system of complex equations, `A_j x_{j-1} + B_j x_j + C_j x_{j+1} = r_j` for
 * blocks `j = 0 .. blockCount - 1`, each block [blockSize] unknowns, solved by block Gaussian
 * elimination without fill outside the band (the block Thomas algorithm).
 *
 * This is the shape of one zonal wave of a model on a latitude-longitude grid: every unknown of a
 * row talks to its own row and the rows either side of it, so the elimination runs down the rows
 * once and back up once. Each Schur complement `B_j - A_j X_{j-1}` is factored by Gaussian
 * elimination with partial pivoting inside the block, which is what keeps a block whose own diagonal
 * is small (a geopotential with no equation of its own, a vertical motion set only by continuity)
 * from dividing by it.
 *
 * Each block is a row-major `blockSize * blockSize` matrix, real and imaginary parts apart, at
 * offset `j * blockSize * blockSize` of its array. [factorize] overwrites [diagonalReal] and
 * [diagonalImaginary] with the factored complements and [upperReal] and [upperImaginary] with
 * `X_j = (B_j - A_j X_{j-1})^-1 C_j`; [lowerReal] and [lowerImaginary] are kept, since every later
 * [solve] reads them. A factored system solves any number of right-hand sides at `O(blockSize^2)` a
 * block each.
 */
class ComplexBlockTridiagonal(val blockCount: Int, val blockSize: Int) {

    init {
        require(blockCount >= 1 && blockSize >= 1) { "a block system of $blockCount blocks of $blockSize" }
    }

    private val blockArea = blockSize * blockSize

    val lowerReal = DoubleArray(blockCount * blockArea)
    val lowerImaginary = DoubleArray(blockCount * blockArea)
    val diagonalReal = DoubleArray(blockCount * blockArea)
    val diagonalImaginary = DoubleArray(blockCount * blockArea)
    val upperReal = DoubleArray(blockCount * blockArea)
    val upperImaginary = DoubleArray(blockCount * blockArea)

    /** The row each elimination step pivoted on, per block. */
    private val pivots = IntArray(blockCount * blockSize)

    var factorized = false
        private set

    /** Index of entry ([row], [column]) of block [block] in the block arrays. */
    fun at(block: Int, row: Int, column: Int): Int = block * blockArea + row * blockSize + column

    /** Every coefficient to zero, ready for the next wave's assembly. */
    fun clear() {
        lowerReal.fill(0.0); lowerImaginary.fill(0.0)
        diagonalReal.fill(0.0); diagonalImaginary.fill(0.0)
        upperReal.fill(0.0); upperImaginary.fill(0.0)
        factorized = false
    }

    /**
     * Eliminates down the blocks. Throws when a complement is singular to working precision, which
     * for a damped wave model means an equation was left out or written twice, not bad luck.
     */
    fun factorize() {
        check(!factorized) { "the system is already factored" }
        val size = blockSize
        val productReal = DoubleArray(blockArea)
        val productImaginary = DoubleArray(blockArea)
        for (block in 0 until blockCount) {
            val base = block * blockArea
            if (block > 0) {
                // B_j - A_j X_{j-1}.
                multiply(lowerReal, lowerImaginary, base, upperReal, upperImaginary, base - blockArea, productReal, productImaginary)
                for (entry in 0 until blockArea) {
                    diagonalReal[base + entry] -= productReal[entry]
                    diagonalImaginary[base + entry] -= productImaginary[entry]
                }
            }
            factorBlock(block)
            if (block < blockCount - 1) {
                // X_j = S_j^-1 C_j, one column at a time.
                val columnReal = DoubleArray(size)
                val columnImaginary = DoubleArray(size)
                for (column in 0 until size) {
                    for (row in 0 until size) {
                        columnReal[row] = upperReal[base + row * size + column]
                        columnImaginary[row] = upperImaginary[base + row * size + column]
                    }
                    solveBlock(block, columnReal, columnImaginary, 0)
                    for (row in 0 until size) {
                        upperReal[base + row * size + column] = columnReal[row]
                        upperImaginary[base + row * size + column] = columnImaginary[row]
                    }
                }
            }
        }
        factorized = true
    }

    /**
     * Solves the factored system for the right-hand side [real], [imaginary] (`blockCount *
     * blockSize` each, block by block), overwriting it with the solution.
     */
    fun solve(real: DoubleArray, imaginary: DoubleArray) {
        check(factorized) { "factorize before solving" }
        require(real.size == blockCount * blockSize && imaginary.size == real.size) { "a right-hand side of ${real.size} values" }
        val size = blockSize
        for (block in 0 until blockCount) {
            val offset = block * size
            if (block > 0) {
                val base = block * blockArea
                for (row in 0 until size) {
                    var sumReal = 0.0
                    var sumImaginary = 0.0
                    for (column in 0 until size) {
                        val coefficientReal = lowerReal[base + row * size + column]
                        val coefficientImaginary = lowerImaginary[base + row * size + column]
                        val valueReal = real[offset - size + column]
                        val valueImaginary = imaginary[offset - size + column]
                        sumReal += coefficientReal * valueReal - coefficientImaginary * valueImaginary
                        sumImaginary += coefficientReal * valueImaginary + coefficientImaginary * valueReal
                    }
                    real[offset + row] -= sumReal
                    imaginary[offset + row] -= sumImaginary
                }
            }
            solveBlock(block, real, imaginary, offset)
        }
        for (block in blockCount - 2 downTo 0) {
            val offset = block * size
            val base = block * blockArea
            for (row in 0 until size) {
                var sumReal = 0.0
                var sumImaginary = 0.0
                for (column in 0 until size) {
                    val coefficientReal = upperReal[base + row * size + column]
                    val coefficientImaginary = upperImaginary[base + row * size + column]
                    val valueReal = real[offset + size + column]
                    val valueImaginary = imaginary[offset + size + column]
                    sumReal += coefficientReal * valueReal - coefficientImaginary * valueImaginary
                    sumImaginary += coefficientReal * valueImaginary + coefficientImaginary * valueReal
                }
                real[offset + row] -= sumReal
                imaginary[offset + row] -= sumImaginary
            }
        }
    }

    /** `out = first * second` for the blocks at [firstBase] and [secondBase]. */
    private fun multiply(
        firstReal: DoubleArray, firstImaginary: DoubleArray, firstBase: Int,
        secondReal: DoubleArray, secondImaginary: DoubleArray, secondBase: Int,
        outReal: DoubleArray, outImaginary: DoubleArray
    ) {
        val size = blockSize
        outReal.fill(0.0)
        outImaginary.fill(0.0)
        for (row in 0 until size) {
            for (inner in 0 until size) {
                val leftReal = firstReal[firstBase + row * size + inner]
                val leftImaginary = firstImaginary[firstBase + row * size + inner]
                if (leftReal == 0.0 && leftImaginary == 0.0) continue
                for (column in 0 until size) {
                    val rightReal = secondReal[secondBase + inner * size + column]
                    val rightImaginary = secondImaginary[secondBase + inner * size + column]
                    outReal[row * size + column] += leftReal * rightReal - leftImaginary * rightImaginary
                    outImaginary[row * size + column] += leftReal * rightImaginary + leftImaginary * rightReal
                }
            }
        }
    }

    /** LU with partial pivoting of the complement in block [block], in place: unit lower and upper factors. */
    private fun factorBlock(block: Int) {
        val size = blockSize
        val base = block * blockArea
        val real = diagonalReal
        val imaginary = diagonalImaginary
        var largestEntry = 0.0
        for (entry in 0 until blockArea) largestEntry = maxOf(largestEntry, abs(real[base + entry]) + abs(imaginary[base + entry]))
        for (step in 0 until size) {
            var pivotRow = step
            var pivotSize = -1.0
            for (row in step until size) {
                val magnitude = abs(real[base + row * size + step]) + abs(imaginary[base + row * size + step])
                if (magnitude > pivotSize) {
                    pivotSize = magnitude
                    pivotRow = row
                }
            }
            check(pivotSize > SINGULAR_SHARE * largestEntry) {
                "block $block of $blockCount is singular at step $step (pivot $pivotSize against entries of $largestEntry)"
            }
            pivots[block * size + step] = pivotRow
            if (pivotRow != step) {
                for (column in 0 until size) {
                    val first = base + step * size + column
                    val second = base + pivotRow * size + column
                    val swapReal = real[first]; real[first] = real[second]; real[second] = swapReal
                    val swapImaginary = imaginary[first]; imaginary[first] = imaginary[second]; imaginary[second] = swapImaginary
                }
            }
            val pivotReal = real[base + step * size + step]
            val pivotImaginary = imaginary[base + step * size + step]
            val pivotNorm = pivotReal * pivotReal + pivotImaginary * pivotImaginary
            for (row in step + 1 until size) {
                val at = base + row * size + step
                // The multiplier, entry over pivot.
                val multiplierReal = (real[at] * pivotReal + imaginary[at] * pivotImaginary) / pivotNorm
                val multiplierImaginary = (imaginary[at] * pivotReal - real[at] * pivotImaginary) / pivotNorm
                real[at] = multiplierReal
                imaginary[at] = multiplierImaginary
                if (multiplierReal == 0.0 && multiplierImaginary == 0.0) continue
                for (column in step + 1 until size) {
                    val upperAt = base + step * size + column
                    val target = base + row * size + column
                    real[target] -= multiplierReal * real[upperAt] - multiplierImaginary * imaginary[upperAt]
                    imaginary[target] -= multiplierReal * imaginary[upperAt] + multiplierImaginary * real[upperAt]
                }
            }
        }
    }

    /** Solves block [block]'s factored complement against the vector at [offset] of [real], [imaginary], in place. */
    private fun solveBlock(block: Int, real: DoubleArray, imaginary: DoubleArray, offset: Int) {
        val size = blockSize
        val base = block * blockArea
        for (step in 0 until size) {
            val pivotRow = pivots[block * size + step]
            if (pivotRow != step) {
                val swapReal = real[offset + step]; real[offset + step] = real[offset + pivotRow]; real[offset + pivotRow] = swapReal
                val swapImaginary = imaginary[offset + step]; imaginary[offset + step] = imaginary[offset + pivotRow]; imaginary[offset + pivotRow] = swapImaginary
            }
        }
        for (row in 1 until size) {
            var sumReal = 0.0
            var sumImaginary = 0.0
            for (column in 0 until row) {
                val factorReal = diagonalReal[base + row * size + column]
                val factorImaginary = diagonalImaginary[base + row * size + column]
                sumReal += factorReal * real[offset + column] - factorImaginary * imaginary[offset + column]
                sumImaginary += factorReal * imaginary[offset + column] + factorImaginary * real[offset + column]
            }
            real[offset + row] -= sumReal
            imaginary[offset + row] -= sumImaginary
        }
        for (row in size - 1 downTo 0) {
            var sumReal = real[offset + row]
            var sumImaginary = imaginary[offset + row]
            for (column in row + 1 until size) {
                val factorReal = diagonalReal[base + row * size + column]
                val factorImaginary = diagonalImaginary[base + row * size + column]
                sumReal -= factorReal * real[offset + column] - factorImaginary * imaginary[offset + column]
                sumImaginary -= factorReal * imaginary[offset + column] + factorImaginary * real[offset + column]
            }
            val pivotReal = diagonalReal[base + row * size + row]
            val pivotImaginary = diagonalImaginary[base + row * size + row]
            val pivotNorm = pivotReal * pivotReal + pivotImaginary * pivotImaginary
            real[offset + row] = (sumReal * pivotReal + sumImaginary * pivotImaginary) / pivotNorm
            imaginary[offset + row] = (sumImaginary * pivotReal - sumReal * pivotImaginary) / pivotNorm
        }
    }

    companion object {
        /**
         * A pivot this small a share of the block's largest entry is a singular block: about a
         * thousand times the double's rounding, so a pivot lost to cancellation is refused rather
         * than divided by.
         */
        const val SINGULAR_SHARE = 1e-13
    }
}
