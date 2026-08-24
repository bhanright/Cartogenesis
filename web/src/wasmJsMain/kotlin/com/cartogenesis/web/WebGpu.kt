package com.cartogenesis.web

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The WebGPU calls the erosion path needs.
 *
 * Every one of them is asynchronous, which is the whole reason the accelerator seam suspends:
 * asking for a device, and reading a buffer back, both return promises, and Kotlin/Wasm cannot
 * block on one. [awaitPromise] is the bridge — a promise on one side, a suspension on the other.
 *
 * The work itself is expressed in one JavaScript function rather than a dozen small ones. That is
 * deliberate: each crossing of the wasm/JS boundary has a cost and, more importantly, a chance to
 * get a type wrong, and the whole compute pass is one logical operation.
 */

/** An opaque handle to a JavaScript object, only ever handed straight back to JavaScript. */
internal external interface JsHandle : JsAny

@JsFun("() => typeof navigator !== 'undefined' && !!navigator.gpu")
internal external fun webGpuPresent(): Boolean

@JsFun(
    """() => (async () => {
        try {
            const adapter = await navigator.gpu.requestAdapter();
            if (!adapter) return null;
            const device = await adapter.requestDevice();
            if (!device) return null;
            // Losing the device mid-run would otherwise surface as an unrelated failure later.
            device.lost.then(() => { device.__lost = true; });
            const info = adapter.info || {};
            device.__label = info.description || info.vendor || 'WebGPU device';
            return device;
        } catch (e) {
            return null;
        }
    })()"""
)
internal external fun requestDevice(): JsHandle

@JsFun("(device) => device.__label")
internal external fun deviceLabel(device: JsHandle): String

/**
 * Runs the whole erosion pass and returns the heights.
 *
 * The two shaders mirror the CPU exactly: one works out how much each cell hands over per unit of
 * excess above the critical slope, the other moves it. Two dispatches per sweep, ping-ponging
 * between a pair of storage buffers, and a single readback at the end — the copy back is the
 * expensive part, so it happens once rather than per sweep.
 */
@JsFun(
    """(device, width, height, heightsBuffer, talus, passes, rate) => (async () => {
        if (device.__lost) return null;

        const cells = width * height;
        const orthogonal = talus / width;
        const diagonal = orthogonal * Math.SQRT2;
        const settled = orthogonal * 1e-3;

        const shared = `
            // Six values, and two words of padding to reach 32 bytes. A struct in the uniform
            // address space has to be a multiple of 16 bytes; at 24 the binding reads as zeros
            // rather than failing, which makes width zero, sends every invocation down the
            // out-of-bounds early return, and leaves the output untouched.
            struct Params {
                width: u32,
                height: u32,
                orthogonal: f32,
                diagonal: f32,
                rate: f32,
                settled: f32,
                pad0: f32,
                pad1: f32,
            };
            @group(0) @binding(0) var<storage, read> source: array<f32>;
            @group(0) @binding(1) var<storage, read_write> destination: array<f32>;
            @group(0) @binding(2) var<storage, read_write> rates: array<f32>;
            @group(0) @binding(3) var<uniform> params: Params;

            const OFFSETS = array<vec2<i32>, 8>(
                vec2<i32>( 1, 0), vec2<i32>(-1, 0), vec2<i32>(0,  1), vec2<i32>(0, -1),
                vec2<i32>( 1, 1), vec2<i32>( 1,-1), vec2<i32>(-1, 1), vec2<i32>(-1,-1)
            );

            // The world is a cylinder: x wraps, y does not.
            fn indexOf(x: i32, y: i32) -> u32 {
                let w = i32(params.width);
                return u32(y) * params.width + u32((x + w) % w);
            }
        `;

        const phaseA = shared + `
            @compute @workgroup_size(16, 16)
            fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
                let x = i32(gid.x);
                let y = i32(gid.y);
                if (x >= i32(params.width) || y >= i32(params.height)) { return; }

                let i = u32(y) * params.width + u32(x);
                let here = source[i];
                var excess = 0.0;
                var steepest = 0.0;

                for (var n = 0; n < 8; n = n + 1) {
                    let ny = y + OFFSETS[n].y;
                    if (ny < 0 || ny >= i32(params.height)) { continue; }
                    let drop = here - source[indexOf(x + OFFSETS[n].x, ny)];
                    if (drop <= 0.0) { continue; }
                    steepest = max(steepest, drop);
                    var limit = params.diagonal;
                    if (n < 4) { limit = params.orthogonal; }
                    if (drop > limit) { excess = excess + (drop - limit); }
                }

                if (excess <= params.settled) {
                    rates[i] = 0.0;
                } else {
                    rates[i] = min(params.rate * excess, steepest * 0.5) / excess;
                }
            }
        `;

        const phaseB = shared + `
            @compute @workgroup_size(16, 16)
            fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
                let x = i32(gid.x);
                let y = i32(gid.y);
                if (x >= i32(params.width) || y >= i32(params.height)) { return; }

                let i = u32(y) * params.width + u32(x);
                let here = source[i];
                var received = 0.0;
                var given = 0.0;

                for (var n = 0; n < 8; n = n + 1) {
                    let ny = y + OFFSETS[n].y;
                    if (ny < 0 || ny >= i32(params.height)) { continue; }
                    let j = indexOf(x + OFFSETS[n].x, ny);
                    var limit = params.diagonal;
                    if (n < 4) { limit = params.orthogonal; }

                    let incoming = source[j] - here;
                    if (incoming > limit) {
                        received = received + rates[j] * (incoming - limit);
                    } else if (-incoming > limit) {
                        given = given + rates[i] * (-incoming - limit);
                    }
                }

                destination[i] = here - given + received;
            }
        `;

        const bytes = cells * 4;
        const usage = GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC | GPUBufferUsage.COPY_DST;
        const bufferA = device.createBuffer({ size: bytes, usage: usage });
        const bufferB = device.createBuffer({ size: bytes, usage: usage });
        const rates = device.createBuffer({ size: bytes, usage: usage });
        const readback = device.createBuffer({
            size: bytes,
            usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ
        });

        const params = device.createBuffer({
            size: 32,
            usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST
        });
        const paramData = new ArrayBuffer(32);
        new Uint32Array(paramData, 0, 2).set([width, height]);
        new Float32Array(paramData, 8, 4).set([orthogonal, diagonal, rate, settled]);
        device.queue.writeBuffer(params, 0, paramData);
        device.queue.writeBuffer(bufferA, 0, heightsBuffer);

        const layout = device.createBindGroupLayout({
            entries: [
                { binding: 0, visibility: GPUShaderStage.COMPUTE, buffer: { type: 'read-only-storage' } },
                { binding: 1, visibility: GPUShaderStage.COMPUTE, buffer: { type: 'storage' } },
                { binding: 2, visibility: GPUShaderStage.COMPUTE, buffer: { type: 'storage' } },
                { binding: 3, visibility: GPUShaderStage.COMPUTE, buffer: { type: 'uniform' } }
            ]
        });
        const pipelineLayout = device.createPipelineLayout({ bindGroupLayouts: [layout] });

        // Compiling a bad shader does not throw: it yields an invalid module, whose pipeline is
        // invalid, whose dispatches quietly do nothing -- and the output buffer then reads back
        // as zeros, which is a blank world reported as a successful one. So compilation is
        // checked, and a failure declines the job and lets the CPU have it.
        device.pushErrorScope('validation');
        const build = (code) => device.createComputePipeline({
            layout: pipelineLayout,
            compute: { module: device.createShaderModule({ code: code }), entryPoint: 'main' }
        });
        const pipelineA = build(phaseA);
        const pipelineB = build(phaseB);
        const compileError = await device.popErrorScope();
        if (compileError) {
            console.error('Cartogenesis: WGSL would not compile -', compileError.message);
            return null;
        }

        const bindGroup = (from, to) => device.createBindGroup({
            layout: layout,
            entries: [
                { binding: 0, resource: { buffer: from } },
                { binding: 1, resource: { buffer: to } },
                { binding: 2, resource: { buffer: rates } },
                { binding: 3, resource: { buffer: params } }
            ]
        });
        // Phase A writes only the rates, but the layout wants a target bound, so it is given the
        // one it is about to write in phase B. It never touches it.
        const forward = bindGroup(bufferA, bufferB);
        const backward = bindGroup(bufferB, bufferA);

        const groupsX = Math.ceil(width / 16);
        const groupsY = Math.ceil(height / 16);

        const encoder = device.createCommandEncoder();
        for (let pass = 0; pass < passes; pass++) {
            const group = (pass % 2 === 0) ? forward : backward;
            const a = encoder.beginComputePass();
            a.setPipeline(pipelineA);
            a.setBindGroup(0, group);
            a.dispatchWorkgroups(groupsX, groupsY);
            a.end();

            const b = encoder.beginComputePass();
            b.setPipeline(pipelineB);
            b.setBindGroup(0, group);
            b.dispatchWorkgroups(groupsX, groupsY);
            b.end();
        }

        // After an odd number of sweeps the answer is in B, after an even number back in A.
        const result = (passes % 2 === 1) ? bufferB : bufferA;
        encoder.copyBufferToBuffer(result, 0, readback, 0, bytes);
        device.queue.submit([encoder.finish()]);

        await readback.mapAsync(GPUMapMode.READ);
        const copy = new Float32Array(readback.getMappedRange().slice(0));
        readback.unmap();

        bufferA.destroy();
        bufferB.destroy();
        rates.destroy();
        readback.destroy();
        params.destroy();
        return copy;
    })()"""
)
internal external fun runErosion(
    device: JsHandle,
    width: Int,
    height: Int,
    heights: JsHandle,
    talus: Float,
    passes: Int,
    rate: Float
): JsHandle

@JsFun("(size) => new Float32Array(size)")
internal external fun allocateFloats(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
internal external fun setFloat(array: JsHandle, index: Int, value: Float)

@JsFun("(array, index) => array[index]")
internal external fun getFloat(array: JsHandle, index: Int): Float

@JsFun("(value) => value === null || value === undefined")
internal external fun isNullish(value: JsHandle?): Boolean

@JsFun(
    """(promise, resolve, reject) => {
        promise.then((value) => resolve(value), (error) => reject(String(error)));
    }"""
)
private external fun thenPromise(
    promise: JsHandle,
    resolve: (JsHandle?) -> Unit,
    reject: (String) -> Unit
)

/** Turns a JavaScript promise into a suspension. */
internal suspend fun awaitPromise(promise: JsHandle): JsHandle? =
    suspendCoroutine { continuation ->
        thenPromise(
            promise,
            resolve = { continuation.resume(it) },
            reject = { continuation.resume(null) }
        )
    }
