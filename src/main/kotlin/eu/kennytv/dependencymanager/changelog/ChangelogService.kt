package eu.kennytv.dependencymanager.changelog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kennytv.dependencymanager.github.GitHubApi
import eu.kennytv.dependencymanager.http.Http
import eu.kennytv.dependencymanager.model.Changelog
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.model.UpdateCandidate
import eu.kennytv.dependencymanager.resolve.Versions
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches GitHub release notes for an update; for maven artifacts the repo is discovered
 * through the POM's scm/url metadata. Successful lookups are cached on disk, misses aren't.
 */
class ChangelogService(
    private val http: Http,
    private val github: GitHubApi,
    private val repositories: List<String>,
    private val cacheDir: Path? = null,
) {

    private val cache = ConcurrentHashMap<String, Changelog>()

    fun fetch(candidate: UpdateCandidate): Changelog {
        val key =
            "${candidate.dependency.matchKeys.firstOrNull()}|${candidate.dependency.currentVersion}|${candidate.newVersion}"
        return cache.getOrPut(key) {
            readDiskCache(key) ?: run {
                val result = try {
                    doFetch(candidate)
                } catch (e: Exception) {
                    Changelog(null, null, "Failed to load changelog: ${e.message}")
                }
                if (result.html != null) writeDiskCache(key, result)
                result
            }
        }
    }

    private fun cacheFile(key: String): Path? {
        val dir = cacheDir ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return dir.resolve(digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun readDiskCache(key: String): Changelog? = try {
        val file = cacheFile(key)
        if (file == null || !Files.exists(file)) null
        else {
            val obj = JsonParser.parseString(Files.readString(file)).asJsonObject
            if (System.currentTimeMillis() - obj.get("timestamp").asLong > CACHE_TTL_MILLIS) null
            else Changelog(
                html = obj.get("html")?.takeIf { !it.isJsonNull }?.asString,
                linkUrl = obj.get("linkUrl")?.takeIf { !it.isJsonNull }?.asString,
                linkLabel = obj.get("linkLabel")?.takeIf { !it.isJsonNull }?.asString,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun writeDiskCache(key: String, changelog: Changelog) {
        try {
            val file = cacheFile(key) ?: return
            Files.createDirectories(file.parent)
            val obj = JsonObject()
            obj.addProperty("timestamp", System.currentTimeMillis())
            obj.addProperty("html", changelog.html)
            obj.addProperty("linkUrl", changelog.linkUrl)
            obj.addProperty("linkLabel", changelog.linkLabel)
            Files.writeString(file, obj.toString())
        } catch (_: Exception) {
            // caching is best-effort
        }
    }

    private fun doFetch(candidate: UpdateCandidate): Changelog {
        val repo = candidate.githubRepo ?: discoverGitHubRepo(candidate)
        ?: return Changelog(null, null, "No GitHub repository could be derived for this artifact.")
        val (owner, name) = repo.split('/', limit = 2)

        val current = Versions.normalize(candidate.dependency.currentVersion)
        val new = Versions.normalize(candidate.newVersion)
        val releases = github.releases(owner, name)
        val relevant = releases.filter { release ->
            val version = Versions.normalize(tagVersion(release.tagName) ?: return@filter false)
            Versions.isNewer(version, current) && Versions.compare(version, new) <= 0
        }.ifEmpty {
            releases.filter { it.tagName.contains(new) }
        }

        val releasesUrl = "https://github.com/$owner/$name/releases"
        if (relevant.isEmpty()) {
            return Changelog(null, releasesUrl, "No release notes found - open releases page")
        }

        val markdown = buildString {
            for (release in relevant) {
                append("## ").append(release.name?.takeIf { it.isNotBlank() } ?: release.tagName).append("\n\n")
                val body = release.body?.takeIf { it.isNotBlank() } ?: "_No release notes._"
                // GitHub returns \r\n bodies, which break fenced code block parsing
                append(body.replace("\r\n", "\n")).append("\n\n")
            }
        }
        return Changelog(renderMarkdown(markdown), releasesUrl, "Open on GitHub")
    }

    private fun discoverGitHubRepo(candidate: UpdateCandidate): String? {
        val dep = candidate.dependency as? ScannedDependency.MavenDependency ?: return null
        val module = dep.modules.firstOrNull() ?: return null
        val path = "${
            module.group.replace(
                '.',
                '/'
            )
        }/${module.name}/${candidate.newVersion}/${module.name}-${candidate.newVersion}.pom"
        for (repo in repositories) {
            val pom = http.get("$repo/$path") ?: continue
            val match = GITHUB_URL.find(pom) ?: continue
            val owner = match.groupValues[1]
            val name = match.groupValues[2].removeSuffix(".git")
            return "$owner/$name"
        }
        return null
    }

    private fun tagVersion(tag: String): String? = TAG_VERSION.find(tag)?.value

    private fun renderMarkdown(markdown: String): String =
        HTML_RENDERER.render(MARKDOWN_PARSER.parse(markdown))

    companion object {
        private val MARKDOWN_EXTENSIONS = listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
        )
        private val MARKDOWN_PARSER = Parser.builder().extensions(MARKDOWN_EXTENSIONS).build()
        private val HTML_RENDERER = HtmlRenderer.builder().extensions(MARKDOWN_EXTENSIONS).build()
        private val GITHUB_URL = Regex("""github\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""")
        private val TAG_VERSION = Regex("""\d+(\.\d+)*([.-][A-Za-z0-9]+)*""")
        private val CACHE_TTL_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(7)
    }
}
