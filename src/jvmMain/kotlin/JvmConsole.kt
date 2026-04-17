import java.io.File

class JvmConsoleIO(private val jvmArgs: List<String>) : ConsoleIO {
    override fun readLine(): String? = kotlin.io.readLine()
    override fun write(text: String) = print(text)
    override fun writeLine(text: String) = println(text)
    override fun writeError(text: String) = System.err.println(text)
    override fun currentTimeMs(): Long = System.currentTimeMillis()
    override fun args(): List<String> = jvmArgs

    override fun readFile(path: String): Result<String> = runCatching {
        File(path).readText()
    }

    override fun writeFile(path: String, content: String): Result<Long> = runCatching {
        File(path).writeText(content)
        content.encodeToByteArray().size.toLong()
    }
}
