data class SessionStats(
    var plainMessages: Int = 0,
    var commandsUsed: Int = 0,
    var totalInputs: Int = 0,
    val startTimeMs: Long = 0L,
)

data class ParsedCommand(
    val name: String,
    val payload: String,
)

sealed interface LoopResponse {
    data class Continue(val message: String?) : LoopResponse
    data object Stop : LoopResponse
}

data class ConsoleArgs(
    val showBanner: Boolean = true,
    val prompt: String = "wasm> ",
)

class History(private val maxSize: Int = 10) {
    private val buffer = ArrayDeque<String>()

    fun add(entry: String) {
        if (buffer.size >= maxSize) buffer.removeFirst()
        buffer.addLast(entry)
    }

    fun all(): List<String> = buffer.toList()
}
