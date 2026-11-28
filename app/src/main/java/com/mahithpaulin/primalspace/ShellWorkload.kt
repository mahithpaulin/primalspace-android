package com.mahithpaulin.primalspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject

object ShellWorkload {
    private const val MAX_OUTPUT = 65536
    private const val MAX_COMMAND = 4000

    fun execute(params: JSONObject, maxSeconds: Int, workDir: File): JSONObject {
        val command = params.optString("command").trim()
        require(command.isNotEmpty()) { "shell_command_required" }
        require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND) { "shell_command_too_large" }
        workDir.mkdirs()
        val builder = ProcessBuilder("sh", "-c", command)
        builder.directory(workDir)
        builder.environment()["HOME"] = workDir.absolutePath
        builder.environment()["TMPDIR"] = workDir.absolutePath
        builder.environment()["PRIMAL_ITEMS"] = params.optJSONArray("items")?.toString() ?: "null"
        builder.environment()["PRIMAL_SHARD_INDEX"] = params.optInt("shard_index", 0).toString()
        builder.environment()["PRIMAL_SHARD_COUNT"] = params.optInt("shard_count", 1).toString()
        val process = builder.start()
        val stdout = AtomicReference<Output?>()
        val stderr = AtomicReference<Output?>()
        val stdoutThread = thread { stdout.set(readBounded(process.inputStream)) }
        val stderrThread = thread { stderr.set(readBounded(process.errorStream)) }
        val finished = process.waitFor(maxSeconds.coerceIn(1, 30).toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            throw IllegalStateException("shell_command_timed_out")
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)
        val out = stdout.get() ?: Output("", false)
        val err = stderr.get() ?: Output("", false)
        return JSONObject()
            .put("exit_code", process.exitValue())
            .put("stdout", out.text)
            .put("stderr", err.text)
            .put("stdout_truncated", out.truncated)
            .put("stderr_truncated", err.truncated)
    }

    private data class Output(val text: String, val truncated: Boolean)

    private fun readBounded(stream: InputStream): Output {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var truncated = false
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val remaining = MAX_OUTPUT - output.size()
                if (remaining > 0) {
                    output.write(buffer, 0, minOf(read, remaining))
                } else {
                    truncated = true
                }
            }
        }
        return Output(output.toString(Charsets.UTF_8.name()), truncated)
    }
}
