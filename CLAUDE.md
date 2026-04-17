# CLAUDE.md — Kotlin/Wasm WASI Echo Console

> This file is the living memory for this project. Claude reads it at the start of every session.
> Update it with `/update` after encountering problems, making decisions, or learning anything useful.

## Project
- **Goal:** JetBrains internship submission — Kotlin Multiplatform project compiling to WASM WASI (and JVM) with an interactive echo console and command dispatcher
- **Stack:** Kotlin Multiplatform 2.1.21 (wasmWasi + jvm targets), Gradle 9.4, Node.js 20+ (WASI host), Java 21+ (build only)
- **Status:** Feature-complete — multiplatform refactor done

## Quick Commands
```bash
# Run interactive WASM console (Node.js, no filesystem)
.\gradlew.bat runWasm

# Run WASM console with filesystem access (/read, /write enabled)
.\gradlew.bat runWasmWithFs

# Run WASM console under wasmtime (wasmtime must be on PATH)
.\gradlew.bat runWasmtime

# Run JVM console
.\gradlew.bat runJvm

# Unit tests only (runs on both jvm and wasmWasi)
.\gradlew.bat test

# Full submission verification (build + tests + E2E transcript + artifact checks)
.\gradlew.bat verifySubmission
```

## Architecture

```
src/
  commonMain/kotlin/
    ConsoleIO.kt      ← platform I/O interface (readLine, write, writeLine, writeError,
                         currentTimeMs, args, readFile, writeFile)
    Models.kt         ← SessionStats, ParsedCommand, LoopResponse, ConsoleArgs, History
    Commands.kt       ← Command interface, buildCommandRegistry(), processInput(),
                         parseCommand(), transformResponse()
    Loop.kt           ← runConsole(io), parseArgs(rawArgs) — the shared main loop
  wasmWasiMain/kotlin/
    WasiConsole.kt    ← WasiConsoleIO: all WASI syscalls (fd_read/write, clock_time_get,
                         args_get, path_open, fd_prestat_get, fd_close)
    Main.kt           ← fun main() = runConsole(WasiConsoleIO())
  jvmMain/kotlin/
    JvmConsole.kt     ← JvmConsoleIO: readLine(), println(), System.currentTimeMillis(), File I/O
    Main.kt           ← fun main(args) = runConsole(JvmConsoleIO(args.toList()))
  commonTest/kotlin/
    CommandsTest.kt   ← 14 tests using FakeConsoleIO; runs on both jvm and wasmWasi
verification/
  sample-input.txt            ← E2E test input (/help, /upper, hello, /history, /reverse, /quit)
  expected-transcript.txt     ← golden stdout for the above (no /stats — elapsed is non-deterministic)
  verify-transcript.mjs       ← E2E runner (exact stdout diff)
  run-with-fs.mjs             ← custom Node.js WASI launcher with preopens: { '.': '.' }
.github/workflows/ci.yml      ← GitHub Actions: Java 21 + Node 20 + ./gradlew verifySubmission
```

**Key patterns:**
- `ConsoleIO` interface decouples all pure logic from platform I/O — the loop, commands, and tests never import anything platform-specific
- `processInput(input, stats, history, commands, io): LoopResponse` — pure dispatch, fully testable via `FakeConsoleIO`
- `buildCommandRegistry(): Map<String, Command>` — open/closed: add a command by adding one entry to the map
- History is appended **after** processing (so `/history` doesn't show itself in its own output)
- `sealed interface LoopResponse { Continue(message?), Stop }` — declarative loop control
- `UnsafeWasmMemoryApi` confined to `WasiConsole.kt` only; all public API is clean Kotlin

**WASI I/O rationale:**
`readln()`/`println()` are not reliably available on `wasmWasi`. The `WasiConsoleIO` uses WASI Preview 1 syscalls directly (`fd_read`, `fd_write`) with a 256-byte buffered stdin reader (carry buffer pattern) and routes errors to fd=2 (stderr).

**Filesystem requires preopened directory:**
`/read` and `/write` use `path_open` via a preopened dir fd discovered at startup with `fd_prestat_get`. The Node.js auto-launcher (`runWasm`) has **no preopens** — use `runWasmWithFs` or `runWasmtime` to enable filesystem commands.

## Active Context
- All 10 features implemented. Pending: run `verifySubmission` to confirm green.

## Known Issues & Solutions

### Problem: `readln()` / `println()` don't work in wasmWasi
**Cause:** Kotlin stdlib I/O is not reliably bridged for the `wasmWasi` target with Node.js.
**Fix:** Use WASI syscalls directly. See `WasiConsole.kt` → `fdRead`/`fdWrite` and the `WasiConsoleIO` class.

### Problem: `/read` and `/write` fail with "No preopened directory"
**Cause:** `runWasm` uses the auto-generated Node.js launcher which has empty preopens.
**Fix:** Use `runWasmWithFs` (custom launcher in `verification/run-with-fs.mjs`) or `runWasmtime --dir=.`.

### Problem: Build output not where you expect
**Cause:** Optimized artifacts land in `build/compileSync/wasmWasi/main/productionExecutable/optimized/`, not `build/libs/`.
**Fix:** Use the Gradle tasks (`runWasm`, etc.) — they already point to the correct dir.

### Problem: `/stats` excluded from the E2E transcript test
**Cause:** `elapsed=Xs` is non-deterministic (depends on wall-clock time at runtime).
**Fix:** The transcript uses `/history` instead. `/stats` is thoroughly covered by `CommandsTest` with a `FakeConsoleIO` that returns a fixed `currentTimeMs()`.

## Gotchas
- `readln()` and `println()` are **not** reliable on `wasmWasi` — always use WASI syscalls directly
- Node.js **20+** required as WASI host; older versions lack full WASI Preview 1 support
- Java **21+** required for the Gradle build; not needed at runtime
- `verifyTranscript` runs via Node.js (not JVM) — it won't appear in JVM test reports; check Gradle output
- The generated `.mjs` launcher is auto-generated by Kotlin/Wasm — do NOT edit it manually
- History is added **after** processing — only for `Continue` responses, not `Stop`
- `jvmRuntimeClasspath` is the correct KMP configuration name for the JVM target's runtime classpath
- `verifySubmission` uses `notCompatibleWithConfigurationCache` — the warning is expected and intentional

## Decisions
- **Direct WASI I/O over stdlib** — `readln()` unreliable on wasmWasi; explicit syscalls give full control
- **ConsoleIO interface (not expect/actual)** — richer abstraction, cleaner injection, easier to stub in tests
- **Sealed `LoopResponse`** — keeps loop declarative; `processInput` is pure and testable without Wasm machinery
- **Command registry (`Map<String, Command>`)** — open/closed principle; adding a command = one map entry
- **History added after processing** — `/history` shows prior inputs only, not itself
- **E2E transcript excludes `/stats`** — elapsed time is wall-clock non-deterministic; `/history` is stable

## DO NOT
- Do NOT use `readln()` or `println()` for the WASI I/O loop — not reliably available on wasmWasi
- Do NOT edit the generated `.mjs` launcher — use `run-with-fs.mjs` instead for filesystem access
- Do NOT run `node <launcher>.mjs` from the project root — must run from the optimized output dir
- Do NOT include `/stats` in `expected-transcript.txt` — elapsed is non-deterministic
- Do NOT skip `verifySubmission` before handing off — it is the single source of truth for submission readiness
