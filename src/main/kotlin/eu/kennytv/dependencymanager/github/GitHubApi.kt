package eu.kennytv.dependencymanager.github

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kennytv.dependencymanager.http.Http
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val body: String?,
    val htmlUrl: String?,
    val prerelease: Boolean,
)

class GitHubApi(private val http: Http) {

    // GITHUB_TOKEN / GH_TOKEN, falling back to the gh CLI's stored token
    val token: String? by lazy {
        System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
            ?: System.getenv("GH_TOKEN")?.takeIf { it.isNotBlank() }
            ?: ghCliToken()
    }

    private fun ghCliToken(): String? = try {
        val process = ProcessBuilder("gh", "auth", "token")
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isNotEmpty()) output else null
    } catch (_: Exception) {
        null
    }

    private fun get(path: String): String? {
        val headers = mutableListOf("Accept", "application/vnd.github+json")
        token?.let {
            headers += "Authorization"
            headers += "Bearer $it"
        }
        return http.get("https://api.github.com$path", *headers.toTypedArray())
    }

    fun releases(owner: String, repo: String, perPage: Int = 50): List<GitHubRelease> {
        val body = get("/repos/$owner/$repo/releases?per_page=$perPage") ?: return emptyList()
        return try {
            JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                if (obj.get("draft")?.asBoolean == true) return@mapNotNull null
                GitHubRelease(
                    tagName = obj.get("tag_name")?.asString ?: return@mapNotNull null,
                    name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString,
                    body = obj.get("body")?.takeIf { !it.isJsonNull }?.asString,
                    htmlUrl = obj.get("html_url")?.takeIf { !it.isJsonNull }?.asString,
                    prerelease = obj.get("prerelease")?.asBoolean ?: false,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun tags(owner: String, repo: String, perPage: Int = 100): List<String> {
        val body = get("/repos/$owner/$repo/tags?per_page=$perPage") ?: return emptyList()
        return try {
            JsonParser.parseString(body).asJsonArray.mapNotNull {
                (it as? JsonObject ?: it.asJsonObject).get("name")?.asString
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Annotated tags need a second lookup to get from the tag object to the commit.
    fun resolveTagToCommitSha(owner: String, repo: String, tag: String): String? {
        val refBody = get("/repos/$owner/$repo/git/ref/tags/$tag") ?: return null
        return try {
            val obj = JsonParser.parseString(refBody).asJsonObject.getAsJsonObject("object")
            val sha = obj.get("sha").asString
            if (obj.get("type").asString == "tag") {
                val tagBody = get("/repos/$owner/$repo/git/tags/$sha") ?: return null
                JsonParser.parseString(tagBody).asJsonObject.getAsJsonObject("object").get("sha").asString
            } else {
                sha
            }
        } catch (_: Exception) {
            null
        }
    }
}
