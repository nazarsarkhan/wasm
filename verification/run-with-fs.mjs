/**
 * Custom Node.js WASI launcher that preopens the current directory (".").
 * This enables the /read and /write commands in the WASI console.
 *
 * The auto-generated Kotlin/Wasm launcher has no preopens configured.
 * This script loads the compiled .wasm binary directly and wires up WASI
 * with filesystem access, so the binary can use path_open.
 *
 * Usage (via Gradle):
 *   ./gradlew runWasmWithFs
 *
 * Direct usage:
 *   node run-with-fs.mjs <path-to-wasm-binary>
 */

import { WASI } from "node:wasi";
import { readFileSync } from "node:fs";
import { argv, env } from "node:process";

const wasmPath = argv[2];
if (!wasmPath) {
  console.error("Usage: node run-with-fs.mjs <path-to-wasm-binary>");
  process.exit(1);
}

const wasi = new WASI({
  version: "preview1",
  args: [wasmPath, ...argv.slice(3)],
  env,
  preopens: { ".": "." },
  stdin: process.stdin.fd,
  stdout: process.stdout.fd,
  stderr: process.stderr.fd,
});

const wasmBytes = readFileSync(wasmPath);
const { instance } = await WebAssembly.instantiate(wasmBytes, {
  wasi_snapshot_preview1: wasi.wasiImport,
});

wasi.start(instance);
