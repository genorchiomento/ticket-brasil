package br.com.ticketbrasil.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class ApiResponse(val status: Int, val body: String, val headers: Map<String, List<String>>, private val mapper: JsonMapper) {
    val json: JsonNode by lazy { mapper.readTree(body) }

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** Thin JDK HttpClient wrapper: thread-safe, so concurrency tests can share it. */
class ApiClient(private val baseUrl: String, private val mapper: JsonMapper) {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    fun get(path: String, headers: Map<String, String> = emptyMap()) =
        send(HttpRequest.newBuilder(uri(path)).GET().apply { headers.forEach { (k, v) -> header(k, v) } })

    fun delete(path: String) = send(HttpRequest.newBuilder(uri(path)).DELETE())

    fun post(path: String, body: Any? = null, headers: Map<String, String> = emptyMap()) =
        postRaw(path, body?.let { mapper.writeValueAsString(it) } ?: "", headers)

    fun postRaw(path: String, rawBody: String, headers: Map<String, String> = emptyMap()): ApiResponse {
        val builder = HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rawBody))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder)
    }

    private fun uri(path: String) = URI.create(baseUrl + path)

    private fun send(builder: HttpRequest.Builder): ApiResponse {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return ApiResponse(response.statusCode(), response.body(), response.headers().map(), mapper)
    }
}
