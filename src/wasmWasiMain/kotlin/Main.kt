import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

data class SessionStats(
    var plainMessages: Int = 0,
    var commandsUsed: Int = 0,
    var totalInputs: Int = 0,
)

private const val prompt = "wasm> "
private const val stdinFd = 0
private const val stdoutFd = 1
private const val wasiSuccess = 0

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun fdRead(fd: Int, iovs: Int, iovsLength: Int, bytesRead: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_write")
private external fun fdWrite(fd: Int, iovs: Int, iovsLength: Int, bytesWritten: Int): Int

fun main() {
    val stats = SessionStats()

    writeLine("Kotlin/Wasm WASI echo console")
    writeLine("Type text to echo it back, or use /help for commands.")

    while (true) {
        writeText(prompt)
        val input = readLineFromStdin() ?: break
        stats.totalInputs++

        when (val response = processInput(input, stats)) {
            is LoopResponse.Continue -> {
                if (response.message != null) {
                    writeLine(response.message)
                }
            }

            LoopResponse.Stop -> {
                writeLine("Stopping on user request.")
                break
            }
        }
    }

    writeLine("Wasm session closed.")
}

data class ParsedCommand(
    val name: String,
    val payload: String,
)

sealed interface LoopResponse {
    data class Continue(val message: String?) : LoopResponse
    data object Stop : LoopResponse
}

fun processInput(input: String, stats: SessionStats): LoopResponse {
    val command = parseCommand(input)
    if (command == null) {
        stats.plainMessages++
        return LoopResponse.Continue("Wasm received: $input")
    }

    stats.commandsUsed++
    return when (command.name) {
        "help" -> LoopResponse.Continue(
            "Commands: /help, /stats, /upper <text>, /lower <text>, /reverse <text>, /quit",
        )

        "stats" -> LoopResponse.Continue(
            "Stats: total=${stats.totalInputs}, plain=${stats.plainMessages}, commands=${stats.commandsUsed}",
        )

        "upper" -> LoopResponse.Continue(transformResponse("upper", command.payload) { it.uppercase() })
        "lower" -> LoopResponse.Continue(transformResponse("lower", command.payload) { it.lowercase() })
        "reverse" -> LoopResponse.Continue(transformResponse("reverse", command.payload) { it.reversed() })
        "quit" -> LoopResponse.Stop
        else -> LoopResponse.Continue("Unknown command: /${command.name}. Type /help for usage.")
    }
}

fun parseCommand(input: String): ParsedCommand? {
    if (!input.startsWith("/")) return null

    val commandText = input.drop(1).trim()
    if (commandText.isEmpty()) {
        return ParsedCommand(name = "", payload = "")
    }

    val firstSpace = commandText.indexOf(' ')
    return if (firstSpace == -1) {
        ParsedCommand(name = commandText.lowercase(), payload = "")
    } else {
        ParsedCommand(
            name = commandText.substring(0, firstSpace).lowercase(),
            payload = commandText.substring(firstSpace + 1).trim(),
        )
    }
}

fun transformResponse(name: String, payload: String, transform: (String) -> String): String {
    if (payload.isBlank()) {
        return "Usage: /$name <text>"
    }

    return "$name: ${transform(payload)}"
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun readLineFromStdin(): String? {
    return withScopedMemoryAllocator { allocator ->
        val buffer = allocator.allocate(1)
        val iovec = allocator.allocate(8)
        val bytesRead = allocator.allocate(4)

        iovec.storeInt(buffer.address.toInt())
        (iovec + 4).storeInt(1)

        val collected = mutableListOf<Byte>()

        while (true) {
            bytesRead.storeInt(0)
            val errno = fdRead(stdinFd, iovec.address.toInt(), 1, bytesRead.address.toInt())
            if (errno != wasiSuccess) {
                error("fd_read failed with errno=$errno")
            }

            val read = bytesRead.loadInt()
            if (read == 0) {
                return@withScopedMemoryAllocator if (collected.isEmpty()) null else collected.toByteArray().decodeToString()
            }

            val byte = buffer.loadByte()
            when (byte.toInt()) {
                '\n'.code -> return@withScopedMemoryAllocator collected.toByteArray().decodeToString()
                '\r'.code -> continue
                else -> collected += byte
            }
        }
        null
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun writeLine(text: String) {
    writeText("$text\n")
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun writeText(text: String) = withScopedMemoryAllocator { allocator ->
    val payload = text.encodeToByteArray()
    val buffer = allocator.allocate(payload.size)
    val iovec = allocator.allocate(8)
    val bytesWritten = allocator.allocate(4)

    payload.copyToMemory(buffer)
    iovec.storeInt(buffer.address.toInt())
    (iovec + 4).storeInt(payload.size)
    bytesWritten.storeInt(0)

    val errno = fdWrite(stdoutFd, iovec.address.toInt(), 1, bytesWritten.address.toInt())
    if (errno != wasiSuccess) {
        error("fd_write failed with errno=$errno")
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun ByteArray.copyToMemory(pointer: Pointer) {
    for (index in indices) {
        (pointer + index).storeByte(this[index])
    }
}
