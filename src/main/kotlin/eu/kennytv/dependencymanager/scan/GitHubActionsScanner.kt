package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.ActionRefType
import eu.kennytv.dependencymanager.model.FileLocation
import eu.kennytv.dependencymanager.model.ScannedDependency

/**
 * Finds `uses:` refs in workflow files, in all three pinning styles: commit SHA with a
 * trailing `# v7.0.0` comment, full tag (`@v5.5.0`), and moving major (`@v5`).
 */
class GitHubActionsScanner : FileScanner {

    override fun matches(relativePath: String): Boolean =
        relativePath.startsWith(".github/workflows/") &&
            (relativePath.endsWith(".yml") || relativePath.endsWith(".yaml"))

    override fun scan(relativePath: String, content: String): List<ScannedDependency> {
        val result = mutableListOf<ScannedDependency>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.trimStart().startsWith("#")) return@forEachIndexed
            val match = USES_LINE.find(line) ?: return@forEachIndexed
            val (owner, repo, subPathRaw, ref, comment) = match.destructured
            if (owner == "." || owner == "..") return@forEachIndexed

            val refType = when {
                SHA.matches(ref) -> ActionRefType.SHA
                MOVING_MAJOR.matches(ref) -> ActionRefType.MOVING_MAJOR
                FULL_VERSION.matches(ref) -> ActionRefType.FULL_TAG
                else -> return@forEachIndexed // branch ref like @main, nothing to compare
            }
            val commentVersion = comment.takeIf { it.isNotEmpty() && FULL_VERSION.matches(it) }
            result += ScannedDependency.GitHubAction(
                owner = owner,
                repo = repo,
                subPath = subPathRaw.takeIf { it.isNotEmpty() }?.removePrefix("/"),
                ref = ref,
                refType = refType,
                commentVersion = commentVersion,
                locations = listOf(FileLocation(relativePath, index)),
            )
        }
        return result
    }

    companion object {
        private val USES_LINE = Regex(
            """^\s*(?:-\s+)?uses:\s*["']?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)((?:/[A-Za-z0-9_./-]+)?)@([A-Za-z0-9_./-]+)["']?\s*(?:#\s*(\S+))?"""
        )
        private val SHA = Regex("^[0-9a-f]{40}$")
        private val MOVING_MAJOR = Regex("^v?\\d+$")
        private val FULL_VERSION = Regex("^v?\\d+(\\.\\d+)+.*$")
    }
}
