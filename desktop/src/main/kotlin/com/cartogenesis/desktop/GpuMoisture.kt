package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.FreeTroposphereTerms
import com.cartogenesis.worldgen.pipeline.MoistureAccelerator
import com.cartogenesis.worldgen.pipeline.MoistureDevice
import com.cartogenesis.worldgen.pipeline.MoistureDeviceRun
import com.cartogenesis.worldgen.pipeline.MoistureDeviceSeason
import com.cartogenesis.worldgen.pipeline.MoistureLapBudget
import java.util.Collections
import java.util.concurrent.Future
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL33C
import org.lwjgl.opengl.GL40C
import org.lwjgl.opengl.GL43C

/**
 * Marches the climate's rain on the graphics card: each lap of `MoistureMarch`, both seasons and
 * both layers, with the water in flight kept on the card from a run's first lap to its last.
 *
 * The algorithm is the processor's, read line for line (`moisture_march.comp`): a lap is the
 * schedule's steps, each a column of each of the four queues (two seasons, two layers), one
 * dispatch solving each queue's column of faces in a workgroup and one crossing its rows side by
 * side. Its answer is not the processor's to the bit, for two reasons this class's [Precision]
 * chooses between; it is held to the processor's within the coupled loop's own settling tolerance
 * (`GpuMoistureTest`), and its water budget closes on its own ledger to the processor's bar.
 *
 * Within a column the rows' order does not matter (each row reads its own arrival and writes its
 * own cells), and the columns are marched in the processor's order, so one world on one device
 * gives one climate, run after run.
 */
class GpuMoisture private constructor(
    override val name: String,
    /** Which precision the per-cell arithmetic and the faces' solve run in. */
    val precision: Precision
) : MoistureAccelerator {

    /** The two forms the march was measured in (docs/DESIGN_LEDGER.md, W2g). */
    enum class Precision {
        /**
         * The crossings' heavy arithmetic, their exponentials, logarithms and root searches, in
         * single precision, every budget closed in double precision on what that arithmetic
         * returns; the faces solved in single precision and refined against the matrix in double.
         * What the application uses: the card's double precision is a sixty-fourth of its single.
         */
        MIXED,

        /** Everything in double precision, the exponentials and logarithms by series: the control. */
        DOUBLE
    }

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuMoisture?, val unavailableBecause: String?)

    /** Whether the march hands each lap's budget back ([budgets]); for the tests. */
    @Volatile
    override var measuresBudget: Boolean = false

    /** Every lap's budget measured on the card since the last [clearRecords], when [measuresBudget]. */
    val budgets: MutableList<MoistureLapBudget> = Collections.synchronizedList(ArrayList())

    /** Each lap's time on the card itself, milliseconds, by the card's own timer: a report for the cost probes. */
    val lapDeviceMillis: MutableList<Double> = Collections.synchronizedList(ArrayList())

    /** Forgets [budgets] and [lapDeviceMillis]. */
    fun clearRecords() {
        budgets.clear()
        lapDeviceMillis.clear()
    }

    /** The compiled kernels for each grid this has marched, by [ProgramKey]. Touched on the context's thread only. */
    private val programs = HashMap<ProgramKey, Programs>()

    private data class ProgramKey(val rows: Int, val columns: Int, val measuring: Boolean)
    private class Programs(val faces: Int, val crossings: Int)

    override fun open(run: MoistureDeviceRun): MoistureDevice? {
        val rows = run.cellsDown
        val cellCount = run.cellCount.toLong()
        if (rows <= 1 || run.cellsAcross <= 1) return null
        // The faces' solve holds a column in one workgroup's shared memory, and the largest buffer
        // must fit one storage block; past either, the processor marches.
        if (rows > maxSolvedRows) return null
        val largest = STATE_FIELDS * SEASONS * cellCount * Double.SIZE_BYTES
        if (largest > maxStorageBlockBytes) return null
        if (run.measuring && LEDGER_FIELDS * SEASONS * LAYERS * cellCount * Double.SIZE_BYTES > maxStorageBlockBytes) return null
        return GlContext.run("Opening the moisture march") {
            val programs = programsFor(rows, run.cellsAcross, run.measuring, run.constants) ?: return@run null
            val march = DeviceMarch(run, programs)
            if (march.upload()) march else {
                march.release()
                null
            }
        }
    }

    /** The kernels for a grid, compiled the first time it is seen. On the context's thread. */
    private fun programsFor(rows: Int, columns: Int, measuring: Boolean, constants: Map<String, Double>): Programs? {
        val key = ProgramKey(rows, columns, measuring)
        programs[key]?.let { return it }
        val faces = GlContext.compileCompute(source("FACES", rows, columns, measuring, constants))
        val crossings = GlContext.compileCompute(source("CROSSINGS", rows, columns, measuring, constants))
        return Programs(faces, crossings).also { programs[key] = it }
    }

    /** The shader's source for [kernel] on a grid of [rows] by [columns]: the definitions it reads, then the file. */
    private fun source(kernel: String, rows: Int, columns: Int, measuring: Boolean, constants: Map<String, Double>): String {
        val workgroupRows = workgroupRowsFor(rows)
        val header = StringBuilder()
        header.append("#version 430\n")
        header.append("#define $kernel 1\n")
        header.append("#define MIXED ${if (precision == Precision.MIXED) 1 else 0}\n")
        header.append("#define MEASURING ${if (measuring) 1 else 0}\n")
        header.append("#define ROWS $rows\n")
        header.append("#define COLUMNS $columns\n")
        header.append("#define CELLS ${rows * columns}\n")
        header.append("#define WORKGROUP_ROWS $workgroupRows\n")
        header.append("#define ROWS_PER_THREAD ${(rows + workgroupRows - 1) / workgroupRows}\n")
        header.append("#define CROSSING_GROUP $CROSSING_GROUP\n")
        header.append("#define REFINEMENTS $REFINEMENTS\n")
        header.append("#define STEP_WIDTH ${MoistureDeviceRun.STEP_WIDTH}\n")
        header.append("uniform double RAIN_SHADOW_SCALE;\n")
        header.append("const double SINGLE_BALANCE_TOLERANCE = ${literal(SINGLE_BALANCE_TOLERANCE)};\n")
        for ((name, value) in constants.toSortedMap()) header.append("const double $name = ${literal(value)};\n")
        return header.toString() + KERNEL_SOURCE
    }

    /**
     * One run's march on the card: its buffers, the steps of a lap and what is in flight between
     * [startLap] and [finishLap]. Every GL call is on the context's thread.
     */
    private inner class DeviceMarch(val run: MoistureDeviceRun, val programs: Programs) : MoistureDevice {
        val rows = run.cellsDown
        val cells = run.cellCount
        val steps = run.steps()
        val stepCount = steps.size / MoistureDeviceRun.STEP_WIDTH
        val buffers = IntArray(BUFFER_COUNT)
        val smallLayout = SmallLayout(run)
        var timerQuery = 0
        var pending: Future<Boolean?>? = null

        /** Allocates every buffer and uploads the run's fields and its water. On the context's thread. */
        fun upload(): Boolean {
            GL15C.glGenBuffers(buffers)
            timerQuery = GL15C.glGenQueries()
            allocate(STATE_BINDING, STATE_FIELDS.toLong() * SEASONS * cells * Double.SIZE_BYTES)
            allocate(TERMS_BINDING, TERM_FIELDS.toLong() * SEASONS * cells * Double.SIZE_BYTES)
            allocate(FIELDS_BINDING, FIELD_COUNT.toLong() * SEASONS * cells * Float.SIZE_BYTES)
            allocate(RESULTS_BINDING, RESULT_FIELDS.toLong() * SEASONS * cells * Float.SIZE_BYTES)
            allocate(INTS_BINDING, INT_FIELDS.toLong() * SEASONS * cells * Int.SIZE_BYTES)
            allocate(SMALL_BINDING, smallLayout.total.toLong() * Double.SIZE_BYTES)
            allocate(ROLES_BINDING, QUEUES.toLong() * rows * Int.SIZE_BYTES)
            bind(STEPS_BINDING)
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, steps, GL15C.GL_STATIC_DRAW)
            bind(LAYOUT_BINDING)
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, smallLayout.table, GL15C.GL_STATIC_DRAW)
            if (run.measuring) {
                allocate(LEDGER_BINDING, LEDGER_FIELDS.toLong() * SEASONS * LAYERS * cells * Double.SIZE_BYTES)
                allocate(LEDGER_WATER_BINDING, SEASONS.toLong() * LAYERS * cells * Float.SIZE_BYTES)
                allocate(LEDGER_FLAGS_BINDING, SEASONS.toLong() * LAYERS * cells * Int.SIZE_BYTES)
            }
            if (GL15C.glGetError() != GL15C.GL_NO_ERROR) return false
            for ((season, fields) in run.seasons.withIndex()) {
                uploadSurfaceAndSweeps(season, fields)
                uploadAirFields(season, fields)
                uploadWindFields(season, fields)
                uploadState(season, fields)
            }
            uploadSmall()
            return GL15C.glGetError() == GL15C.GL_NO_ERROR
        }

        override fun startLap(fixedFieldsChanged: Boolean): Boolean {
            check(pending == null) { "a lap is already marching" }
            pending = GlContext.submit("The moisture march's lap") {
                for ((season, fields) in run.seasons.withIndex()) {
                    if (fixedFieldsChanged) {
                        uploadSurfaceAndSweeps(season, fields)
                        uploadAirFields(season, fields)
                    }
                    uploadWindFields(season, fields)
                }
                if (GL15C.glGetError() != GL15C.GL_NO_ERROR) return@submit false
                for (binding in 0 until BUFFER_COUNT) {
                    if (buffers[binding] != 0) GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffers[binding])
                }
                GL43C.glUseProgram(programs.faces)
                GL40C.glUniform1d(GL43C.glGetUniformLocation(programs.faces, "RAIN_SHADOW_SCALE"), run.rainShadowScale)
                val facesStep = GL43C.glGetUniformLocation(programs.faces, "uStep")
                GL43C.glUseProgram(programs.crossings)
                GL40C.glUniform1d(GL43C.glGetUniformLocation(programs.crossings, "RAIN_SHADOW_SCALE"), run.rainShadowScale)
                val crossingsStep = GL43C.glGetUniformLocation(programs.crossings, "uStep")
                val rowGroups = (rows + CROSSING_GROUP - 1) / CROSSING_GROUP
                GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, timerQuery)
                for (step in 0 until stepCount) {
                    GL43C.glUseProgram(programs.faces)
                    GL43C.glUniform1i(facesStep, step)
                    GL43C.glDispatchCompute(QUEUES, 1, 1)
                    // The crossings read the faces' solve, which other work groups have just written.
                    GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
                    GL43C.glUseProgram(programs.crossings)
                    GL43C.glUniform1i(crossingsStep, step)
                    GL43C.glDispatchCompute(rowGroups, QUEUES, 1)
                    // And the next column's arrivals read the parcels and banks they wrote.
                    GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
                }
                GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED)
                GL15C.glFlush()
                GL15C.glGetError() == GL15C.GL_NO_ERROR
            }
            return true
        }

        override fun finishLap(): Boolean {
            val started = pending ?: return false
            pending = null
            if (GlContext.await("The moisture march's lap", started) != true) return false
            return GlContext.run("Reading the moisture march's lap") {
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
                for ((season, fields) in run.seasons.withIndex()) {
                    readResult(season, RAIN, fields.boundaryRainMm)
                    readResult(season, CONDENSATION, fields.condensationMm)
                    readResult(season, HUMIDITY, fields.humidity)
                    readResult(season, FREE_RAIN, fields.freeRainMm)
                }
                lapDeviceMillis += GL33C.glGetQueryObjecti64(timerQuery, GL15C.GL_QUERY_RESULT) / NANOSECONDS_PER_MILLISECOND
                if (run.measuring) {
                    readLedger()
                    readStateArrays()
                }
                GL15C.glGetError() == GL15C.GL_NO_ERROR
            } == true
        }

        override fun readState(): Boolean = GlContext.run("Reading the moisture march's end") {
            GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
            readStateArrays()
            for ((season, fields) in run.seasons.withIndex()) {
                readResult(season, LAND_RAIN, fields.boundaryLandRainMm)
                readResult(season, SOURCE, fields.sourceMm)
                readResult(season, FREE_LAND_RAIN, fields.freeLandRainMm)
            }
            GL15C.glGetError() == GL15C.GL_NO_ERROR
        } == true

        override fun budgetMeasured(budget: MoistureLapBudget) {
            budgets += budget
        }

        override fun close() {
            pending?.let { GlContext.await("The moisture march's last lap", it) }
            pending = null
            GlContext.run("Releasing the moisture march") { release() }
        }

        /** Lets the buffers go. On the context's thread. */
        fun release() {
            GL15C.glDeleteBuffers(buffers)
            buffers.fill(0)
            if (timerQuery != 0) GL15C.glDeleteQueries(timerQuery)
            timerQuery = 0
        }

        private fun bind(binding: Int) = GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[binding])

        private fun allocate(binding: Int, bytes: Long) {
            bind(binding)
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, bytes, GL15C.GL_DYNAMIC_COPY)
        }

        private fun put(binding: Int, offsetElements: Long, elementBytes: Int, data: FloatArray) {
            bind(binding); GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, offsetElements * elementBytes, data)
        }

        private fun put(binding: Int, offsetElements: Long, elementBytes: Int, data: DoubleArray) {
            bind(binding); GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, offsetElements * elementBytes, data)
        }

        private fun put(binding: Int, offsetElements: Long, elementBytes: Int, data: IntArray) {
            bind(binding); GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, offsetElements * elementBytes, data)
        }

        private fun fieldOffset(season: Int, field: Int) = (season.toLong() * FIELD_COUNT + field) * cells
        private fun stateOffset(season: Int, field: Int) = (season.toLong() * STATE_FIELDS + field) * cells
        private fun termOffset(season: Int, field: Int) = (season.toLong() * TERM_FIELDS + field) * cells
        private fun resultOffset(season: Int, field: Int) = (season.toLong() * RESULT_FIELDS + field) * cells
        private fun intOffset(season: Int, field: Int) = (season.toLong() * INT_FIELDS + field) * cells

        private fun uploadSurfaceAndSweeps(season: Int, fields: MoistureDeviceSeason) {
            val kind = IntArray(cells) { here ->
                fields.surface[here].toInt() or
                    (if (fields.boundary.direction[here] > 0) BOUNDARY_EAST_BIT else 0) or
                    (if (fields.free.direction[here] > 0) FREE_EAST_BIT else 0)
            }
            put(INTS_BINDING, intOffset(season, KIND), Int.SIZE_BYTES, kind)
            put(INTS_BINDING, intOffset(season, TURN_SLOT), Int.SIZE_BYTES, fields.turnSlot)
            put(INTS_BINDING, intOffset(season, BL_SENT_SLOT), Int.SIZE_BYTES, fields.boundary.sentSlot)
            put(INTS_BINDING, intOffset(season, FT_SENT_SLOT), Int.SIZE_BYTES, fields.free.sentSlot)
        }

        private fun uploadAirFields(season: Int, fields: MoistureDeviceSeason) {
            put(FIELDS_BINDING, fieldOffset(season, CAP), Float.SIZE_BYTES, fields.boundaryCapMm)
            put(FIELDS_BINDING, fieldOffset(season, GROUND_PRESSURE), Float.SIZE_BYTES, fields.groundPressureKpa)
            put(FIELDS_BINDING, fieldOffset(season, LAYER_DEPTH), Float.SIZE_BYTES, fields.layerDepthM)
            put(FIELDS_BINDING, fieldOffset(season, AIR_C_ROW_MAJOR), Float.SIZE_BYTES, fields.airTemperatureCRowMajor())
            put(FIELDS_BINDING, fieldOffset(season, SEA_HUMIDITY), Float.SIZE_BYTES, fields.seaSurfaceHumidity)
            put(FIELDS_BINDING, fieldOffset(season, SUPPRESSION), Float.SIZE_BYTES, fields.suppression)
            // Built for the upload and let go: the card keeps them for the run.
            val terms = FreeTroposphereTerms(cells)
            fields.fillCrossingTerms(terms)
            put(TERMS_BINDING, termOffset(season, EXCHANGE), Double.SIZE_BYTES, terms.exchangePerSecond)
            put(TERMS_BINDING, termOffset(season, LAW_SATURATED), Double.SIZE_BYTES, terms.lawSaturatedMm)
            put(TERMS_BINDING, termOffset(season, LAW_OFFSET), Double.SIZE_BYTES, terms.lawOffsetMm)
            put(TERMS_BINDING, termOffset(season, FREE_SATURATED), Double.SIZE_BYTES, terms.freeSaturatedMm)
            put(TERMS_BINDING, termOffset(season, BASE_SHARE), Double.SIZE_BYTES, terms.baseShare)
        }

        private fun uploadWindFields(season: Int, fields: MoistureDeviceSeason) {
            put(FIELDS_BINDING, fieldOffset(season, BL_STRETCH), Float.SIZE_BYTES, fields.boundary.stretch)
            put(FIELDS_BINDING, fieldOffset(season, FT_STRETCH), Float.SIZE_BYTES, fields.free.stretch)
            put(FIELDS_BINDING, fieldOffset(season, BL_FACE), Float.SIZE_BYTES, fields.boundary.faceShare)
            put(FIELDS_BINDING, fieldOffset(season, FT_FACE), Float.SIZE_BYTES, fields.free.faceShare)
            put(FIELDS_BINDING, fieldOffset(season, SEA_TRANSFER), Float.SIZE_BYTES, fields.seaTransferMassFlux)
            put(FIELDS_BINDING, fieldOffset(season, LIFT), Float.SIZE_BYTES, fields.liftMps)
            put(FIELDS_BINDING, fieldOffset(season, DESCENT), Float.SIZE_BYTES, fields.descentRate)
            put(FIELDS_BINDING, fieldOffset(season, GROUND_RETURN), Float.SIZE_BYTES, fields.groundReturnRate)
        }

        private fun uploadState(season: Int, fields: MoistureDeviceSeason) {
            put(STATE_BINDING, stateOffset(season, BL_BANK), Double.SIZE_BYTES, fields.boundary.bank)
            put(STATE_BINDING, stateOffset(season, BL_BANK_LAND), Double.SIZE_BYTES, fields.boundary.bankLand)
            put(STATE_BINDING, stateOffset(season, BL_BANK_AIR), Double.SIZE_BYTES, checkNotNull(fields.boundary.bankAir))
            put(STATE_BINDING, stateOffset(season, FT_BANK), Double.SIZE_BYTES, fields.free.bank)
            put(STATE_BINDING, stateOffset(season, FT_BANK_LAND), Double.SIZE_BYTES, fields.free.bankLand)
            put(STATE_BINDING, stateOffset(season, TO_FREE), Double.SIZE_BYTES, fields.toFree)
            put(STATE_BINDING, stateOffset(season, TO_FREE_LAND), Double.SIZE_BYTES, fields.toFreeLand)
            put(STATE_BINDING, stateOffset(season, TO_BOUNDARY), Double.SIZE_BYTES, fields.toBoundary)
            put(STATE_BINDING, stateOffset(season, TO_BOUNDARY_LAND), Double.SIZE_BYTES, fields.toBoundaryLand)
            put(FIELDS_BINDING, fieldOffset(season, REPLACED), Float.SIZE_BYTES, fields.replacedPerSecond)
        }

        private fun uploadSmall() {
            val small = DoubleArray(smallLayout.total)
            for ((season, fields) in run.seasons.withIndex()) {
                smallLayout.scatter(season, fields, small)
            }
            for (row in 0 until rows) {
                small[smallLayout.rowConstants + row] = run.secondsPerColumn[row]
                small[smallLayout.rowConstants + rows + row] = run.faceMixing[row]
            }
            put(SMALL_BINDING, 0L, Double.SIZE_BYTES, small)
        }

        private fun readResult(season: Int, field: Int, into: FloatArray) {
            bind(RESULTS_BINDING)
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, resultOffset(season, field) * Float.SIZE_BYTES, into)
        }

        private fun readState(season: Int, field: Int, into: DoubleArray) {
            bind(STATE_BINDING)
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, stateOffset(season, field) * Double.SIZE_BYTES, into)
        }

        /** The water in flight, into each season's own arrays. On the context's thread. */
        private fun readStateArrays() {
            for ((season, fields) in run.seasons.withIndex()) {
                readState(season, BL_BANK, fields.boundary.bank)
                readState(season, BL_BANK_LAND, fields.boundary.bankLand)
                readState(season, BL_BANK_AIR, checkNotNull(fields.boundary.bankAir))
                readState(season, FT_BANK, fields.free.bank)
                readState(season, FT_BANK_LAND, fields.free.bankLand)
                readState(season, TO_FREE, fields.toFree)
                readState(season, TO_FREE_LAND, fields.toFreeLand)
                readState(season, TO_BOUNDARY, fields.toBoundary)
                readState(season, TO_BOUNDARY_LAND, fields.toBoundaryLand)
                bind(FIELDS_BINDING)
                GL15C.glGetBufferSubData(
                    GL43C.GL_SHADER_STORAGE_BUFFER, fieldOffset(season, REPLACED) * Float.SIZE_BYTES, fields.replacedPerSecond
                )
            }
            val small = DoubleArray(smallLayout.total)
            bind(SMALL_BINDING)
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, small)
            for ((season, fields) in run.seasons.withIndex()) smallLayout.gather(season, small, fields)
        }

        /** What each crossing did, into each season's layers' budgets. On the context's thread. */
        private fun readLedger() {
            for ((season, fields) in run.seasons.withIndex()) {
                for ((layerIndex, layer) in listOf(fields.boundary, fields.free).withIndex()) {
                    val budget = checkNotNull(layer.budget)
                    val queue = season * LAYERS + layerIndex
                    val doubles = listOf(budget.added, budget.rain, budget.landRain, budget.condensed, budget.reevaporated, budget.imbalance)
                    bind(LEDGER_BINDING)
                    for ((field, into) in doubles.withIndex()) {
                        GL15C.glGetBufferSubData(
                            GL43C.GL_SHADER_STORAGE_BUFFER, ((queue.toLong() * LEDGER_FIELDS + field) * cells) * Double.SIZE_BYTES, into
                        )
                    }
                    bind(LEDGER_WATER_BINDING)
                    GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, queue.toLong() * cells * Float.SIZE_BYTES, budget.waterMm)
                    bind(LEDGER_FLAGS_BINDING)
                    GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, queue.toLong() * cells * Int.SIZE_BYTES, budget.flags)
                }
            }
        }
    }

    /**
     * Where each season's small arrays stand in the small buffer, as the shader's layout table
     * reads them: the turns' condensate banks, the sent parcels, the rows' parcels; then the
     * per-row constants and the faces' working space.
     */
    private class SmallLayout(run: MoistureDeviceRun) {
        val rows = run.cellsDown
        val turnBase = IntArray(SEASONS)
        val boundarySentBase = IntArray(SEASONS)
        val freeSentBase = IntArray(SEASONS)
        val boundaryParcelBase = IntArray(SEASONS)
        val freeParcelBase = IntArray(SEASONS)
        val rowConstants: Int
        val scratch: Int
        val total: Int
        val table: IntArray

        init {
            var cursor = 0
            for ((season, fields) in run.seasons.withIndex()) {
                turnBase[season] = cursor; cursor += TURN_FIELDS * fields.turnCells
                boundarySentBase[season] = cursor; cursor += BOUNDARY_SENT_FIELDS * fields.boundary.sentSlots
                freeSentBase[season] = cursor; cursor += FREE_SENT_FIELDS * fields.free.sentSlots
                boundaryParcelBase[season] = cursor; cursor += SWEEPS * BOUNDARY_PARCEL_FIELDS * rows
                freeParcelBase[season] = cursor; cursor += SWEEPS * FREE_PARCEL_FIELDS * rows
            }
            rowConstants = cursor; cursor += ROW_CONSTANT_FIELDS * rows
            scratch = cursor; cursor += QUEUES * SCRATCH_FIELDS * rows
            total = cursor
            table = IntArray(SEASONS * LAYOUT_PER_SEASON + 2)
            for (season in 0 until SEASONS) {
                table[season * LAYOUT_PER_SEASON] = turnBase[season]
                table[season * LAYOUT_PER_SEASON + 1] = boundarySentBase[season]
                table[season * LAYOUT_PER_SEASON + 2] = freeSentBase[season]
                table[season * LAYOUT_PER_SEASON + 3] = boundaryParcelBase[season]
                table[season * LAYOUT_PER_SEASON + 4] = freeParcelBase[season]
            }
            table[SEASONS * LAYOUT_PER_SEASON] = rowConstants
            table[SEASONS * LAYOUT_PER_SEASON + 1] = scratch
        }

        private fun turnArrays(fields: MoistureDeviceSeason) =
            listOf(fields.bankCloud, fields.bankCloudLand, fields.bankFalling, fields.bankFallingLand)

        private fun boundaryParcels(fields: MoistureDeviceSeason) = listOf(fields.boundary.eastParcel, fields.boundary.westParcel).flatMap {
            listOf(it.vapor, it.vaporLand, it.cloud, it.cloudLand, it.falling, it.fallingLand, it.air)
        }

        private fun freeParcels(fields: MoistureDeviceSeason) = listOf(fields.free.eastParcel, fields.free.westParcel).flatMap {
            listOf(it.vapor, it.vaporLand)
        }

        /** Copies [fields]' turn banks and parcels into [small]. */
        fun scatter(season: Int, fields: MoistureDeviceSeason, small: DoubleArray) {
            for ((index, array) in turnArrays(fields).withIndex()) array.copyInto(small, turnBase[season] + index * fields.turnCells)
            for ((index, array) in boundaryParcels(fields).withIndex()) array.copyInto(small, boundaryParcelBase[season] + index * rows)
            for ((index, array) in freeParcels(fields).withIndex()) array.copyInto(small, freeParcelBase[season] + index * rows)
        }

        /** Copies [small]'s turn banks and parcels back into [fields]. */
        fun gather(season: Int, small: DoubleArray, fields: MoistureDeviceSeason) {
            for ((index, array) in turnArrays(fields).withIndex()) {
                val start = turnBase[season] + index * fields.turnCells
                small.copyInto(array, 0, start, start + fields.turnCells)
            }
            for ((index, array) in boundaryParcels(fields).withIndex()) {
                val start = boundaryParcelBase[season] + index * rows
                small.copyInto(array, 0, start, start + rows)
            }
            for ((index, array) in freeParcels(fields).withIndex()) {
                val start = freeParcelBase[season] + index * rows
                small.copyInto(array, 0, start, start + rows)
            }
        }
    }

    companion object {
        /** The shader's source, both kernels, read once. */
        private val KERNEL_SOURCE: String by lazy {
            checkNotNull(GpuMoisture::class.java.getResource("moisture_march.comp")) { "the moisture march's shader is missing" }.readText()
        }

        /**
         * The largest storage block this device takes, bytes: probed once. The state buffer is the
         * largest, 302 MB at 1,024 rows.
         */
        @Volatile
        private var maxStorageBlockBytes = 0L

        /**
         * The tallest grid the faces' solve takes, rows: a column's rows in one workgroup, in the
         * mixed form a role and six singles a row in its shared memory, which the GL specification
         * guarantees at 32 KB and this class asks the device for (49,152 bytes on the machine this
         * was built on); a taller grid is the processor's.
         */
        @Volatile
        private var maxSolvedRows = 0

        /**
         * How many rows a workgroup of the faces' kernel has one invocation for: the grid's rows up
         * to the device's largest workgroup, the rest taken in turn by the same invocations.
         */
        @Volatile
        private var maxWorkgroupInvocations = 0

        private fun workgroupRowsFor(rows: Int) = minOf(rows, maxWorkgroupInvocations, MAX_WORKGROUP_ROWS)

        /**
         * Invocations of the faces' kernel a workgroup at most: 1,024, the largest the GL
         * specification guarantees every device that has compute shaders allows.
         */
        private const val MAX_WORKGROUP_ROWS = 1024

        /**
         * Rows a workgroup of the crossings' kernel takes: 64, two warps of the cards this was built
         * on, small enough that a column's rows spread over the card's multiprocessors, where each
         * crossing's long serial arithmetic is what a step waits on.
         */
        private const val CROSSING_GROUP = 64

        /**
         * The single-precision faces' solve's refinements against the double-precision matrix: two.
         * The system is an M-matrix whose condition reaches about four times its largest mixing
         * share, up to some 5e4 where a calm cell's stretch multiplies the eddies' share; a solve in
         * singles leaves `cond * 2^-24`, 3e-3, of the answer, and each refinement multiplies that by
         * itself, so two leave it under 1e-8 before the last residual, added in doubles, closes what
         * the faces moved on the doubles' matrix.
         */
        private const val REFINEMENTS = 2

        /**
         * The crossing's balance tolerance in single precision, as a share of the cap and a
         * millimeter: sixteen units of a single's last place, 2^-20. A root search in singles cannot
         * resolve the balance closer than the rounding of the sinks it evaluates there, a few units
         * of the last place; the processor's own, 1e-12, is a double's.
         */
        private val SINGLE_BALANCE_TOLERANCE = Math.scalb(1.0, -20)

        /** Nanoseconds in a millisecond, for the card's timer. */
        private const val NANOSECONDS_PER_MILLISECOND = 1.0e6

        /** A double as a GLSL double-precision literal. */
        private fun literal(value: Double): String = "${value}LF"

        // The buffers' bindings, as `layout(binding = ...)` in the shader declares them.
        private const val STATE_BINDING = 0
        private const val TERMS_BINDING = 1
        private const val FIELDS_BINDING = 2
        private const val RESULTS_BINDING = 3
        private const val INTS_BINDING = 4
        private const val SMALL_BINDING = 5
        private const val STEPS_BINDING = 6
        private const val ROLES_BINDING = 7
        private const val LAYOUT_BINDING = 8
        private const val LEDGER_BINDING = 9
        private const val LEDGER_WATER_BINDING = 10
        private const val LEDGER_FLAGS_BINDING = 11
        private const val BUFFER_COUNT = 12

        // Each buffer's fields, in the shader's order.
        private const val SEASONS = 2
        private const val LAYERS = 2
        private const val QUEUES = SEASONS * LAYERS
        private const val SWEEPS = 2
        private const val STATE_FIELDS = 9
        private const val BL_BANK = 0
        private const val BL_BANK_LAND = 1
        private const val BL_BANK_AIR = 2
        private const val FT_BANK = 3
        private const val FT_BANK_LAND = 4
        private const val TO_FREE = 5
        private const val TO_FREE_LAND = 6
        private const val TO_BOUNDARY = 7
        private const val TO_BOUNDARY_LAND = 8
        private const val TERM_FIELDS = 5
        private const val EXCHANGE = 0
        private const val LAW_SATURATED = 1
        private const val LAW_OFFSET = 2
        private const val FREE_SATURATED = 3
        private const val BASE_SHARE = 4
        private const val FIELD_COUNT = 15
        private const val CAP = 0
        private const val GROUND_PRESSURE = 1
        private const val LAYER_DEPTH = 2
        private const val AIR_C_ROW_MAJOR = 3
        private const val SEA_HUMIDITY = 4
        private const val SUPPRESSION = 5
        private const val BL_STRETCH = 6
        private const val FT_STRETCH = 7
        private const val BL_FACE = 8
        private const val FT_FACE = 9
        private const val SEA_TRANSFER = 10
        private const val LIFT = 11
        private const val DESCENT = 12
        private const val GROUND_RETURN = 13
        private const val REPLACED = 14
        private const val RESULT_FIELDS = 7
        private const val RAIN = 0
        private const val LAND_RAIN = 1
        private const val CONDENSATION = 2
        private const val HUMIDITY = 3
        private const val SOURCE = 4
        private const val FREE_RAIN = 5
        private const val FREE_LAND_RAIN = 6
        private const val INT_FIELDS = 4
        private const val KIND = 0
        private const val TURN_SLOT = 1
        private const val BL_SENT_SLOT = 2
        private const val FT_SENT_SLOT = 3
        private const val BOUNDARY_EAST_BIT = 4
        private const val FREE_EAST_BIT = 8
        private const val LEDGER_FIELDS = 6
        private const val LAYOUT_PER_SEASON = 5
        private const val TURN_FIELDS = 4
        private const val BOUNDARY_SENT_FIELDS = 3
        private const val FREE_SENT_FIELDS = 2
        private const val BOUNDARY_PARCEL_FIELDS = 7
        private const val FREE_PARCEL_FIELDS = 2
        private const val ROW_CONSTANT_FIELDS = 2
        private const val SCRATCH_FIELDS = 20

        /** Bytes of the faces' kernel's shared memory a row takes in the mixed form: a role and six singles. */
        private const val SHARED_BYTES_PER_ROW = Int.SIZE_BYTES + 6 * Float.SIZE_BYTES

        /** How long a driver may take over the march's two kernels before it is given up on. */
        private const val COMPILE_TIMEOUT_SECONDS = 60L

        /**
         * Takes the shared offscreen context, reads the device's limits and compiles the kernels on a
         * small grid, or returns null with a reason if this machine cannot offer what they need.
         */
        fun createOrNull(precision: Precision = Precision.MIXED): Result {
            val context = GlContext.ensure()
            val device = context.device ?: return Result(null, context.unavailableBecause ?: "unknown failure")
            val march = GpuMoisture(device, precision)
            var failure: String? = "the moisture march's kernels would not compile"
            GlContext.run("Compiling the moisture march", timeoutSeconds = COMPILE_TIMEOUT_SECONDS) {
                val blocks = GL43C.glGetInteger(GL43C.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)
                if (blocks < BUFFER_COUNT) {
                    failure = "the card offers $blocks storage blocks to a kernel and the march needs $BUFFER_COUNT"
                    return@run
                }
                maxStorageBlockBytes = GL43C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE)
                maxWorkgroupInvocations = GL43C.glGetInteger(GL43C.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)
                maxSolvedRows = GL43C.glGetInteger(GL43C.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE) / SHARED_BYTES_PER_ROW
                // A small grid's kernels, both forms of the ledger, to know the device compiles them
                // before a world asks.
                march.programsFor(PROBE_ROWS, 2 * PROBE_ROWS, false, MoistureDeviceRun.CONSTANTS)
                march.programsFor(PROBE_ROWS, 2 * PROBE_ROWS, true, MoistureDeviceRun.CONSTANTS)
                failure = null
            }
            return if (failure == null) Result(march, null) else Result(null, failure)
        }

        /** The probe's grid, rows: small enough to compile in a moment. */
        private const val PROBE_ROWS = 16
    }
}
