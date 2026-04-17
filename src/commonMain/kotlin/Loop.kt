fun parseArgs(rawArgs: List<String>): ConsoleArgs {
    var showBanner = true
    var prompt = "wasm> "
    var i = 0
    while (i < rawArgs.size) {
        when (rawArgs[i]) {
            "--no-banner" -> showBanner = false
            "--prompt"   -> if (i + 1 < rawArgs.size) { i++; prompt = rawArgs[i] }
        }
        i++
    }
    return ConsoleArgs(showBanner = showBanner, prompt = prompt)
}

fun runConsole(io: ConsoleIO) {
    val consoleArgs = parseArgs(io.args())
    val stats   = SessionStats(startTimeMs = io.currentTimeMs())
    val history = History()
    val commands = buildCommandRegistry()

    if (consoleArgs.showBanner) {
        io.writeLine("Kotlin/Wasm WASI echo console")
        io.writeLine("Type text to echo it back, or use /help for commands.")
    }

    while (true) {
        io.write(consoleArgs.prompt)
        val input = io.readLine() ?: break
        stats.totalInputs++

        when (val response = processInput(input, stats, history, commands, io)) {
            is LoopResponse.Continue -> {
                if (response.message != null) io.writeLine(response.message)
                history.add(input)
            }
            LoopResponse.Stop -> {
                io.writeLine("Stopping on user request.")
                break
            }
        }
    }

    io.writeLine("Wasm session closed.")
}
