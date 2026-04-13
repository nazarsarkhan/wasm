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
