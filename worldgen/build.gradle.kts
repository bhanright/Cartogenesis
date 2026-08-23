import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The generation engine is plain Kotlin with no platform dependencies, so it is built for the JVM
 * (which the Android app consumes) and for the browser via Wasm and JS.
 *
 * `commonTest` holds the correctness suite and runs on every target â€” which is what proves the
 * engine really is portable, rather than merely compiling. `jvmTest` holds `DebugMapDump`, which
 * renders PNGs through `java.awt` and so cannot be shared.
 */
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Kept for reference, but NOT save-compatible: Kotlin/JS routes sin/cos/pow through
    // JavaScript's Math, whose results differ from the JVM in the last bit. Those feed the FFT,
    // the difference compounds, and the same seed yields a measurably different world — enough to
    // fail the resolution-consistency test. Kotlin/Wasm matches the JVM exactly. Use wasmJs.
    js(IR) {
        nodejs()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // Config classes carry @Serializable so the save format is derived from them directly.
            // Mirroring them into hand-written DTOs would mean every new setting had to be added
            // in two places, and would silently drop from saves when someone forgot.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Erosion and the FFT both hold several grid-sized float buffers at once, and the profiling and
// audit tests run the pipeline at export resolutions. The default test heap cannot take 2048.
tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
}
