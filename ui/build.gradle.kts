import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The interface, once, for every front end.
 *
 * Everything here is Compose Multiplatform and knows nothing about where it is running. The parts
 * that genuinely differ between a desktop window and a browser tab — where saved worlds live, what
 * "export" means, whether there is a GPU to offer — arrive as [com.cartogenesis.ui.Platform],
 * which each front end supplies. What is left is the actual application, and it is shared rather
 * than reimplemented.
 */
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":cartography"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            // The two type faces the theme is set in travel with the module rather than being
            // asked of the host, which is the only way the browser build renders in the same
            // faces as the desktop one instead of in whatever the page happens to have.
            implementation(compose.components.resources)
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

/*
 * Where the generated accessors for `src/commonMain/composeResources` land. Named explicitly
 * rather than left to the plugin's default, which derives a package from the module coordinates
 * and would change under the code if the module were ever renamed. Internal, because the fonts are
 * an implementation detail of the theme.
 */
compose.resources {
    packageOfResClass = "com.cartogenesis.ui.generated.resources"
    publicResClass = false
}
