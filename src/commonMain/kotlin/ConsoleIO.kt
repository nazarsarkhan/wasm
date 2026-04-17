/**
 * Platform-agnostic console I/O interface.
 * Each compilation target (wasmWasi, jvm) provides its own implementation.
 */
interface ConsoleIO {
    /** Reads one line of input, stripping the trailing newline. Returns null on EOF. */
    fun readLine(): String?

    /** Writes text without a trailing newline (used for the interactive prompt). */
    fun write(text: String)

    /** Writes text followed by a newline. */
    fun writeLine(text: String)

    /** Writes an error message to stderr (or platform equivalent). */
    fun writeError(text: String)

    /**
     * Returns wall-clock time in milliseconds since the Unix epoch.
     * Used both for the /time command and for elapsed-time calculations in /stats.
     */
    fun currentTimeMs(): Long

    /** Returns the command-line arguments passed to the process. */
    fun args(): List<String>

    /** Reads the entire content of a file as a UTF-8 string. */
    fun readFile(path: String): Result<String>

    /**
     * Creates or overwrites [path] with [content].
     * Returns the number of bytes written on success.
     */
    fun writeFile(path: String, content: String): Result<Long>
}
