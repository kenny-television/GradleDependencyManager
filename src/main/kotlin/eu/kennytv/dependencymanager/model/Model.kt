package eu.kennytv.dependencymanager.model

data class FileLocation(val relativePath: String, val line: Int)

enum class UpdateType { MAJOR, MINOR, PATCH, OTHER }

enum class ActionRefType { SHA, FULL_TAG, MOVING_MAJOR }

data class ModuleCoordinate(val group: String, val name: String) {
    override fun toString(): String = "$group:$name"
}

enum class DeclarationKind { INLINE, PLUGIN_BLOCK, CATALOG }

sealed class ScannedDependency {
    abstract val displayName: String
    abstract val currentVersion: String
    abstract val locations: List<FileLocation>

    /** Identifiers that ignore rule globs are matched against. */
    abstract val matchKeys: List<String>
    abstract val groupLabel: String

    data class GitHubAction(
        val owner: String,
        val repo: String,
        /** "setup-gradle" in gradle/actions/setup-gradle */
        val subPath: String?,
        /** as written in the file: commit SHA, full tag, or moving major like v5 */
        val ref: String,
        val refType: ActionRefType,
        /** version from a trailing "# v7.0.0" comment next to a SHA pin */
        val commentVersion: String?,
        override val locations: List<FileLocation>,
    ) : ScannedDependency() {
        val slug: String get() = "$owner/$repo" + (subPath?.let { "/$it" } ?: "")
        override val displayName: String get() = slug
        override val currentVersion: String get() = commentVersion ?: ref
        override val matchKeys: List<String> get() = listOf(slug, "$owner/$repo", "actions:$owner/$repo").distinct()
        override val groupLabel: String get() = "GitHub Actions"
    }

    data class MavenDependency(
        /** more than one when a catalog version ref is shared by several libraries */
        val modules: List<ModuleCoordinate>,
        override val currentVersion: String,
        /** key in the catalog's [versions] section, when declared there */
        val versionRefKey: String?,
        val pluginIds: List<String>,
        val declaration: DeclarationKind,
        override val locations: List<FileLocation>,
    ) : ScannedDependency() {
        override val displayName: String
            get() = when {
                versionRefKey != null && modules.size + pluginIds.size > 1 ->
                    "$versionRefKey (${(modules.map { it.toString() } + pluginIds).joinToString(", ")})"

                modules.isNotEmpty() -> modules[0].toString()
                else -> pluginIds.joinToString(", ")
            }
        override val matchKeys: List<String>
            get() = buildList {
                for (m in modules) {
                    add(m.toString())
                    add("maven:$m")
                    add(m.name)
                }
                for (id in pluginIds) {
                    add(id)
                    add("plugin:$id")
                }
                versionRefKey?.let { add("ref:$it") }
            }.distinct()
        override val groupLabel: String
            get() = when (declaration) {
                DeclarationKind.CATALOG -> "Version Catalog"
                DeclarationKind.PLUGIN_BLOCK -> "Gradle Plugins"
                DeclarationKind.INLINE -> "Gradle Dependencies"
            }
    }

    data class GradleWrapper(
        override val currentVersion: String,
        /** "bin" or "all" */
        val distType: String,
        /** line of the distributionSha256Sum entry, if the wrapper pins a checksum */
        val checksumLine: Int?,
        val location: FileLocation,
    ) : ScannedDependency() {
        override val displayName: String get() = "Gradle wrapper"
        override val locations: List<FileLocation> get() = listOf(location)
        override val matchKeys: List<String> get() = listOf("gradle", "gradle-wrapper", "wrapper")
        override val groupLabel: String get() = "Gradle Wrapper"
    }
}

data class UpdateCandidate(
    val dependency: ScannedDependency,
    /** display version; for actions this is the tag, not necessarily what gets written */
    val newVersion: String,
    /** what gets written as the action ref: commit SHA, tag, or moving major */
    val newRef: String? = null,
    val updateType: UpdateType,
    /** new distribution sha256 for wrapper updates that pin a checksum */
    val checksum: String? = null,
    /** "owner/name" when known, for changelog lookup */
    val githubRepo: String? = null,
)

data class SkippedDependency(val dependency: ScannedDependency, val reason: String)

data class CheckResult(
    val updates: List<UpdateCandidate>,
    val upToDateCount: Int,
    val skipped: List<SkippedDependency>,
    val errors: List<String>,
    val timestamp: Long,
)

data class Changelog(
    /** rendered HTML, or null when no release notes were found */
    val html: String?,
    val linkUrl: String?,
    val linkLabel: String?,
)
