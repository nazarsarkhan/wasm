import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl

plugins {
    kotlin("multiplatform") version "2.1.21"
}

group   = "com.nazar.internship"
version = "1.0.0"

repositories {
    mavenCentral()
}

val optimizedRuntimeDir = layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/optimized")
val wasiEntryModule     = "${rootProject.name}-wasm-wasi.mjs"
val wasiBinaryName      = "${rootProject.name}-wasm-wasi.wasm"
val verificationDir     = layout.projectDirectory.dir("verification")

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    // ── Targets ──────────────────────────────────────────────────────────────
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    jvm()

    // ── Source sets ──────────────────────────────────────────────────────────
    sourceSets {
        commonMain.dependencies { }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmWasiMain.dependencies { }
        jvmMain.dependencies { }
    }
}

// ── WASM / WASI tasks ─────────────────────────────────────────────────────────

tasks.register<Exec>("runWasm") {
    group       = "application"
    description = "Builds the Kotlin/Wasm WASI executable and runs it under Node.js."
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")
    workingDir(optimizedRuntimeDir)
    commandLine("node", wasiEntryModule)
    standardInput  = System.`in`
    standardOutput = System.out
    errorOutput    = System.err
}

tasks.register<Exec>("runWasmWithFs") {
    group       = "application"
    description = "Runs the WASI binary under a custom Node.js launcher with '.' preopened for /read and /write."
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")
    commandLine(
        "node", "--no-warnings",
        verificationDir.file("run-with-fs.mjs").asFile.absolutePath,
        optimizedRuntimeDir.get().asFile.resolve(wasiBinaryName).absolutePath,
    )
    standardInput  = System.`in`
    standardOutput = System.out
    errorOutput    = System.err
}

tasks.register<Exec>("runWasmtime") {
    group       = "application"
    description = "Runs the WASI binary under wasmtime with '.' preopened (wasmtime must be on PATH)."
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")
    commandLine(
        "wasmtime", "--dir=.",
        optimizedRuntimeDir.get().asFile.resolve(wasiBinaryName).absolutePath,
    )
    standardInput  = System.`in`
    standardOutput = System.out
    errorOutput    = System.err
}

// ── JVM task ──────────────────────────────────────────────────────────────────

tasks.register<JavaExec>("runJvm") {
    group       = "application"
    description = "Builds and runs the JVM implementation of the console."
    dependsOn("jvmJar")
    val jvmJarTask = tasks.named<Jar>("jvmJar")
    classpath(configurations.named("jvmRuntimeClasspath"), jvmJarTask)
    mainClass.set("MainKt")
    standardInput  = System.`in`
    standardOutput = System.out
    errorOutput    = System.err
}

// ── Verification tasks ────────────────────────────────────────────────────────

val verifyTranscript by tasks.registering(Exec::class) {
    group       = "verification"
    description = "Runs a fixed transcript against the WASI binary and diffs the exact stdout."
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")
    inputs.file(verificationDir.file("sample-input.txt"))
    inputs.file(verificationDir.file("expected-transcript.txt"))
    inputs.file(verificationDir.file("verify-transcript.mjs"))
    commandLine(
        "node", "--no-warnings",
        verificationDir.file("verify-transcript.mjs").asFile.absolutePath,
        optimizedRuntimeDir.get().asFile.absolutePath,
        wasiEntryModule,
        verificationDir.file("sample-input.txt").asFile.absolutePath,
        verificationDir.file("expected-transcript.txt").asFile.absolutePath,
    )
}

tasks.named("check") {
    dependsOn(verifyTranscript)
}

tasks.register("verifySubmission") {
    group       = "verification"
    description = "Builds the project, runs all tests and verifications, and asserts generated artifacts exist."
    notCompatibleWithConfigurationCache("Uses ad-hoc artifact assertions during task execution.")
    dependsOn("build")

    doLast {
        // WASM artifacts
        val runtimeDir      = optimizedRuntimeDir.get().asFile
        val wasmArtifact    = runtimeDir.resolve(wasiBinaryName)
        val launcherArtifact = runtimeDir.resolve(wasiEntryModule)
        check(wasmArtifact.isFile)    { "Expected WASM binary at ${wasmArtifact.absolutePath}" }
        check(launcherArtifact.isFile) { "Expected Node launcher at ${launcherArtifact.absolutePath}" }

        // JVM artifact
        val jvmJar = layout.buildDirectory.file("libs/${rootProject.name}-jvm-${version}.jar").get().asFile
        check(jvmJar.isFile) { "Expected JVM jar at ${jvmJar.absolutePath}" }
    }
}
