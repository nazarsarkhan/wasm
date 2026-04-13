# Kotlin/Wasm WASI Echo Console

This project is a Kotlin Multiplatform application that compiles to a WebAssembly binary targeting the WASI environment. It satisfies the JetBrains internship task and adds a small command-driven console so the demo is stronger than a plain echo loop.

## Requirements

- Java 21+
- Node.js 20+

## Run

```bash
./gradlew runWasm
```

On Windows:

```powershell
.\gradlew.bat runWasm
```

## Verify the submission

```bash
./gradlew verifySubmission
```

On Windows:

```powershell
.\gradlew.bat verifySubmission
```

This task builds the project, runs the tests, checks a fixed end-to-end transcript against the generated WASI binary, and confirms the expected `.wasm` artifact exists.

## What it does

- Reads lines from `stdin`
- Echoes regular input as `Wasm received: <input>`
- Supports `/help`, `/stats`, `/upper <text>`, `/lower <text>`, `/reverse <text>`, and `/quit`

## Example session

```text
Kotlin/Wasm WASI echo console
Type text to echo it back, or use /help for commands.
wasm> hello
Wasm received: hello
wasm> /upper internship
upper: INTERNSHIP
wasm> /stats
Stats: total=3, plain=1, commands=2
wasm> /quit
Stopping on user request.
Wasm session closed.
```

## Notes

- The Gradle build uses the official `wasmWasi { nodejs(); binaries.executable() }` target shape.
- `runWasm` compiles the optimized executable and runs the generated Kotlin `.mjs` launcher under Node.js.

## Why direct WASI I/O?

The project reads and writes through direct WASI imports (`fd_read` and `fd_write`) instead of `readln()` and `println()` for the input loop. That choice is deliberate: Kotlin/Wasm supports the WASI target and Node.js runtime, but the standard Kotlin line-reading API is not reliably available for this environment. Using WASI syscalls keeps the interactive loop inside the Wasm binary itself, which is closer to the internship objective and makes the stdin/stdout behavior explicit.
