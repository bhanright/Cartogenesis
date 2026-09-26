package com.cartogenesis.web

import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanStencil

/**
 * Solves the coarse stream function on the browser's graphics device.
 *
 * The counterpart to the desktop's OpenGL path, and the same relaxation again: red-black
 * Gauss-Seidel, two compute passes per relaxation pass so that the second colour reads a
 * first colour every work group has finished writing. The desktop writes GLSL and this writes
 * WGSL; what they compute is the CPU reference's own arithmetic.
 *
 * As on the desktop, the arithmetic is not bit-for-bit the CPU's, which is why choosing this path
 * makes a world carry its currents in the save instead of being regenerated from its seed.
 */
class WebGpuOcean private constructor(
    private val device: JsHandle,
    override val name: String
) : OceanAccelerator {
    override suspend fun solve(
        stencil: OceanStencil,
        start: FloatArray,
        passes: Int
    ): FloatArray? {
        val cellsAcross = stencil.cellsAcross
        val cellsDown = stencil.cellsDown
        // Wrapped neighbours of an odd-width grid are not independent within a colour.
        if (cellsAcross <= 0 || cellsAcross % 2 != 0 || cellsDown <= 0 || passes < 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != stencil.isWater.size.toLong() || cellCount != start.size.toLong()) return null
        val water = allocateWaterWords(cellCount.toInt())
        val forcing = allocateFloats(cellCount.toInt())
        val stream = allocateFloats(cellCount.toInt())
        for (cell in 0 until cellCount.toInt()) {
            setWaterWord(water, cell, if (stencil.isWater[cell]) 1 else 0)
            setFloat(forcing, cell, stencil.forcing[cell])
            setFloat(stream, cell, start[cell])
        }
        // East, west, north and south: one vec4 of weights per cell.
        val weights = allocateFloats(cellCount.toInt() * WEIGHTS_PER_CELL)
        for (cell in 0 until cellCount.toInt()) {
            setFloat(weights, cell * WEIGHTS_PER_CELL, stencil.eastWeight[cell])
            setFloat(weights, cell * WEIGHTS_PER_CELL + 1, stencil.westWeight[cell])
            setFloat(weights, cell * WEIGHTS_PER_CELL + 2, stencil.northWeight[cell])
            setFloat(weights, cell * WEIGHTS_PER_CELL + 3, stencil.southWeight[cell])
        }
        val result = awaitPromise(
            runOcean(device, OCEAN_RELAXATION_WGSL, cellsAcross, cellsDown, water, forcing, stream, weights, passes)
        )
        if (result == null || isNullish(result)) return null
        return FloatArray(cellCount.toInt()) { getFloat(result, it) }
    }

    companion object {
        /** East, west, north and south: the four weights each cell carries. */
        private const val WEIGHTS_PER_CELL = 4

        /**
         * The ocean solver on the device erosion is already using.
         *
         * A browser hands out one device per request and a second request can be refused outright,
         * so the two accelerators share one rather than each asking. It carries the same name,
         * because it is the same card.
         */
        fun sharingDeviceWith(erosion: WebGpuErosion): WebGpuOcean =
            WebGpuOcean(erosion.device, erosion.name)
    }
}

@JsFun("(size) => new Uint32Array(size)")
private external fun allocateWaterWords(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setWaterWord(array: JsHandle, index: Int, value: Int)

/** Each compute-pass boundary makes the previous colour visible across all work groups. */
@JsFun(
    """(device, source, width, height, waterData, forcingData, startData, weightData, passes) => (async () => {
        if (device.__lost) return null;
        // Both storage types are 32-bit words; 16 squared is the baseline 256 invocations.
        const bytesPerCell = 4;
        const workGroupSide = 16;
        const bytes = width * height * bytesPerCell;
        // The weights are four words a cell, the largest buffer bound.
        if (bytes * 4 > device.limits.maxStorageBufferBindingSize || bytes * 4 > device.limits.maxBufferSize) {
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
            // Pipelines belong to the device and are independent of the grid and forcing.
            if (!device.__oceanPipelines) {
                const module = device.createShaderModule({code: source});
                const info = await module.getCompilationInfo();
                if (info.messages.some(message => message.type === 'error')) return null;
                const layout = device.createBindGroupLayout({entries: [
                    {binding: 0, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'read-only-storage'}},
                    {binding: 1, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'read-only-storage'}},
                    {binding: 2, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'storage'}},
                    {binding: 3, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'uniform'}},
                    {binding: 4, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'read-only-storage'}}
                ]});
                const pipelineLayout = device.createPipelineLayout({bindGroupLayouts: [layout]});
                const red = await device.createComputePipelineAsync({
                    layout: pipelineLayout, compute: {module, entryPoint: 'red'}
                });
                const black = await device.createComputePipelineAsync({
                    layout: pipelineLayout, compute: {module, entryPoint: 'black'}
                });
                device.__oceanPipelines = {layout, red, black};
            }
            const pipelines = device.__oceanPipelines;
            const storageUsage = GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST;
            const water = buffer(bytes, storageUsage);
            const forcing = buffer(bytes, storageUsage);
            const stream = buffer(bytes, storageUsage | GPUBufferUsage.COPY_SRC);
            const weights = buffer(bytes * 4, storageUsage);
            const readback = buffer(bytes, GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ);
            // Four 32-bit words keep the uniform binding at 16 bytes.
            const params = buffer(16, GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST);
            const paramData = new ArrayBuffer(16);
            new Uint32Array(paramData, 0, 2).set([width, height]);
            device.queue.writeBuffer(params, 0, paramData);
            device.queue.writeBuffer(water, 0, waterData);
            device.queue.writeBuffer(forcing, 0, forcingData);
            device.queue.writeBuffer(stream, 0, startData);
            device.queue.writeBuffer(weights, 0, weightData);
            const group = device.createBindGroup({layout: pipelines.layout, entries: [
                {binding: 0, resource: {buffer: water}},
                {binding: 1, resource: {buffer: forcing}},
                {binding: 2, resource: {buffer: stream}},
                {binding: 3, resource: {buffer: params}},
                {binding: 4, resource: {buffer: weights}}
            ]});
            // Submitted in batches rather than as one command buffer of six thousand compute
            // passes: submissions on a queue run in order, so the arithmetic is unchanged, and a
            // command buffer that long is the kind a browser refuses or a watchdog kills.
            const passesPerSubmit = 256;
            const groupsAcross = Math.ceil(width / workGroupSide);
            const groupsDown = Math.ceil(height / workGroupSide);
            for (let done = 0; done < passes; done += passesPerSubmit) {
                const encoder = device.createCommandEncoder();
                const batch = Math.min(passesPerSubmit, passes - done);
                for (let iteration = 0; iteration < batch; iteration++) {
                    for (const pipeline of [pipelines.red, pipelines.black]) {
                        const pass = encoder.beginComputePass();
                        pass.setPipeline(pipeline);
                        pass.setBindGroup(0, group);
                        pass.dispatchWorkgroups(groupsAcross, groupsDown);
                        pass.end();
                    }
                }
                device.queue.submit([encoder.finish()]);
            }
            const readbackEncoder = device.createCommandEncoder();
            readbackEncoder.copyBufferToBuffer(stream, 0, readback, 0, bytes);
            device.queue.submit([readbackEncoder.finish()]);
            await readback.mapAsync(GPUMapMode.READ);
            const result = new Float32Array(readback.getMappedRange().slice(0));
            readback.unmap();
            const memoryError = await device.popErrorScope();
            const validationError = await device.popErrorScope();
            scopesOpen = false;
            return device.__lost || memoryError || validationError ? null : result;
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
private external fun runOcean(
    device: JsHandle,
    source: String,
    width: Int,
    height: Int,
    water: JsHandle,
    forcing: JsHandle,
    start: JsHandle,
    weights: JsHandle,
    passes: Int
): JsHandle

/**
 * One relaxation pass over one color of the ocean's stencil, the processor's
 * `OceanCirculation.relax`: every water cell of the color set to `e x_east + w x_west + n x_north +
 * s x_south - f` from its own four weights, land held at zero, columns wrapping.
 *
 * A Kotlin constant handed to the page's script rather than text inside it, so the shader can be
 * read, checked for WGSL's reserved words and compiled by a test without a browser.
 */
internal const val OCEAN_RELAXATION_WGSL = """
struct Params { width: u32, height: u32, padding0: u32, padding1: u32 };
@group(0) @binding(0) var<storage, read> water: array<u32>;
@group(0) @binding(1) var<storage, read> forcing: array<f32>;
@group(0) @binding(2) var<storage, read_write> stream: array<f32>;
@group(0) @binding(3) var<uniform> params: Params;
@group(0) @binding(4) var<storage, read> weights: array<vec4<f32>>;

fn relax(gid: vec3<u32>, colour: u32) {
    let x = gid.x;
    let y = gid.y;
    if (x >= params.width || y >= params.height || ((x + y) & 1u) != colour) {
        return;
    }
    let cell = y * params.width + x;
    if (water[cell] == 0u) { stream[cell] = 0.0; return; }
    let east = (x + 1u) % params.width;
    let west = (x + params.width - 1u) % params.width;
    // An edge row's weight toward its pole is zero, so what lies beyond is never read.
    var streamNorth = 0.0;
    if (y > 0u) { streamNorth = stream[cell - params.width]; }
    var streamSouth = 0.0;
    if (y + 1u < params.height) { streamSouth = stream[cell + params.width]; }
    let weight = weights[cell];
    // Grouped left to right, as the reference sums them. WGSL has no way to
    // forbid reassociation, so this is as close as a browser can be held to the
    // processor's own order.
    let relaxed = (((weight.x * stream[y * params.width + east]
        + weight.y * stream[y * params.width + west])
        + weight.z * streamNorth) + weight.w * streamSouth) - forcing[cell];
    stream[cell] = relaxed;
}
@compute @workgroup_size(16, 16)
fn red(@builtin(global_invocation_id) gid: vec3<u32>) { relax(gid, 0u); }
@compute @workgroup_size(16, 16)
fn black(@builtin(global_invocation_id) gid: vec3<u32>) { relax(gid, 1u); }
"""
