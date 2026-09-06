package eu.kennytv.dependencymanager.resolve

import com.google.gson.JsonParser
import eu.kennytv.dependencymanager.github.GitHubApi
import eu.kennytv.dependencymanager.github.GitHubRelease
import eu.kennytv.dependencymanager.http.Http
import eu.kennytv.dependencymanager.ignore.IgnoreRules
import eu.kennytv.dependencymanager.model.ActionRefType
import eu.kennytv.dependencymanager.model.DependencyNote
import eu.kennytv.dependencymanager.model.NoteLevel
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.model.SkippedDependency
import eu.kennytv.dependencymanager.model.UpdateCandidate

sealed class Resolution {
    data class Update(val candidate: UpdateCandidate) : Resolution()
    object UpToDate : Resolution()
    data class Skipped(val skipped: SkippedDependency) : Resolution()
    data class Error(val message: String) : Resolution()
}

/** A resolution plus advice that is worth showing even when nothing needs updating. */
data class ResolveOutcome(val resolution: Resolution, val note: DependencyNote? = null)

class UpdateResolver(
    private val http: Http,
    private val github: GitHubApi,
    private val repositories: List<String>,
    private val ignoreRules: IgnoreRules,
) {

    fun resolve(dependency: ScannedDependency): ResolveOutcome {
        if (ignoreRules.isFullyIgnored(dependency)) {
            return ResolveOutcome(Resolution.Skipped(SkippedDependency(dependency, "ignored by rule")))
        }
        return when (dependency) {
            is ScannedDependency.GitHubAction -> resolveAction(dependency)
            is ScannedDependency.MavenDependency -> ResolveOutcome(resolveMaven(dependency))
            is ScannedDependency.GradleWrapper -> ResolveOutcome(resolveWrapper(dependency))
        }
    }

    private fun resolveAction(dep: ScannedDependency.GitHubAction): ResolveOutcome {
        if (dep.refType == ActionRefType.SHA && dep.commentVersion == null) {
            val reason = "SHA-pinned without a version comment; cannot tell what is currently used"
            return ResolveOutcome(Resolution.Skipped(SkippedDependency(dep, reason)))
        }

        val releases = github.releases(dep.owner, dep.repo)
        val stable = releases.filter { !it.prerelease }
        val tagNames = stable.map { it.tagName }.ifEmpty { github.tags(dep.owner, dep.repo) }
        if (tagNames.isEmpty()) {
            val message = "${dep.slug}: could not list releases/tags (rate limit or unknown repo)"
            return ResolveOutcome(Resolution.Error(message))
        }
        return ResolveOutcome(resolveActionVersion(dep, tagNames), pinningNote(dep, releases))
    }

    private fun resolveActionVersion(dep: ScannedDependency.GitHubAction, tagNames: List<String>): Resolution {
        val current = dep.currentVersion
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

    /**
     * An immutable release freezes its tag, which makes referencing it by tag as tamper-proof as
     * a commit SHA. So warn about tag refs that can still be moved, and point out SHA pins that
     * the immutability of their release already makes unnecessary.
     */
    private fun pinningNote(dep: ScannedDependency.GitHubAction, releases: List<GitHubRelease>): DependencyNote? {
        val pinned = dep.refType == ActionRefType.SHA
        val tag = (if (pinned) dep.commentVersion else dep.ref) ?: return null
        val immutable = isImmutableRelease(dep, tag, releases)
        val mutableRefReason = if (dep.refType == ActionRefType.MOVING_MAJOR) {
            "follows a major tag that the publisher moves onto every new ${dep.ref}.x release"
        } else {
            "uses a tag that the publisher can still move or delete (the <b>$tag</b> release is not immutable)"
        }
        return when {
            pinned && immutable -> DependencyNote(
                dep,
                NoteLevel.INFO,
                "$tag is an immutable release, the SHA pin is optional",
                "<p><code>${dep.slug}</code> is pinned to a commit SHA, but the <b>$tag</b> release is immutable: " +
                    "its tag can no longer be moved or deleted, so <code>${dep.slug}@$tag</code> resolves to the " +
                    "same code either way. Keeping the SHA does no harm, it just is not needed for this version.</p>",
            )

            !pinned && !immutable -> DependencyNote(
                dep,
                NoteLevel.WARNING,
                "@${dep.ref} is not pinned and can move",
                "<p><code>${dep.slug}@${dep.ref}</code> $mutableRefReason, so the code running in the workflow " +
                    "can change without the ref changing. Pin it to the full commit SHA with a trailing " +
                    "<code># $tag</code> comment to freeze the exact code that runs.</p>",
            )

            else -> null
        }
    }

    /** Whether the release published for [tag] is immutable; false when the tag has no release. */
    private fun isImmutableRelease(
        dep: ScannedDependency.GitHubAction,
        tag: String,
        releases: List<GitHubRelease>,
    ): Boolean {
        releases.firstOrNull { Versions.normalize(it.tagName) == Versions.normalize(tag) }
            ?.let { return it.immutable }
        // Without any release, the ref can only point at a plain tag; otherwise the used version
        // may just be older than the page of releases fetched above.
        if (releases.isEmpty()) return false
        return github.releaseByTag(dep.owner, dep.repo, tag)?.immutable ?: false
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
