import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Common install locations for a full JDK, newest first. */
fun javaHomeCandidates(): List<String> = listOf(
    File("C:/Program Files/Eclipse Adoptium"),
    File("C:/Program Files/Java"),
    File("C:/Program Files/Microsoft"),
    File("/usr/lib/jvm")
).flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
    .filter { it.isDirectory && it.name.contains("jdk", ignoreCase = true) }
    .sortedByDescending { it.name }
    .map { it.absolutePath }

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Kotlin and Java must agree, or the build refuses. Without this, javac defaults to whatever the
// running JDK is (25 from Android Studio's JBR) while Kotlin targets 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// LWJGL ships its native libraries as classifier artifacts, one set per platform, so the host has
// to be named explicitly. Only the running platform's natives are pulled in.
val lwjglNatives = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "natives-windows"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
        if (System.getProperty("os.arch").startsWith("aarch64")) "natives-macos-arm64"
        else "natives-macos"
    else -> "natives-linux"
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    // GPU-accelerated erosion, offered as an opt-in. GLFW is here only to obtain an offscreen
    // OpenGL context; no window is ever shown.
    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.opengl)
    implementation(libs.lwjgl.glfw)
    runtimeOnly(variantOf(libs.lwjgl.core) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(lwjglNatives) })
}

// Exports at 4096 and beyond are the point of this module, so the tests need room to prove it.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "10g"
}

compose.desktop {
    application {
        mainClass = "com.cartogenesis.desktop.MainKt"

        // Packaging needs jpackage, which the JetBrains Runtime that ships with Android Studio
        // does not include. Point only the packaging step at a full JDK; the rest of the build
        // carries on using whatever Gradle is running under.
        // Override with -PjdkHome=/path/to/jdk if yours lives elsewhere.
        javaHome = (findProperty("jdkHome") as String?)
            ?: System.getenv("JPACKAGE_HOME")
            ?: javaHomeCandidates().firstOrNull { File(it, "bin/jpackage.exe").exists() ||
                File(it, "bin/jpackage").exists() }
            ?: System.getProperty("java.home")

        // The whole point of the desktop build: generation at export resolutions needs gigabytes,
        // which is exactly what Android could not give it. 4096 wants roughly 2GB, 8192 four times
        // that, and the FFT buffers are transient spikes on top.
        jvmArgs += listOf("-Xmx12g")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Cartogenesis"
            // Declared in gradle.properties so the build is the single source of truth for the
            // version, rather than something to be kept in step by hand at release time.
            packageVersion = providers.gradleProperty("cartogenesisVersion").get()

            // jpackage runs jlink, which bundles only the modules it can prove are needed -- and
            // it cannot see through LWJGL's reflection, so it left out jdk.unsupported. That is
            // the module holding sun.misc.Unsafe, which LWJGL uses for native memory, so the
            // packaged build could not start a GL context at all and reported the GPU as
            // unavailable with a NoClassDefFoundError. Nothing was wrong in development, where the
            // full JDK is on hand; only the trimmed runtime was short.
            modules("jdk.unsupported")
        }
    }
}

/*
 * `WebDeploymentContractTest` reads the web module's sources, which Gradle has no way to know
 * about: without declaring them, the test task stays up to date when they change, and the build
 * cache cheerfully restores the previous *passing* result. Caught exactly that way - the id was
 * renamed to prove the guard bites, and the guard reported success from cache.
 */
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.fileTree("web/src/wasmJsMain/kotlin"))
        .withPropertyName("webSourcesReadByDeploymentContractTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
