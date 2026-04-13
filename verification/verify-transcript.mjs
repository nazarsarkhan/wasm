import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";

const [runtimeDir, entryModule, inputPath, expectedPath] = process.argv.slice(2);

if (!runtimeDir || !entryModule || !inputPath || !expectedPath) {
  console.error("Usage: node verify-transcript.mjs <runtimeDir> <entryModule> <inputPath> <expectedPath>");
  process.exit(1);
}

const input = readFileSync(inputPath, "utf8");
const expected = readFileSync(expectedPath, "utf8").replace(/\r\n/g, "\n");

const result = spawnSync("node", ["--no-warnings", entryModule], {
  cwd: runtimeDir,
  input,
  encoding: "utf8",
});

if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}

if (result.status !== 0) {
  console.error(result.stdout);
  console.error(result.stderr);
  process.exit(result.status ?? 1);
}

const actual = result.stdout.replace(/\r\n/g, "\n");

if (actual !== expected) {
  console.error("Transcript verification failed.");
  console.error("Expected:");
  console.error(expected);
  console.error("Actual:");
  console.error(actual);
  process.exit(1);
}
