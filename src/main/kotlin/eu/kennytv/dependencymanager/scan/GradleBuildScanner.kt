package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.DeclarationKind
import eu.kennytv.dependencymanager.model.FileLocation
import eu.kennytv.dependencymanager.model.ModuleCoordinate
import eu.kennytv.dependencymanager.model.ScannedDependency

/** Inline "group:artifact:version" strings and plugin block declarations in build scripts. */
class GradleBuildScanner : FileScanner {

    override fun matches(relativePath: String): Boolean =
        relativePath.endsWith(".gradle.kts") || relativePath.endsWith(".gradle")

    override fun scan(relativePath: String, content: String): List<ScannedDependency> {
        val result = mutableListOf<ScannedDependency>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) return@forEachIndexed
            val location = FileLocation(relativePath, index)

            for (match in INLINE_COORDINATE.findAll(line)) {
                val (group, name, version) = match.destructured
                if (!version.first().isDigit() && !version.startsWith("[") && !version.startsWith("(")) continue
                result += ScannedDependency.MavenDependency(
                    modules = listOf(ModuleCoordinate(group, name)),
                    currentVersion = version,
                    versionRefKey = null,
                    pluginIds = emptyList(),
                    declaration = DeclarationKind.INLINE,
                    locations = listOf(location),
                )
            }

            for (match in PLUGIN_DECLARATION.findAll(line)) {
                val (id, version) = match.destructured
                result += pluginDependency(id, version, location)
            }
            for (match in KOTLIN_PLUGIN_DECLARATION.findAll(line)) {
                val (shortName, version) = match.destructured
                result += pluginDependency("org.jetbrains.kotlin.$shortName", version, location)
            }
        }
        return result
    }

    private fun pluginDependency(id: String, version: String, location: FileLocation) =
        ScannedDependency.MavenDependency(
            // Plugin marker artifact
            modules = listOf(ModuleCoordinate(id, "$id.gradle.plugin")),
            currentVersion = version,
            versionRefKey = null,
            pluginIds = listOf(id),
            declaration = DeclarationKind.PLUGIN_BLOCK,
            locations = listOf(location),
        )

    companion object {
        // Version part must not contain interpolation ($) or quotes; group must contain a dot
        // to weed out non-coordinate strings like "a:b:c" markers.
        private val INLINE_COORDINATE =
            Regex(""""([A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_-]+)+):([A-Za-z0-9_.-]+):([^"$\s]+)"""")
        private val PLUGIN_DECLARATION =
            Regex("""id\s*\(\s*"([A-Za-z0-9_.-]+)"\s*\)\s+version\s+"([^"$]+)"""")
        private val KOTLIN_PLUGIN_DECLARATION =
            Regex("""kotlin\s*\(\s*"([A-Za-z0-9-]+)"\s*\)\s+version\s+"([^"$]+)"""")
    }
}
