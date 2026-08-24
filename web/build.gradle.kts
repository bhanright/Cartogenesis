plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The browser front end.
 *
 * Almost nothing lives here. The application is `:ui`, the renderer and save format are shared
 * with the desktop build, and what this module supplies is a page to draw into and the browser's
 * answers to the three questions in `Platform`: where worlds are kept, what export means, and
 * whether there is a GPU.
 */
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "cartogenesis.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":ui"))
            implementation(compose.runtime)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
