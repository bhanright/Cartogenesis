package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.ComplexBlockTridiagonal
import com.cartogenesis.worldgen.math.SymmetricEigen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The stationary-wave model's two pieces of linear algebra, held to their definitions. */
class WaveSolverMathTest {

    @Test
    fun `the block Thomas solve satisfies every equation, for several right-hand sides`() {
        val random = Random(20261009)
        val blocks = 9
        val size = 6
        val system = ComplexBlockTridiagonal(blocks, size)
        for (index in system.diagonalReal.indices) {
            system.lowerReal[index] = random.nextDouble(-1.0, 1.0); system.lowerImaginary[index] = random.nextDouble(-1.0, 1.0)
            system.upperReal[index] = random.nextDouble(-1.0, 1.0); system.upperImaginary[index] = random.nextDouble(-1.0, 1.0)
            system.diagonalReal[index] = random.nextDouble(-1.0, 1.0); system.diagonalImaginary[index] = random.nextDouble(-1.0, 1.0)
        }
        // Small diagonals in places, so the pivoting has to choose.
        for (block in 0 until blocks) system.diagonalReal[system.at(block, 0, 0)] = 1e-9
        val lowerReal = system.lowerReal.copyOf(); val lowerImaginary = system.lowerImaginary.copyOf()
        val diagonalReal = system.diagonalReal.copyOf(); val diagonalImaginary = system.diagonalImaginary.copyOf()
        val upperReal = system.upperReal.copyOf(); val upperImaginary = system.upperImaginary.copyOf()
        system.factorize()
        repeat(3) {
            val rightReal = DoubleArray(blocks * size) { random.nextDouble(-1.0, 1.0) }
            val rightImaginary = DoubleArray(blocks * size) { random.nextDouble(-1.0, 1.0) }
            val solutionReal = rightReal.copyOf()
            val solutionImaginary = rightImaginary.copyOf()
            system.solve(solutionReal, solutionImaginary)
            var worst = 0.0
            for (block in 0 until blocks) for (row in 0 until size) {
                var sumReal = 0.0
                var sumImaginary = 0.0
                for ((offset, real, imaginary) in listOf(Triple(-1, lowerReal, lowerImaginary), Triple(0, diagonalReal, diagonalImaginary), Triple(1, upperReal, upperImaginary))) {
                    val target = block + offset
                    if (target < 0 || target >= blocks) continue
                    for (column in 0 until size) {
                        val at = system.at(block, row, column)
                        val xReal = solutionReal[target * size + column]
                        val xImaginary = solutionImaginary[target * size + column]
                        sumReal += real[at] * xReal - imaginary[at] * xImaginary
                        sumImaginary += real[at] * xImaginary + imaginary[at] * xReal
                    }
                }
                worst = max(worst, abs(sumReal - rightReal[block * size + row]) + abs(sumImaginary - rightImaginary[block * size + row]))
            }
            println("BLOCK THOMAS residual %.2e on a random system of $blocks blocks of $size".format(worst))
            assertTrue(worst < 1e-10, "the solve leaves a residual of $worst")
        }
    }

    @Test
    fun `a singular block is refused rather than divided by`() {
        val system = ComplexBlockTridiagonal(2, 2)
        system.diagonalReal[system.at(0, 0, 0)] = 1.0
        system.diagonalReal[system.at(0, 1, 0)] = 2.0
        system.diagonalReal[system.at(1, 0, 0)] = 1.0
        system.diagonalReal[system.at(1, 1, 1)] = 1.0
        assertFailsWith<IllegalStateException> { system.factorize() }
    }

    @Test
    fun `Jacobi's eigenvectors are orthonormal and satisfy their equations`() {
        val random = Random(42)
        for (size in listOf(2, 3, 5, 8)) {
            val matrix = DoubleArray(size * size)
            for (row in 0 until size) for (column in 0..row) {
                val value = random.nextDouble(-2.0, 2.0)
                matrix[row * size + column] = value
                matrix[column * size + row] = value
            }
            val eigen = SymmetricEigen(matrix, size)
            var residual = 0.0
            var orthogonality = 0.0
            for (mode in 0 until size) {
                val vector = eigen.vector(mode)
                for (row in 0 until size) {
                    var product = 0.0
                    for (column in 0 until size) product += matrix[row * size + column] * vector[column]
                    residual = max(residual, abs(product - eigen.values[mode] * vector[row]))
                }
                for (other in 0 until size) {
                    val dot = (0 until size).sumOf { vector[it] * eigen.vector(other)[it] }
                    orthogonality = max(orthogonality, abs(dot - if (other == mode) 1.0 else 0.0))
                }
                if (mode > 0) assertTrue(eigen.values[mode] >= eigen.values[mode - 1], "eigenvalues out of order")
            }
            println("JACOBI size $size: residual %.1e, orthonormality %.1e".format(residual, orthogonality))
            assertTrue(residual < 1e-12 * sqrt(size.toDouble()) * 10 && orthogonality < 1e-12, "size $size: residual $residual, orthogonality $orthogonality")
        }
    }
}
