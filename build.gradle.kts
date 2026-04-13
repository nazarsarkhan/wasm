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
    workingDir(layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/optimized"))
    commandLine(
        "node",
        "${project.name}-wasm-wasi.mjs",
    )
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}
