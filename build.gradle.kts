import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.1.21"
}

group = "com.nazar.internship"
version = "1.0.0"

repositories {
    mavenCentral()
}

val optimizedRuntimeDir = layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/optimized")
val wasiEntryModule = "${project.name}-wasm-wasi.mjs"
val wasiBinaryName = "${project.name}-wasm-wasi.wasm"
val verificationDir = layout.projectDirectory.dir("verification")

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        wasmWasiMain.dependencies {
        }
        wasmWasiTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<Exec>("runWasm") {
    group = "application"
    description = "Builds the Kotlin/Wasm WASI executable and runs it with Node.js."
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")
    workingDir(optimizedRuntimeDir)
    commandLine(
        "node",
        wasiEntryModule,
    )
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}

val verifyTranscript by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs a fixed transcript against the WASI binary and compares the exact stdout."
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")
    inputs.file(verificationDir.file("sample-input.txt"))
    inputs.file(verificationDir.file("expected-transcript.txt"))
    inputs.file(verificationDir.file("verify-transcript.mjs"))

    commandLine(
        "node",
        "--no-warnings",
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
    group = "verification"
    description = "Builds the project, runs tests, verifies the transcript, and checks the generated WASI artifacts."
    notCompatibleWithConfigurationCache("Uses ad-hoc artifact assertions during task execution.")
    dependsOn("build")

    doLast {
        val runtimeDir = optimizedRuntimeDir.get().asFile
        val wasmArtifact = runtimeDir.resolve(wasiBinaryName)
        val launcherArtifact = runtimeDir.resolve(wasiEntryModule)

        check(wasmArtifact.isFile) {
            "Expected generated WASM binary at ${wasmArtifact.absolutePath}"
        }
        check(launcherArtifact.isFile) {
            "Expected generated Node launcher at ${launcherArtifact.absolutePath}"
        }
    }
}
