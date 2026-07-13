package eu.kennytv.dependencymanager.resolve

import eu.kennytv.dependencymanager.model.UpdateType
import org.apache.maven.artifact.versioning.ComparableVersion

object Versions {

    private val UNSTABLE_TOKEN =
        Regex("(?i)^(alpha|beta|rc|cr|m|milestone|b|ea|eap|pre|preview|dev|snapshot|canary)\\d*$")
    private val NUMERIC_PREFIX = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")

    fun normalize(version: String): String = version.removePrefix("v").removePrefix("V")

    fun compare(a: String, b: String): Int = ComparableVersion(normalize(a)).compareTo(ComparableVersion(normalize(b)))

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun max(versions: Collection<String>): String? = versions.maxWithOrNull { a, b -> compare(a, b) }

    // Qualifiers like Final/GA/jre count as stable, alpha/beta/rc/snapshot/etc. don't.
    fun isStable(version: String): Boolean =
        version.split('.', '-', '_', '+').none { token -> UNSTABLE_TOKEN.matches(token.trim()) }

    // Unstable ones are only shown when the current version is itself unstable
    fun isAcceptableStability(candidate: String, current: String): Boolean =
        isStable(candidate) || !isStable(current)

    fun majorOf(version: String): Int? = NUMERIC_PREFIX.find(normalize(version))?.groupValues?.get(1)?.toIntOrNull()

    fun updateType(current: String, new: String): UpdateType {
        val c = NUMERIC_PREFIX.find(normalize(current)) ?: return UpdateType.OTHER
        val n = NUMERIC_PREFIX.find(normalize(new)) ?: return UpdateType.OTHER
        fun part(m: MatchResult, i: Int) = m.groupValues[i].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return when {
            part(c, 1) != part(n, 1) -> UpdateType.MAJOR
            part(c, 2) != part(n, 2) -> UpdateType.MINOR
            part(c, 3) != part(n, 3) -> UpdateType.PATCH
            else -> UpdateType.OTHER
        }
    }

    // Range/dynamic declarations like `[3.0.0,4.0.0)` or `1.+`
    fun isDynamic(version: String): Boolean =
        version.startsWith("[") || version.startsWith("(") || version.contains(',') ||
            version.endsWith("+") || version.contains("latest", ignoreCase = true)
}
