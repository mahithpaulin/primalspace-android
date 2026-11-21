package com.mahithpaulin.primalspace

import java.security.MessageDigest

object HashWorkload {
    private const val MOD = 1_000_000_007L

    fun compute(seed: String, iterations: Int, chunks: Int): Long {
        val chunkSize = (iterations + chunks - 1) / chunks
        var total = 0L
        for (chunk in 0 until chunks) {
            val start = chunk * chunkSize
            val end = minOf(iterations, start + chunkSize)
            total = (total + chunkValue(seed, start, end)) % MOD
        }
        return total
    }

    private fun chunkValue(seed: String, start: Int, end: Int): Long {
        var sum = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in start until end) {
            digest.reset()
            digest.update("$seed:$index".toByteArray(Charsets.UTF_8))
            val bytes = digest.digest()
            var value = 0L
            for (index2 in 0 until 8) value = (value shl 8) or (bytes[index2].toLong() and 0xff)
            sum = (sum + value % MOD) % MOD
        }
        return sum
    }
}
