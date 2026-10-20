package com.cartogenesis.worldgen.pipeline

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A plate of the tectonic history: its rotation about the planet's center, in radians per million
 * years as a vector along the Euler pole (`v = omega x r`, Cortial et al. 2019), and whether it is
 * still on the planet.
 */
internal class HistoryPlate(val id: Int) {
    var omegaX = 0.0
    var omegaY = 0.0
    var omegaZ = 0.0
    var alive = true

    /** Part of the supercontinent, which moves as one until it breaks up. */
    var assembled = false

    /** Until when, Myr before the present, a split of this plate is not tried again; it has been tried and held. */
    var heldTogetherUntilMyrAgo = Double.MAX_VALUE

    /**
     * Volume (km³) a rifted margin of this plate has given up by stretching, still to be laid out as
     * crust, and the area (km²) it is to cover: a sample stretched by beta frees `h (1 - 1/beta)` of
     * its column to spread over `beta - 1` more of its own area.
     */
    var stretchPoolKm3 = 0.0
    var stretchPoolAreaKm2 = 0.0

    fun setOmega(x: Double, y: Double, z: Double) {
        omegaX = x; omegaY = y; omegaZ = z
    }

    /** The plate's speed at the unit vector given, km per Myr, which is mm a year. */
    fun speedAt(unitX: Double, unitY: Double, unitZ: Double, radiusKm: Double): Double {
        val vx = omegaY * unitZ - omegaZ * unitY
        val vy = omegaZ * unitX - omegaX * unitZ
        val vz = omegaX * unitY - omegaY * unitX
        return sqrt(vx * vx + vy * vy + vz * vz) * radiusKm
    }
}

/**
 * A rigid rotation about a unit axis, as Rodrigues' matrix, row-major, for carrying a plate's crust
 * one step: `R p` rotates `p` by [angleRadians] about ([axisX], [axisY], [axisZ]).
 */
internal class Rotation(axisX: Double, axisY: Double, axisZ: Double, angleRadians: Double) {
    private val m = DoubleArray(9)

    init {
        val c = cos(angleRadians)
        val s = sin(angleRadians)
        val t = 1.0 - c
        m[0] = t * axisX * axisX + c; m[1] = t * axisX * axisY - s * axisZ; m[2] = t * axisX * axisZ + s * axisY
        m[3] = t * axisX * axisY + s * axisZ; m[4] = t * axisY * axisY + c; m[5] = t * axisY * axisZ - s * axisX
        m[6] = t * axisX * axisZ - s * axisY; m[7] = t * axisY * axisZ + s * axisX; m[8] = t * axisZ * axisZ + c
    }

    fun x(px: Double, py: Double, pz: Double) = m[0] * px + m[1] * py + m[2] * pz
    fun y(px: Double, py: Double, pz: Double) = m[3] * px + m[4] * py + m[5] * pz
    fun z(px: Double, py: Double, pz: Double) = m[6] * px + m[7] * py + m[8] * pz

    companion object {
        /** The rotation a plate turning at [plate]'s rate makes in [myr]; the identity for a still plate. */
        fun of(plate: HistoryPlate, myr: Double): Rotation {
            val rate = sqrt(plate.omegaX * plate.omegaX + plate.omegaY * plate.omegaY + plate.omegaZ * plate.omegaZ)
            if (rate <= 0.0) return Rotation(0.0, 0.0, 1.0, 0.0)
            return Rotation(plate.omegaX / rate, plate.omegaY / rate, plate.omegaZ / rate, rate * myr)
        }
    }
}

/**
 * The forces on plates, and the rotation each settles at under them.
 *
 * Forsyth and Uyeda (*On the relative importance of the driving forces of plate motion*, Geophys.
 * J. R. Astron. Soc. 43, 1975) inverted the plates' motions for the forces on them: the pull of the
 * slab is an order of magnitude stronger than any other, a plate attached to a slab moves at a
 * terminal velocity where the slab's weight is balanced by the mantle resisting it, and the drag
 * under a plate is stronger beneath continents than beneath oceans. Plate inertia is nothing, so a
 * plate's rotation is the one at which the torques on it sum to zero: the driving forces at its
 * edges against a drag over its base.
 *
 * Each force is booked where it acts, as a vector in the tangent plane at a lattice point weighted
 * by the length of boundary the point stands for (one spacing), so a plate's torque is `sum r x F`.
 * The drag on a plate turning at `omega` is `-sum k a (omega - (omega . r) r)` over its points, `k`
 * one under ocean and [CONTINENTAL_DRAG_RATIO] under continent; the balance is a 3 by 3 solve.
 *
 * The forces' absolute size is not something the history can know, since it carries no mantle
 * viscosity; what it can know is their ratios, which are Forsyth and Uyeda's, and Earth's plate
 * speeds, so the rotations are scaled together until the median plate's root-mean-square speed is
 * Earth's ([com.cartogenesis.worldgen.model.TectonicsConfig.medianPlateSpeedMmPerYear]).
 */
internal class PlateForces(pointCount: Int) {
    val point = IntArray(pointCount * EVENTS_PER_POINT)
    val plate = IntArray(pointCount * EVENTS_PER_POINT)
    val forceX = DoubleArray(pointCount * EVENTS_PER_POINT)
    val forceY = DoubleArray(pointCount * EVENTS_PER_POINT)
    val forceZ = DoubleArray(pointCount * EVENTS_PER_POINT)
    var count = 0
        private set

    fun clear() {
        count = 0
    }

    /** Books a force on [plateId] at [atPoint], [magnitude] along the unit tangent ([dx], [dy], [dz]). */
    fun add(atPoint: Int, plateId: Int, magnitude: Double, dx: Double, dy: Double, dz: Double) {
        if (count == point.size) return
        point[count] = atPoint
        plate[count] = plateId
        forceX[count] = magnitude * dx
        forceY[count] = magnitude * dy
        forceZ[count] = magnitude * dz
        count++
    }

    companion object {
        /** A lattice point can carry a force for each plate it borders; three is more than any does. */
        const val EVENTS_PER_POINT = 3

        /**
         * Slab pull, per spacing of trench, the unit every other force is a share of: Forsyth and
         * Uyeda's order of magnitude above the rest.
         */
        const val SLAB_PULL = 1.0

        /**
         * The pull of a trench on the plate above it, per spacing of trench: half the slab's.
         * Conrad and Lithgow-Bertelloni (*How mantle slabs drive plate tectonics*, Science 298, 2002)
         * find the slabs attached in the upper mantle pull with about half the force that drives
         * plates and the slabs sunk into the lower mantle drive the rest through suction, the mantle
         * flow they excite drawing the plates either side toward the trench; the upper plate's share
         * is taken as half the subducting plate's. It is what carries a supercontinent's pieces
         * outward to the trenches that ring it once it breaks, the plates of the Americas moving
         * west.
         */
        const val TRENCH_SUCTION = 0.5

        /** The push of a ridge on the plates either side, per spacing of ridge: an order of magnitude below the slab's (Forsyth and Uyeda). */
        const val RIDGE_PUSH = 0.1

        /**
         * A continent's refusal to go down a trench, per spacing of contact, as a share of the slab
         * pull it stands against: one. India's convergence with Eurasia fell by 40 to 45% when the
         * two met and has gone on since, slowing by another 30% around 20 million years ago
         * (Molnar and Stock, *Slowing of India's convergence with Eurasia since 20 Ma*, Tectonics
         * 28, 2009, and the reconstructions that followed it), so the collision resists without
         * arresting: the Indian plate's slabs to the east are about twice the collision's length,
         * and a resistance equal to the pull per length takes about half of its drive away.
         */
        const val COLLISION_RESISTANCE = 1.0

        /**
         * How much harder the base of a continent drags than the base of an ocean (Forsyth and
         * Uyeda: stronger under continents). Three, the ratio of Zahirovic et al.'s speeds for
         * oceanic plates bounded by subduction, about 85 mm a year, to plates more than a quarter
         * cratonic, about 28 (*Tectonic speed limits from plate kinematic reconstructions*, EPSL
         * 418, 2015; see `TectonicsConfig.medianPlateSpeedMmPerYear`).
         */
        const val CONTINENTAL_DRAG_RATIO = 1.5
    }
}

/** The 3 by 3 symmetric drag matrix of a set of points, and the torque on them, to solve for a rotation. */
internal class TorqueBalance {
    val drag = DoubleArray(9)
    val torque = DoubleArray(3)

    fun clear() {
        drag.fill(0.0)
        torque.fill(0.0)
    }

    /** Adds a point's drag, `k (I - r r^T)`. */
    fun addDrag(rx: Double, ry: Double, rz: Double, weight: Double) {
        drag[0] += weight * (1 - rx * rx); drag[1] -= weight * rx * ry; drag[2] -= weight * rx * rz
        drag[3] -= weight * ry * rx; drag[4] += weight * (1 - ry * ry); drag[5] -= weight * ry * rz
        drag[6] -= weight * rz * rx; drag[7] -= weight * rz * ry; drag[8] += weight * (1 - rz * rz)
    }

    /** Adds the torque `r x F` of a force at a point. */
    fun addForce(rx: Double, ry: Double, rz: Double, fx: Double, fy: Double, fz: Double) {
        torque[0] += ry * fz - rz * fy
        torque[1] += rz * fx - rx * fz
        torque[2] += rx * fy - ry * fx
    }

    fun add(other: TorqueBalance) {
        for (index in 0 until 9) drag[index] += other.drag[index]
        for (index in 0 until 3) torque[index] += other.torque[index]
    }

    /** The rotation at which drag balances torque, `drag^-1 torque`, or zero if the drag is singular. */
    fun solve(): DoubleArray {
        val d = drag
        val c00 = d[4] * d[8] - d[5] * d[7]
        val c01 = d[5] * d[6] - d[3] * d[8]
        val c02 = d[3] * d[7] - d[4] * d[6]
        val determinant = d[0] * c00 + d[1] * c01 + d[2] * c02
        if (abs(determinant) < SINGULAR_DETERMINANT) return DoubleArray(3)
        val inverse = doubleArrayOf(
            c00, d[2] * d[7] - d[1] * d[8], d[1] * d[5] - d[2] * d[4],
            c01, d[0] * d[8] - d[2] * d[6], d[2] * d[3] - d[0] * d[5],
            c02, d[1] * d[6] - d[0] * d[7], d[0] * d[4] - d[1] * d[3]
        )
        val t = torque
        return doubleArrayOf(
            (inverse[0] * t[0] + inverse[1] * t[1] + inverse[2] * t[2]) / determinant,
            (inverse[3] * t[0] + inverse[4] * t[1] + inverse[5] * t[2]) / determinant,
            (inverse[6] * t[0] + inverse[7] * t[1] + inverse[8] * t[2]) / determinant
        )
    }

    private companion object {
        /** A plate of a few points in a line has no drag about its own axis; its rotation is left still. */
        const val SINGULAR_DETERMINANT = 1e-12
    }
}
