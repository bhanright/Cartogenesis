import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Turning a generated world into a picture, without depending on any graphics toolkit.
 *
 * The per-pixel work — hypsometric tints, biome wash, relief shading, coastlines, borders — is
 * plain integer maths over an IntArray, so it is identical on every platform and belongs here.
 * The vector overlays are handled by describing them as geometry rather than drawing them, which
 * leaves each platform with only the drawing calls to implement and keeps the decisions shared.
 */
kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(project(":worldgen"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
