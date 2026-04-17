import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// ── WASI Preview 1 imports ────────────────────────────────────────────────────

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun fdRead(fd: Int, iovs: Int, iovsLen: Int, nRead: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_write")
private external fun fdWrite(fd: Int, iovs: Int, iovsLen: Int, nWritten: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_close")
private external fun fdClose(fd: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_prestat_get")
private external fun fdPrestatGet(fd: Int, prestatPtr: Int): Int

@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun pathOpen(
    dirFd: Int,
    dirFlags: Int,
    pathPtr: Int,
    pathLen: Int,
    oFlags: Int,
    fsRightsBase: Long,
    fsRightsInheriting: Long,
    fdFlags: Int,
    openedFdPtr: Int,
): Int

@WasmImport("wasi_snapshot_preview1", "clock_time_get")
private external fun clockTimeGet(clockId: Int, precision: Long, timePtr: Int): Int

@WasmImport("wasi_snapshot_preview1", "args_sizes_get")
private external fun argsSizesGet(argcPtr: Int, argvBufSizePtr: Int): Int

@WasmImport("wasi_snapshot_preview1", "args_get")
private external fun argsGet(argvPtr: Int, argvBufPtr: Int): Int

// ── WASI constants ────────────────────────────────────────────────────────────

private const val FD_STDIN    = 0
private const val FD_STDOUT   = 1
private const val FD_STDERR   = 2
private const val WASI_OK     = 0
private const val CLOCK_REALTIME = 0

private const val RIGHTS_FD_READ  = 0x02L
private const val RIGHTS_FD_WRITE = 0x40L
private const val OFLAG_CREAT     = 0x01
private const val OFLAG_TRUNC     = 0x08

// ── Helpers ───────────────────────────────────────────────────────────────────

@OptIn(UnsafeWasmMemoryApi::class)
private fun ByteArray.copyToMemory(ptr: Pointer) {
    for (i in indices) (ptr + i).storeByte(this[i])
}

/**
 * Reads a WASI u64 (nanoseconds) as two i32 loads and converts to milliseconds.
 * Avoids relying on Pointer.loadLong() which may not be stable across Kotlin/Wasm versions.
 */
private fun Pointer.loadNanosToMs(): Long {
    val lo = this.loadInt().toLong() and 0xFFFFFFFFL
    val hi = (this + 4).loadInt().toLong() and 0xFFFFFFFFL
    return ((hi shl 32) or lo) / 1_000_000L
}

// ── Low-level WASI operations ─────────────────────────────────────────────────

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWrite(fd: Int, text: String) = withScopedMemoryAllocator { allocator ->
    val bytes = text.encodeToByteArray()
    if (bytes.isEmpty()) return@withScopedMemoryAllocator
    val buf      = allocator.allocate(bytes.size)
    val iov      = allocator.allocate(8)
    val nWritten = allocator.allocate(4)
    bytes.copyToMemory(buf)
    iov.storeInt(buf.address.toInt())
    (iov + 4).storeInt(bytes.size)
    nWritten.storeInt(0)
    val errno = fdWrite(fd, iov.address.toInt(), 1, nWritten.address.toInt())
    if (errno != WASI_OK) error("fd_write failed (errno=$errno)")
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiCurrentTimeMs(): Long = withScopedMemoryAllocator { allocator ->
    val timePtr = allocator.allocate(8)
    val errno = clockTimeGet(CLOCK_REALTIME, 1_000_000L, timePtr.address.toInt())
    if (errno != WASI_OK) return@withScopedMemoryAllocator 0L
    timePtr.loadNanosToMs()
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiArgs(): List<String> = withScopedMemoryAllocator { allocator ->
    val argcPtr    = allocator.allocate(4)
    val bufSizePtr = allocator.allocate(4)
    if (argsSizesGet(argcPtr.address.toInt(), bufSizePtr.address.toInt()) != WASI_OK) {
        return@withScopedMemoryAllocator emptyList()
    }
    val argc    = argcPtr.loadInt()
    val bufSize = bufSizePtr.loadInt()
    if (argc == 0 || bufSize == 0) return@withScopedMemoryAllocator emptyList()

    val argv    = allocator.allocate(argc * 4)
    val argvBuf = allocator.allocate(bufSize)
    if (argsGet(argv.address.toInt(), argvBuf.address.toInt()) != WASI_OK) {
        return@withScopedMemoryAllocator emptyList()
    }

    // Strings in argvBuf are null-terminated and stored sequentially.
    // Scan for null bytes to extract each argument without needing pointer arithmetic.
    val result = mutableListOf<String>()
    var start = 0
    for (pos in 0 until bufSize) {
        if ((argvBuf + pos).loadByte() == 0.toByte()) {
            result.add(ByteArray(pos - start) { (argvBuf + start + it).loadByte() }.decodeToString())
            start = pos + 1
            if (result.size == argc) break
        }
    }
    result
}

/**
 * Scans preopened file descriptors (starting at fd=3) to find a preopened directory.
 * Returns the fd of the first preopened dir, or -1 if none is found.
 * Filesystem commands (/read, /write) will report a friendly error when this is -1.
 */
@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiPreopenedDirFd(): Int = withScopedMemoryAllocator { allocator ->
    val prestat = allocator.allocate(8)
    for (fd in 3..20) {
        val errno = fdPrestatGet(fd, prestat.address.toInt())
        if (errno != WASI_OK) break           // ERRNO_BADF: no more preopened fds
        if (prestat.loadByte().toInt() == 0)  // PREOPENTYPE_DIR = 0
            return@withScopedMemoryAllocator fd
    }
    -1
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiReadFile(dirFd: Int, path: String): Result<String> = runCatching {
    withScopedMemoryAllocator { allocator ->
        val pathBytes    = path.encodeToByteArray()
        val pathBuf      = allocator.allocate(pathBytes.size)
        pathBytes.copyToMemory(pathBuf)
        val openedFdPtr  = allocator.allocate(4)

        val openErrno = pathOpen(
            dirFd, 0,
            pathBuf.address.toInt(), pathBytes.size,
            0, RIGHTS_FD_READ, 0L, 0,
            openedFdPtr.address.toInt(),
        )
        if (openErrno != WASI_OK) error("path_open '$path' failed (errno=$openErrno)")
        val fd = openedFdPtr.loadInt()

        val readBuf = allocator.allocate(256)
        val iov     = allocator.allocate(8)
        val nRead   = allocator.allocate(4)
        iov.storeInt(readBuf.address.toInt())
        (iov + 4).storeInt(256)

        val collected = mutableListOf<Byte>()
        try {
            while (true) {
                nRead.storeInt(0)
                val errno = fdRead(fd, iov.address.toInt(), 1, nRead.address.toInt())
                if (errno != WASI_OK) error("fd_read failed (errno=$errno)")
                val n = nRead.loadInt()
                if (n == 0) break
                for (i in 0 until n) collected.add((readBuf + i).loadByte())
            }
        } finally {
            fdClose(fd)
        }
        collected.toByteArray().decodeToString()
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
private fun wasiWriteFile(dirFd: Int, path: String, content: String): Result<Long> = runCatching {
    withScopedMemoryAllocator { allocator ->
        val pathBytes    = path.encodeToByteArray()
        val pathBuf      = allocator.allocate(pathBytes.size)
        pathBytes.copyToMemory(pathBuf)
        val contentBytes = content.encodeToByteArray()
        val contentBuf   = allocator.allocate(contentBytes.size)
        contentBytes.copyToMemory(contentBuf)
        val openedFdPtr  = allocator.allocate(4)

        val openErrno = pathOpen(
            dirFd, 0,
            pathBuf.address.toInt(), pathBytes.size,
            OFLAG_CREAT or OFLAG_TRUNC, RIGHTS_FD_WRITE, 0L, 0,
            openedFdPtr.address.toInt(),
        )
        if (openErrno != WASI_OK) error("path_open '$path' for write failed (errno=$openErrno)")
        val fd = openedFdPtr.loadInt()

        val iov      = allocator.allocate(8)
        val nWritten = allocator.allocate(4)
        iov.storeInt(contentBuf.address.toInt())
        (iov + 4).storeInt(contentBytes.size)
        nWritten.storeInt(0)
        try {
            val errno = fdWrite(fd, iov.address.toInt(), 1, nWritten.address.toInt())
            if (errno != WASI_OK) error("fd_write failed (errno=$errno)")
        } finally {
            fdClose(fd)
        }
        contentBytes.size.toLong()
    }
}

// ── WasiConsoleIO ─────────────────────────────────────────────────────────────

class WasiConsoleIO : ConsoleIO {
    private val preopenedDirFd: Int  = wasiPreopenedDirFd()
    private val cachedArgs: List<String> = wasiArgs()

    /** Carry buffer: leftover bytes from a previous fd_read that crossed a line boundary. */
    private val stdinCarry = ArrayDeque<Byte>()

    override fun write(text: String)      = wasiWrite(FD_STDOUT, text)
    override fun writeLine(text: String)  = wasiWrite(FD_STDOUT, "$text\n")
    override fun writeError(text: String) = wasiWrite(FD_STDERR, "$text\n")
    override fun currentTimeMs(): Long    = wasiCurrentTimeMs()
    override fun args(): List<String>     = cachedArgs

    @OptIn(UnsafeWasmMemoryApi::class)
    override fun readLine(): String? {
        // Fast path: a complete line is already in the carry buffer.
        val nlIdx = stdinCarry.indexOf('\n'.code.toByte())
        if (nlIdx >= 0) return extractLine(nlIdx)

        return withScopedMemoryAllocator { allocator ->
            // 256-byte buffer: reads up to a full terminal line in one syscall.
            val buf   = allocator.allocate(256)
            val iov   = allocator.allocate(8)
            val nRead = allocator.allocate(4)
            iov.storeInt(buf.address.toInt())
            (iov + 4).storeInt(256)

            while (true) {
                nRead.storeInt(0)
                val errno = fdRead(FD_STDIN, iov.address.toInt(), 1, nRead.address.toInt())
                if (errno != WASI_OK) error("fd_read failed (errno=$errno)")

                val n = nRead.loadInt()
                if (n == 0) {
                    // EOF: flush carry buffer as the last line (no trailing newline).
                    return@withScopedMemoryAllocator if (stdinCarry.isEmpty()) null else {
                        stdinCarry.toByteArray().decodeToString().also { stdinCarry.clear() }
                    }
                }

                for (i in 0 until n) stdinCarry.addLast((buf + i).loadByte())

                val idx = stdinCarry.indexOf('\n'.code.toByte())
                if (idx >= 0) return@withScopedMemoryAllocator extractLine(idx)
            }
            null
        }
    }

    private fun extractLine(nlIdx: Int): String {
        val bytes = ByteArray(nlIdx) { stdinCarry[it] }
        repeat(nlIdx + 1) { stdinCarry.removeFirst() }
        return bytes.decodeToString().trimEnd('\r')
    }

    override fun readFile(path: String): Result<String> =
        if (preopenedDirFd < 0)
            Result.failure(Exception("No preopened directory. Use 'runWasmWithFs' (Node.js) or 'runWasmtime' tasks."))
        else wasiReadFile(preopenedDirFd, path)

    override fun writeFile(path: String, content: String): Result<Long> =
        if (preopenedDirFd < 0)
            Result.failure(Exception("No preopened directory. Use 'runWasmWithFs' (Node.js) or 'runWasmtime' tasks."))
        else wasiWriteFile(preopenedDirFd, path, content)
}
