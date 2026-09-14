package com.cartogenesis.web

import com.cartogenesis.worldgen.pipeline.OceanAccelerator

/**
 * Solves the coarse stream function on the browser's graphics device.
 *
 * The counterpart to the desktop's OpenGL path, and the same solve again: red-black Gauss-Seidel
 * with over-relaxation, two compute passes per relaxation pass so that the second colour reads a
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
        cellsAcross: Int,
        cellsDown: Int,
        isWater: BooleanArray,
        forcing: FloatArray,
        passes: Int,
        overRelaxation: Float
    ): FloatArray? {
        // Wrapped neighbours of an odd-width grid are not independent within a colour.
        if (cellsAcross <= 0 || cellsAcross % 2 != 0 || cellsDown <= 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != isWater.size.toLong() || cellCount != forcing.size.toLong()) return null
        val water = allocateWaterWords(isWater.size)
        val curl = allocateFloats(forcing.size)
        for (cell in forcing.indices) {
            setWaterWord(water, cell, if (isWater[cell]) 1 else 0)
            setFloat(curl, cell, forcing[cell])
        }
        val result = awaitPromise(
            runOcean(device, cellsAcross, cellsDown, water, curl, passes, overRelaxation)
        )
        if (result == null || isNullish(result)) return null
        return FloatArray(forcing.size) { getFloat(result, it) }
    }

    companion object {
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
    """(device, width, height, waterData, forcingData, passes, overRelaxation) => (async () => {
        if (device.__lost) return null;
        // Both storage types are 32-bit words; 16 squared is the baseline 256 invocations.
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
                struct Params { width: u32, height: u32, overRelaxation: f32, padding: u32 };
                @group(0) @binding(0) var<storage, read> water: array<u32>;
                @group(0) @binding(1) var<storage, read> forcing: array<f32>;
                @group(0) @binding(2) var<storage, read_write> stream: array<f32>;
                @group(0) @binding(3) var<uniform> params: Params;

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
                    let north = u32(max(i32(y) - 1, 0));
                    let south = min(y + 1u, params.height - 1u);
                    // Grouped left to right, as the reference sums them: WGSL has no `precise`,
                    // so this is as close as the browser can be held to the CPU's own order.
                    let neighbourSum = ((stream[y * params.width + east]
                        + stream[y * params.width + west])
                        + stream[north * params.width + x]) + stream[south * params.width + x];
                    let relaxed = (neighbourSum - forcing[cell]) * 0.25;
                    let here = stream[cell];
                    stream[cell] = here + (relaxed - here) * params.overRelaxation;
                }
                @compute @workgroup_size(16, 16)
                fn red(@builtin(global_invocation_id) gid: vec3<u32>) { relax(gid, 0u); }
                @compute @workgroup_size(16, 16)
                fn black(@builtin(global_invocation_id) gid: vec3<u32>) { relax(gid, 1u); }
            `;
            // Pipelines belong to the device and are independent of the grid and forcing.
            if (!device.__oceanPipelines) {
                const module = device.createShaderModule({code: source});
                const info = await module.getCompilationInfo();
                if (info.messages.some(message => message.type === 'error')) return null;
                const layout = device.createBindGroupLayout({entries: [
                    {binding: 0, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'read-only-storage'}},
                    {binding: 1, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'read-only-storage'}},
                    {binding: 2, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'storage'}},
                    {binding: 3, visibility: GPUShaderStage.COMPUTE, buffer: {type: 'uniform'}}
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
            // WebGPU zero-initialises new buffers, including the stream's starting iterate.
            const stream = buffer(bytes, storageUsage | GPUBufferUsage.COPY_SRC);
            const readback = buffer(bytes, GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ);
            // Four 32-bit words keep the uniform binding at 16 bytes.
            const params = buffer(16, GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST);
            const paramData = new ArrayBuffer(16);
            new Uint32Array(paramData, 0, 2).set([width, height]);
            new Float32Array(paramData, 8, 1)[0] = overRelaxation;
            device.queue.writeBuffer(params, 0, paramData);
            device.queue.writeBuffer(water, 0, waterData);
            device.queue.writeBuffer(forcing, 0, forcingData);
            const group = device.createBindGroup({layout: pipelines.layout, entries: [
                {binding: 0, resource: {buffer: water}},
                {binding: 1, resource: {buffer: forcing}},
                {binding: 2, resource: {buffer: stream}},
                {binding: 3, resource: {buffer: params}}
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
    width: Int,
    height: Int,
    water: JsHandle,
    forcing: JsHandle,
    passes: Int,
    overRelaxation: Float
): JsHandle
