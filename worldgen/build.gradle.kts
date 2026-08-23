import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
