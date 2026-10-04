pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS rather than FAIL_ON_PROJECT_REPOS because the browser build's Kotlin/Wasm
    // plugin registered its own Node.js repository, which the strict mode rejects. G1 removed that
    // build and left the mode as it was.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Cartogenesis"
include(":worldgen")
include(":cartography")
include(":ui")
include(":desktop")
