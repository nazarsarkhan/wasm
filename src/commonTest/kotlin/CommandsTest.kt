import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ── Test double ───────────────────────────────────────────────────────────────

class FakeConsoleIO(private val fixedTimeMs: Long = 5_000L) : ConsoleIO {
    private val fileStore = mutableMapOf<String, String>()

    override fun readLine(): String? = null
    override fun write(text: String) {}
    override fun writeLine(text: String) {}
    override fun writeError(text: String) {}
    override fun currentTimeMs(): Long = fixedTimeMs
    override fun args(): List<String> = emptyList()

    override fun readFile(path: String): Result<String> =
        fileStore[path]?.let { Result.success(it) }
            ?: Result.failure(Exception("File not found: $path"))

    override fun writeFile(path: String, content: String): Result<Long> {
        fileStore[path] = content
        return Result.success(content.encodeToByteArray().size.toLong())
    }

    fun addFile(path: String, content: String) { fileStore[path] = content }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class CommandsTest {
    private val io       = FakeConsoleIO(fixedTimeMs = 5_000L)
    private val stats    = SessionStats(startTimeMs = 0L)
    private val history  = History()
    private val commands = buildCommandRegistry()

    private fun process(input: String) = processInput(input, stats, history, commands, io)

    @Test
    fun `plain input echoes with prefix`() {
        stats.totalInputs = 1
        val result = process("hello")
        assertEquals(1, stats.plainMessages)
        assertEquals(0, stats.commandsUsed)
        assertEquals("Wasm received: hello", assertIs<LoopResponse.Continue>(result).message)
    }

    @Test
    fun `reverse command transforms text`() {
        stats.totalInputs = 1
        val result = process("/reverse abc123")
        assertEquals("reverse: 321cba", assertIs<LoopResponse.Continue>(result).message)
        assertEquals(1, stats.commandsUsed)
    }

    @Test
    fun `upper and lower commands work`() {
        assertEquals("upper: HELLO", assertIs<LoopResponse.Continue>(process("/upper hello")).message)
        assertEquals("lower: hello", assertIs<LoopResponse.Continue>(process("/lower HELLO")).message)
    }

    @Test
    fun `stats command includes elapsed time`() {
        // processInput bumps commandsUsed from 1 → 2 before executing the command.
        val s = SessionStats(totalInputs = 4, plainMessages = 2, commandsUsed = 1, startTimeMs = 0L)
        val result = processInput("/stats", s, history, commands, io)
        // fixedTimeMs=5000, startTimeMs=0 → elapsed = 5s
        assertEquals(
            "Stats: total=4, plain=2, commands=2, elapsed=5s",
            assertIs<LoopResponse.Continue>(result).message,
        )
    }

    @Test
    fun `quit command stops the loop`() {
        stats.totalInputs = 1
        assertIs<LoopResponse.Stop>(process("/quit"))
        assertEquals(1, stats.commandsUsed)
    }

    @Test
    fun `parse command preserves payload trimming`() {
        val parsed = parseCommand("/upper   hello wasm   ")
        assertEquals("upper", parsed?.name)
        assertEquals("hello wasm", parsed?.payload)
    }

    @Test
    fun `history command shows previous inputs`() {
        history.add("first")
        history.add("second")
        val msg = assertIs<LoopResponse.Continue>(process("/history")).message
        assertNotNull(msg)
        assertTrue(msg.contains("1: first"))
        assertTrue(msg.contains("2: second"))
    }

    @Test
    fun `history is empty when no inputs recorded`() {
        assertEquals("history: (empty)", assertIs<LoopResponse.Continue>(process("/history")).message)
    }

    @Test
    fun `time command shows uptime`() {
        // fixedTimeMs=5000, startTimeMs=0 → uptime = 5000ms
        assertEquals("uptime: 5000ms", assertIs<LoopResponse.Continue>(process("/time")).message)
    }

    @Test
    fun `read command returns file contents`() {
        io.addFile("test.txt", "hello file")
        assertEquals("read: hello file", assertIs<LoopResponse.Continue>(process("/read test.txt")).message)
    }

    @Test
    fun `read command fails gracefully on missing file`() {
        val msg = assertIs<LoopResponse.Continue>(process("/read missing.txt")).message
        assertTrue(msg?.startsWith("read error:") == true)
    }

    @Test
    fun `write command stores file and reports byte count`() {
        // "hello world" = 11 bytes
        assertEquals("write: ok (11 bytes)", assertIs<LoopResponse.Continue>(process("/write out.txt hello world")).message)
    }

    @Test
    fun `unknown command returns helpful message`() {
        val msg = assertIs<LoopResponse.Continue>(process("/frobnicate")).message
        assertTrue(msg?.contains("Unknown command") == true)
        assertTrue(msg?.contains("frobnicate") == true)
    }

    @Test
    fun `transform command with no payload returns usage hint`() {
        assertEquals("Usage: /upper <text>", assertIs<LoopResponse.Continue>(process("/upper")).message)
    }
}
