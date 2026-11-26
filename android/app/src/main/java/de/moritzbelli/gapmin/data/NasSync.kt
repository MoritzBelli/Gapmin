package de.moritzbelli.gapmin.data

import de.moritzbelli.gapmin.core.Json
import de.moritzbelli.gapmin.core.asDouble
import de.moritzbelli.gapmin.core.asMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Upload the app database to the Gapmin server on the NAS, which merges it into PostgreSQL. */
object NasSync {
    data class Result(val ok: Boolean, val message: String, val rows: Long? = null)

    suspend fun upload(baseUrl: String, token: String, bytes: ByteArray): Result = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext Result(false, "Keine Server-Adresse eingetragen")
        try {
            val c = URL("$base/api/sync").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = (c.errorStream ?: c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                return@withContext Result(false, "Server antwortet $code: ${err.take(200)}")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            Result(true, "Synchronisiert", Json.parse(body).asMap()?.get("rows").asDouble()?.toLong())
        } catch (e: SocketTimeoutException) {
            Result(false, "Zeitüberschreitung")
        } catch (e: Exception) {
            Result(false, "NAS nicht erreichbar (${e.message ?: e.javaClass.simpleName})")
        }
    }

    suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = URL("${baseUrl.trim().trimEnd('/')}/api/health").openConnection() as HttpURLConnection
            c.connectTimeout = 4_000
            c.readTimeout = 4_000
            c.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }
}
