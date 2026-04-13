import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MainTest {
    @Test
    fun `plain input echoes with prefix`() {
        val stats = SessionStats(totalInputs = 1)

        val result = processInput("hello", stats)

        assertEquals(1, stats.plainMessages)
        assertEquals(0, stats.commandsUsed)
        assertEquals("Wasm received: hello", assertIs<LoopResponse.Continue>(result).message)
    }

    @Test
    fun `transform commands return processed text`() {
        val stats = SessionStats(totalInputs = 1)

        val result = processInput("/reverse abc123", stats)

        assertEquals("reverse: 321cba", assertIs<LoopResponse.Continue>(result).message)
        assertEquals(1, stats.commandsUsed)
    }

    @Test
    fun `stats command reports current counters`() {
        val stats = SessionStats(totalInputs = 4, plainMessages = 2, commandsUsed = 1)

        val result = processInput("/stats", stats)

        assertEquals(
            "Stats: total=4, plain=2, commands=2",
            assertIs<LoopResponse.Continue>(result).message,
        )
    }

    @Test
    fun `quit command stops the loop`() {
        val stats = SessionStats(totalInputs = 1)

        val result = processInput("/quit", stats)

        assertIs<LoopResponse.Stop>(result)
        assertEquals(1, stats.commandsUsed)
    }

    @Test
    fun `parse command preserves payload trimming`() {
        val parsed = parseCommand("/upper   hello wasm   ")

        assertEquals("upper", parsed?.name)
        assertEquals("hello wasm", parsed?.payload)
    }
}
