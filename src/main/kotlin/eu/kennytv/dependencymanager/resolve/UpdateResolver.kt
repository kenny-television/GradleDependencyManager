package eu.kennytv.dependencymanager.resolve

import com.google.gson.JsonParser
import eu.kennytv.dependencymanager.github.GitHubApi
import eu.kennytv.dependencymanager.http.Http
import eu.kennytv.dependencymanager.ignore.IgnoreRules
import eu.kennytv.dependencymanager.model.ActionRefType
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.model.SkippedDependency
import eu.kennytv.dependencymanager.model.UpdateCandidate

sealed class Resolution {
    data class Update(val candidate: UpdateCandidate) : Resolution()
    object UpToDate : Resolution()
    data class Skipped(val skipped: SkippedDependency) : Resolution()
    data class Error(val message: String) : Resolution()
}

class UpdateResolver(
    private val http: Http,
    private val github: GitHubApi,
    private val repositories: List<String>,
    private val ignoreRules: IgnoreRules,
) {

    fun resolve(dependency: ScannedDependency): Resolution {
        if (ignoreRules.isFullyIgnored(dependency)) {
            return Resolution.Skipped(SkippedDependency(dependency, "ignored by rule"))
        }
        return when (dependency) {
            is ScannedDependency.GitHubAction -> resolveAction(dependency)
            is ScannedDependency.MavenDependency -> resolveMaven(dependency)
            is ScannedDependency.GradleWrapper -> resolveWrapper(dependency)
        }
    }

    private fun resolveAction(dep: ScannedDependency.GitHubAction): Resolution {
        if (dep.refType == ActionRefType.SHA && dep.commentVersion == null) {
            return Resolution.Skipped(
                SkippedDependency(
                    dep,
                    "SHA-pinned without a version comment; cannot tell what is currently used"
                )
            )
        }
        val current = dep.currentVersion

        val releases = github.releases(dep.owner, dep.repo).filter { !it.prerelease }
        val tagNames = releases.map { it.tagName }.ifEmpty { github.tags(dep.owner, dep.repo) }
        if (tagNames.isEmpty()) {
            return Resolution.Error("${dep.slug}: could not list releases/tags (rate limit or unknown repo)")
        }

        val best = pickBest(dep, current, tagNames) ?: return Resolution.UpToDate
        if (dep.refType == ActionRefType.MOVING_MAJOR &&
            (Versions.majorOf(best) == null || Versions.majorOf(best) == Versions.majorOf(current))
        ) {
            return Resolution.UpToDate // moving majors only ever move on a new major release
        }

        val newRef = when (dep.refType) {
            ActionRefType.SHA -> github.resolveTagToCommitSha(dep.owner, dep.repo, best)
                ?: return Resolution.Error("${dep.slug}: could not resolve tag $best to a commit SHA")

            ActionRefType.FULL_TAG -> best
            ActionRefType.MOVING_MAJOR -> "v${Versions.majorOf(best)}"
        }
        return Resolution.Update(
            UpdateCandidate(
                dependency = dep,
                newVersion = best,
                newRef = newRef,
                updateType = Versions.updateType(current, best),
                githubRepo = "${dep.owner}/${dep.repo}",
            )
        )
    }

    private fun resolveMaven(dep: ScannedDependency.MavenDependency): Resolution {
        val current = dep.currentVersion
        if (Versions.isDynamic(current)) {
            return Resolution.Skipped(
                SkippedDependency(
                    dep,
                    "dynamic/range version \"$current\" is intentionally floating"
                )
            )
        }

        // A shared catalog ref can only move to a version that exists for all its modules
        var candidates: Set<String>? = null
        for (module in dep.modules) {
            val versions = mavenVersions(module.group, module.name)
                ?: return Resolution.Error("$module: no maven-metadata.xml found in any known repository")
            candidates = candidates?.intersect(versions) ?: versions
        }
        val best = pickBest(dep, current, candidates.orEmpty()) ?: return Resolution.UpToDate
        return Resolution.Update(
            UpdateCandidate(
                dependency = dep,
                newVersion = best,
                updateType = Versions.updateType(current, best),
            )
        )
    }

    private fun mavenVersions(group: String, name: String): Set<String>? {
        val path = "${group.replace('.', '/')}/$name/maven-metadata.xml"
        var found = false
        val versions = mutableSetOf<String>()
        for (repo in repositories) {
            val body = http.get("$repo/$path") ?: continue
            found = true
            VERSION_TAG.findAll(body).forEach { versions += it.groupValues[1] }
        }
        return if (found) versions else null
    }

    private fun resolveWrapper(dep: ScannedDependency.GradleWrapper): Resolution {
        val body = http.get("https://services.gradle.org/versions/current")
            ?: return Resolution.Error("Gradle wrapper: could not query services.gradle.org")
        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return Resolution.Error("Gradle wrapper: unexpected response from services.gradle.org")
        }
        val latest = json.get("version")?.asString
            ?: return Resolution.Error("Gradle wrapper: unexpected response from services.gradle.org")

        val best = pickBest(dep, dep.currentVersion, listOf(latest)) ?: return Resolution.UpToDate

        var checksum: String? = null
        if (dep.checksumLine != null) {
            val checksumUrl = "https://services.gradle.org/distributions/gradle-$latest-${dep.distType}.zip.sha256"
            checksum = http.get(checksumUrl)?.trim()?.takeIf { CHECKSUM.matches(it) }
                ?: return Resolution.Error("Gradle wrapper: could not fetch the distribution checksum for $latest")
        }
        return Resolution.Update(
            UpdateCandidate(
                dependency = dep,
                newVersion = best,
                updateType = Versions.updateType(dep.currentVersion, best),
                checksum = checksum,
                githubRepo = "gradle/gradle",
            )
        )
    }

    /** Highest candidate that is newer, matches the current stability level, and isn't ignored. */
    private fun pickBest(dep: ScannedDependency, current: String, candidates: Collection<String>): String? =
        candidates
            .filter { Versions.isNewer(it, current) }
            .filter { Versions.isAcceptableStability(it, current) }
            .filterNot { ignoreRules.isIgnored(dep, Versions.normalize(it), Versions.updateType(current, it)) }
            .let { Versions.max(it) }

    companion object {
        private val VERSION_TAG = Regex("<version>([^<]+)</version>")
        private val CHECKSUM = Regex("^[0-9a-f]{64}$")
    }
}
