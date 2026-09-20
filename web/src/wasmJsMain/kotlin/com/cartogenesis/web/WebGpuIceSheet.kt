package com.cartogenesis.web

import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator

/**
 * Draws the ice sheet's profile and the flow down its surface on the browser's graphics device.
 *
 * The counterpart to the desktop's OpenGL path and the same two passes again: one dispatch writes
 * each cell's thickness from Nye's and Vialov's plastic profile, and a second reads the surface
 * those thicknesses make and picks each cell's steepest descent. The desktop writes GLSL and this
 * writes WGSL; what they compute is `IceSheet`'s own arithmetic.
 *
 * The two halves are separate compute passes rather than one, for the reason `GpuIceSheet` gives:
 * a cell's flow reads its neighbours' thicknesses and a neighbour is usually in another work
 * group, so the whole grid's thickness has to be written and made visible before any of it is
 * read. A pass boundary is what makes it visible.
 *
 * As everywhere else on this seam, the arithmetic is not bit-for-bit the processor's — a device is
 * free to round a square root its own way — which is why a world generated on it carries its
 * fields in the save rather than being regenerated from its seed.
 */
class WebGpuIceSheet private constructor(
    private val device: JsHandle,
    override val name: String
) : IceSheetAccelerator {

    override suspend fun sheet(
        cellsAcross: Int,
        cellsDown: Int,
        marginDistanceKm: FloatArray,
        nearestMarginCell: IntArray,
        bedRelative: FloatArray,
        onTheSheet: BooleanArray,
        metresPerRootKilometre: Float,
        metresPerFieldUnit: Float,
        cellHeightInCellWidths: Float
    ): IceSheetAccelerator.Sheet? {
        if (cellsAcross <= 0 || cellsDown <= 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != marginDistanceKm.size.toLong() ||
            cellCount != nearestMarginCell.size.toLong() ||
            cellCount != bedRelative.size.toLong() ||
            cellCount != onTheSheet.size.toLong()
        ) return null

        val margin = allocateFloats(marginDistanceKm.size)
        val nearest = allocateSignedWords(nearestMarginCell.size)
        val bed = allocateFloats(bedRelative.size)
        // A boolean has no storage width a device agrees on, so the mask crosses as words.
        val sheetWords = allocateUnsignedWords(onTheSheet.size)
        for (cell in marginDistanceKm.indices) {
            setFloat(margin, cell, marginDistanceKm[cell])
            setSignedWord(nearest, cell, nearestMarginCell[cell])
            setFloat(bed, cell, bedRelative[cell])
            setUnsignedWord(sheetWords, cell, if (onTheSheet[cell]) 1 else 0)
        }

        val result = awaitPromise(
            runIceSheet(
                device, cellsAcross, cellsDown, margin, nearest, bed, sheetWords,
                metresPerRootKilometre, metresPerFieldUnit, cellHeightInCellWidths
            )
        )
        if (result == null || isNullish(result)) return null

        val thickness = thicknessOf(result)
        val receiver = receiverOf(result)
        return IceSheetAccelerator.Sheet(
            FloatArray(marginDistanceKm.size) { getFloat(thickness, it) },
            IntArray(marginDistanceKm.size) { getSignedWord(receiver, it) }
        )
    }

    companion object {
        /**
         * The ice sheet on the device erosion is already using.
         *
         * For [WebGpuOcean.sharingDeviceWith]'s reason: a browser hands out one device per request
         * and a second request can be refused outright, so every accelerator shares the first one
         * and carries its name, because it is the same card.
         */
        fun sharingDeviceWith(erosion: WebGpuErosion): WebGpuIceSheet =
            WebGpuIceSheet(erosion.device, erosion.name)
    }
}

@JsFun("(size) => new Int32Array(size)")
private external fun allocateSignedWords(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setSignedWord(array: JsHandle, index: Int, value: Int)

@JsFun("(array, index) => array[index]")
private external fun getSignedWord(array: JsHandle, index: Int): Int

@JsFun("(size) => new Uint32Array(size)")
private external fun allocateUnsignedWords(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setUnsignedWord(array: JsHandle, index: Int, value: Int)

@JsFun("(result) => result.thickness")
private external fun thicknessOf(result: JsHandle): JsHandle

@JsFun("(result) => result.receiver")
private external fun receiverOf(result: JsHandle): JsHandle

/**
 * Both fields in one round trip: the thickness, then the flow down the surface it makes.
 *
 * One module with two entry points, dispatched as two compute passes, and one readback each. The
 * profile's output is the flow's input, which is the only ordering constraint there is; there is
 * no iteration here and nothing to converge, so unlike the ocean's solve there is nothing a
 * device could compound an error through.
 */
@JsFun(
    """(device, width, height, marginData, nearestData, bedData, sheetData,
         metresPerRootKm, metresPerFieldUnit, rowScale) => (async () => {
        if (device.__lost) return null;
        // Every storage type here is a 32-bit word; 16 squared is the baseline 256 invocations.
        const bytesPerCell = 4;
        const workGroupSide = 16;
        const bytes = width * height * bytesPerCell;
        if (bytes > device.limits.maxStorageBufferBindingSize || bytes > device.limits.maxBufferSize) {
            return null;
        }
        const allocated = [];
        const buffer = (size, usage) => {
            const value = device.createBuffer({size, usage});
            allocated.push(value);
            return value;
        };
        device.pushErrorScope('validation');
        device.pushErrorScope('out-of-memory');
        let scopesOpen = true;
        try {
            const source = `
                // Five values and three words of padding: a struct in the uniform address space
                // has to be a multiple of 16 bytes, and one that is not reads back as zeros
                // rather than failing, which would make the width zero and the output untouched.
                struct Params {
                    width: u32,
                    height: u32,
                    metresPerRootKm: f32,
                    metresPerFieldUnit: f32,
                    rowScale: f32,
                    pad0: f32,
                    pad1: f32,
                    pad2: f32,
                };
                @group(0) @binding(0) var<storage, read> marginKm: array<f32>;
                @group(0) @binding(1) var<storage, read> nearest: array<i32>;
                @group(0) @binding(2) var<storage, read> bed: array<f32>;
                @group(0) @binding(3) var<storage, read> onTheSheet: array<u32>;
                @group(0) @binding(4) var<storage, read_write> thickness: array<f32>;
                @group(0) @binding(5) var<storage, read_write> receiver: array<i32>;
                @group(0) @binding(6) var<uniform> params: Params;

                // IceSheet.surfaceMetres, less the bed under it. The margin's own bed is floored
                // at the waterline: a marine margin is where the ice meets the sea, and the sea
                // is where its surface starts. No backtick may appear anywhere in this shader --
                // the whole source is a JavaScript template literal and one would end it.
                @compute @workgroup_size(16, 16)
                fn profile(@builtin(global_invocation_id) gid: vec3<u32>) {
                    let x = gid.x;
                    let y = gid.y;
                    if (x >= params.width || y >= params.height) { return; }
                    let cell = y * params.width + x;
                    if (onTheSheet[cell] == 0u) { thickness[cell] = 0.0; return; }

                    let distance = marginKm[cell];
                    var rise = 0.0;
                    if (distance > 0.0) { rise = params.metresPerRootKm * sqrt(distance); }
                    let from = nearest[cell];
                    var marginBed = 0.0;
                    if (from >= 0) {
                        marginBed = max(bed[u32(from)] * params.metresPerFieldUnit, 0.0);
                    }
                    let surface = marginBed + rise;
                    thickness[cell] = max(surface - bed[cell] * params.metresPerFieldUnit, 0.0);
                }

                fn surfaceAt(cell: u32) -> f32 {
                    return bed[cell] + thickness[cell] / params.metresPerFieldUnit;
                }

                // IceSheet.steepestDescent, tie-break included: a tie here decides a bearing, and
                // a bearing decides where a trough goes, so it goes to the lower cell index in
                // both places rather than to whichever of the eight was looked at first.
                @compute @workgroup_size(16, 16)
                fn flow(@builtin(global_invocation_id) gid: vec3<u32>) {
                    let x = i32(gid.x);
                    let y = i32(gid.y);
                    if (x >= i32(params.width) || y >= i32(params.height)) { return; }
                    let cell = u32(y) * params.width + u32(x);
                    if (onTheSheet[cell] == 0u) { receiver[cell] = -1; return; }

                    let across = i32(params.width);
                    let here = surfaceAt(cell);
                    var best = -1;
                    var bestGradient = 0.0;
                    for (var rowStep = -1; rowStep <= 1; rowStep = rowStep + 1) {
                        let neighbourRow = y + rowStep;
                        if (neighbourRow < 0 || neighbourRow >= i32(params.height)) { continue; }
                        for (var columnStep = -1; columnStep <= 1; columnStep = columnStep + 1) {
                            if (rowStep == 0 && columnStep == 0) { continue; }
                            let neighbourColumn = (x + columnStep + across) % across;
                            let neighbour = neighbourRow * across + neighbourColumn;
                            let fall = here - surfaceAt(u32(neighbour));
                            if (fall <= 0.0) { continue; }
                            // Per unit of ground walked, not per cell: a step down the map is
                            // rowScale steps across it, and a flow that did not know would drift.
                            let eastward = f32(columnStep);
                            let southward = f32(rowStep) * params.rowScale;
                            let walked = sqrt(eastward * eastward + southward * southward);
                            let gradient = fall / walked;
                            if (gradient > bestGradient ||
                                (gradient == bestGradient && neighbour < best)) {
                                bestGradient = gradient;
                                best = neighbour;
                            }
                        }
                    }
                    receiver[cell] = best;
                }
            `;
            // Pipelines belong to the device and are independent of the grid.
            if (!device.__iceSheetPipelines) {
                const module = device.createShaderModule({code: source});
                const info = await module.getCompilationInfo();
                if (info.messages.some(message => message.type === 'error')) return null;
                const readOnly = {type: 'read-only-storage'};
                const layout = device.createBindGroupLayout({entries: [
                    {binding: 0, visibility: GPUShaderStage.COMPUTE, buffer: readOnly},
                    {binding: 1, visibility: GPUShaderStage.COMPUTE, buffer: readOnly},
                    {binding: 2, visibility: GPUShaderStage.COMPUTE, buffer: readOnly},
                    {binding: 3, visibility: GPUShaderStage.COMPUTE, buffer: readOnly},
                    {binding: 4, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'storage'}},
                    {binding: 5, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'storage'}},
                    {binding: 6, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'uniform'}}
                ]});
                const pipelineLayout = device.createPipelineLayout({bindGroupLayouts: [layout]});
                const profile = await device.createComputePipelineAsync({
                    layout: pipelineLayout, compute: {module, entryPoint: 'profile'}
                });
                const flow = await device.createComputePipelineAsync({
                    layout: pipelineLayout, compute: {module, entryPoint: 'flow'}
                });
                device.__iceSheetPipelines = {layout, profile, flow};
            }
            const pipelines = device.__iceSheetPipelines;
            const storageUsage = GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST;
            const margin = buffer(bytes, storageUsage);
            const nearest = buffer(bytes, storageUsage);
            const bed = buffer(bytes, storageUsage);
            const sheet = buffer(bytes, storageUsage);
            const thickness = buffer(bytes, storageUsage | GPUBufferUsage.COPY_SRC);
            const receiver = buffer(bytes, storageUsage | GPUBufferUsage.COPY_SRC);
            const thicknessBack = buffer(bytes, GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ);
            const receiverBack = buffer(bytes, GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ);
            // Eight 32-bit words keep the uniform binding at 32 bytes.
            const params = buffer(32, GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST);
            const paramData = new ArrayBuffer(32);
            new Uint32Array(paramData, 0, 2).set([width, height]);
            new Float32Array(paramData, 8, 3).set([metresPerRootKm, metresPerFieldUnit, rowScale]);
            device.queue.writeBuffer(params, 0, paramData);
            device.queue.writeBuffer(margin, 0, marginData);
            device.queue.writeBuffer(nearest, 0, nearestData);
            device.queue.writeBuffer(bed, 0, bedData);
            device.queue.writeBuffer(sheet, 0, sheetData);
            const group = device.createBindGroup({layout: pipelines.layout, entries: [
                {binding: 0, resource: {buffer: margin}},
                {binding: 1, resource: {buffer: nearest}},
                {binding: 2, resource: {buffer: bed}},
                {binding: 3, resource: {buffer: sheet}},
                {binding: 4, resource: {buffer: thickness}},
                {binding: 5, resource: {buffer: receiver}},
                {binding: 6, resource: {buffer: params}}
            ]});
            const groupsAcross = Math.ceil(width / workGroupSide);
            const groupsDown = Math.ceil(height / workGroupSide);
            const encoder = device.createCommandEncoder();
            // Two passes, in this order. The second reads thicknesses the first wrote, and a pass
            // boundary is what makes another work group's writes visible.
            for (const pipeline of [pipelines.profile, pipelines.flow]) {
                const pass = encoder.beginComputePass();
                pass.setPipeline(pipeline);
                pass.setBindGroup(0, group);
                pass.dispatchWorkgroups(groupsAcross, groupsDown);
                pass.end();
            }
            encoder.copyBufferToBuffer(thickness, 0, thicknessBack, 0, bytes);
            encoder.copyBufferToBuffer(receiver, 0, receiverBack, 0, bytes);
            device.queue.submit([encoder.finish()]);
            await thicknessBack.mapAsync(GPUMapMode.READ);
            await receiverBack.mapAsync(GPUMapMode.READ);
            const thicknessOut = new Float32Array(thicknessBack.getMappedRange().slice(0));
            const receiverOut = new Int32Array(receiverBack.getMappedRange().slice(0));
            thicknessBack.unmap();
            receiverBack.unmap();
            const memoryError = await device.popErrorScope();
            const validationError = await device.popErrorScope();
            scopesOpen = false;
            if (device.__lost || memoryError || validationError) return null;
            return {thickness: thicknessOut, receiver: receiverOut};
        } catch (error) {
            return null;
        } finally {
            if (scopesOpen) {
                await device.popErrorScope();
                await device.popErrorScope();
            }
            for (const value of allocated) value.destroy();
        }
    })()"""
)
private external fun runIceSheet(
    device: JsHandle,
    width: Int,
    height: Int,
    margin: JsHandle,
    nearest: JsHandle,
    bed: JsHandle,
    sheet: JsHandle,
    metresPerRootKm: Float,
    metresPerFieldUnit: Float,
    rowScale: Float
): JsHandle
