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
    // PREFER_SETTINGS rather than FAIL_ON_PROJECT_REPOS: the Kotlin JS/Wasm plugin insists on
    // registering its own Node.js download repository, which the strict mode rejects outright.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kotlin/JS and Kotlin/Wasm download their own Node.js toolchain. Declaring it here rather
        // than letting the Kotlin plugin add it keeps FAIL_ON_PROJECT_REPOS satisfied.
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        // Kotlin/Wasm optimises its output with Binaryen, which the plugin fetches from GitHub
        // releases. Declared here for the same reason as Node and Yarn above.
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout {
                artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "Cartogenesis"
include(":worldgen")
include(":cartography")
include(":ui")
include(":desktop")
include(":web")
