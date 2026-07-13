package eu.kennytv.dependencymanager.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class Http {

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Response body, or null on non-200 or transport error. */
    fun get(url: String, vararg headers: String): String? = try {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "DependencyManager-IntelliJ-Plugin")
        if (headers.isNotEmpty()) {
            builder.headers(*headers)
        }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null
    } catch (_: Exception) {
        null
    }
}
