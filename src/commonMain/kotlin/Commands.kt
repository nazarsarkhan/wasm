fun interface Command {
    fun execute(payload: String, stats: SessionStats, history: History, io: ConsoleIO): LoopResponse
}

fun buildCommandRegistry(): Map<String, Command> = mapOf(
    "help" to Command { _, _, _, _ ->
        LoopResponse.Continue(
            "Commands: /help, /stats, /time, /history, " +
                "/upper <text>, /lower <text>, /reverse <text>, " +
                "/read <file>, /write <file> <content>, /quit",
        )
    },
    "stats" to Command { _, stats, _, io ->
        val elapsedSec = (io.currentTimeMs() - stats.startTimeMs) / 1000
        LoopResponse.Continue(
            "Stats: total=${stats.totalInputs}, plain=${stats.plainMessages}, " +
                "commands=${stats.commandsUsed}, elapsed=${elapsedSec}s",
        )
    },
    "time" to Command { _, stats, _, io ->
        val uptimeMs = io.currentTimeMs() - stats.startTimeMs
        LoopResponse.Continue("uptime: ${uptimeMs}ms")
    },
    "history" to Command { _, _, history, _ ->
        val entries = history.all()
        if (entries.isEmpty()) {
            LoopResponse.Continue("history: (empty)")
        } else {
            LoopResponse.Continue(buildString {
                append("history:")
                entries.forEachIndexed { i, e -> append("\n  ${i + 1}: $e") }
            })
        }
    },
    "upper"   to Command { payload, _, _, _ ->
        LoopResponse.Continue(transformResponse("upper",   payload) { it.uppercase() })
    },
    "lower"   to Command { payload, _, _, _ ->
        LoopResponse.Continue(transformResponse("lower",   payload) { it.lowercase() })
    },
    "reverse" to Command { payload, _, _, _ ->
        LoopResponse.Continue(transformResponse("reverse", payload) { it.reversed() })
    },
    "read" to Command { payload, _, _, io ->
        if (payload.isBlank()) {
            LoopResponse.Continue("Usage: /read <file>")
        } else {
            io.readFile(payload.trim()).fold(
                onSuccess = { LoopResponse.Continue("read: $it") },
                onFailure = { LoopResponse.Continue("read error: ${it.message}") },
            )
        }
    },
    "write" to Command { payload, _, _, io ->
        val space = payload.indexOf(' ')
        if (payload.isBlank() || space == -1) {
            LoopResponse.Continue("Usage: /write <file> <content>")
        } else {
            val path    = payload.substring(0, space)
            val content = payload.substring(space + 1)
            io.writeFile(path, content).fold(
                onSuccess = { LoopResponse.Continue("write: ok ($it bytes)") },
                onFailure = { LoopResponse.Continue("write error: ${it.message}") },
            )
        }
    },
    "quit" to Command { _, _, _, _ -> LoopResponse.Stop },
)

fun parseCommand(input: String): ParsedCommand? {
    if (!input.startsWith("/")) return null
    val commandText = input.drop(1).trim()
    if (commandText.isEmpty()) return ParsedCommand(name = "", payload = "")
    val firstSpace = commandText.indexOf(' ')
    return if (firstSpace == -1) {
        ParsedCommand(name = commandText.lowercase(), payload = "")
    } else {
        ParsedCommand(
            name    = commandText.substring(0, firstSpace).lowercase(),
            payload = commandText.substring(firstSpace + 1).trim(),
        )
    }
}

fun processInput(
    input: String,
    stats: SessionStats,
    history: History,
    commands: Map<String, Command>,
    io: ConsoleIO,
): LoopResponse {
    val command = parseCommand(input)
    if (command == null) {
        stats.plainMessages++
        return LoopResponse.Continue("Wasm received: $input")
    }
    stats.commandsUsed++
    return commands[command.name]?.execute(command.payload, stats, history, io)
        ?: LoopResponse.Continue("Unknown command: /${command.name}. Type /help for usage.")
}

fun transformResponse(name: String, payload: String, transform: (String) -> String): String {
    if (payload.isBlank()) return "Usage: /$name <text>"
    return "$name: ${transform(payload)}"
}
