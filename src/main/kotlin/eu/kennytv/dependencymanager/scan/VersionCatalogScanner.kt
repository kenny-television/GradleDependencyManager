package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.DeclarationKind
import eu.kennytv.dependencymanager.model.FileLocation
import eu.kennytv.dependencymanager.model.ModuleCoordinate
import eu.kennytv.dependencymanager.model.ScannedDependency

/**
 * Version catalogs (libs.versions.toml). Each `[versions]` key becomes one dependency
 * carrying every library/plugin that references it, so shared refs update together;
 * inline literal versions become their own entries.
 */
class VersionCatalogScanner : FileScanner {

    override fun matches(relativePath: String): Boolean = relativePath.endsWith(".versions.toml")

    override fun scan(relativePath: String, content: String): List<ScannedDependency> {
        data class VersionEntry(val version: String, val line: Int)

        val versions = linkedMapOf<String, VersionEntry>()
        val refModules = linkedMapOf<String, MutableList<ModuleCoordinate>>()
        val refPlugins = linkedMapOf<String, MutableList<String>>()
        val inlineItems = mutableListOf<ScannedDependency>()

        var section = ""
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) return@forEachIndexed
            SECTION.matchEntire(trimmed)?.let {
                section = it.groupValues[1]
                return@forEachIndexed
            }

            when (section) {
                "versions" -> {
                    val match = VERSION_ENTRY.matchEntire(trimmed) ?: return@forEachIndexed
                    versions[match.groupValues[1]] = VersionEntry(match.groupValues[2], index)
                }

                "libraries" -> {
                    val module = moduleOf(trimmed) ?: return@forEachIndexed
                    val ref = VERSION_REF.find(trimmed)?.groupValues?.get(1)
                    if (ref != null) {
                        refModules.getOrPut(ref) { mutableListOf() } += module
                    } else {
                        val inline = INLINE_VERSION.find(trimmed)?.groupValues?.get(1)
                            ?: STRING_NOTATION_VERSION.find(trimmed)?.groupValues?.get(1)
                            ?: return@forEachIndexed // BOM-managed or rich version, nothing to update
                        inlineItems += ScannedDependency.MavenDependency(
                            modules = listOf(module),
                            currentVersion = inline,
                            versionRefKey = null,
                            pluginIds = emptyList(),
                            declaration = DeclarationKind.CATALOG,
                            locations = listOf(FileLocation(relativePath, index)),
                        )
                    }
                }

                "plugins" -> {
                    val id = PLUGIN_ID.find(trimmed)?.groupValues?.get(1) ?: return@forEachIndexed
                    val ref = VERSION_REF.find(trimmed)?.groupValues?.get(1)
                    if (ref != null) {
                        refPlugins.getOrPut(ref) { mutableListOf() } += id
                    } else {
                        val inline = INLINE_VERSION.find(trimmed)?.groupValues?.get(1) ?: return@forEachIndexed
                        inlineItems += ScannedDependency.MavenDependency(
                            modules = listOf(ModuleCoordinate(id, "$id.gradle.plugin")),
                            currentVersion = inline,
                            versionRefKey = null,
                            pluginIds = listOf(id),
                            declaration = DeclarationKind.CATALOG,
                            locations = listOf(FileLocation(relativePath, index)),
                        )
                    }
                }
            }
        }

        val refItems = versions.map { (key, entry) ->
            val plugins = refPlugins[key].orEmpty()
            ScannedDependency.MavenDependency(
                modules = refModules[key].orEmpty() + plugins.map { ModuleCoordinate(it, "$it.gradle.plugin") },
                currentVersion = entry.version,
                versionRefKey = key,
                pluginIds = plugins,
                declaration = DeclarationKind.CATALOG,
                locations = listOf(FileLocation(relativePath, entry.line)),
            )
        }.filter { it.modules.isNotEmpty() } // unreferenced version keys

        return refItems + inlineItems
    }

    private fun moduleOf(line: String): ModuleCoordinate? {
        MODULE_NOTATION.find(line)?.let {
            val (group, name) = it.destructured
            return ModuleCoordinate(group, name)
        }
        val group = GROUP_FIELD.find(line)?.groupValues?.get(1)
        val name = NAME_FIELD.find(line)?.groupValues?.get(1)
        if (group != null && name != null) return ModuleCoordinate(group, name)
        STRING_NOTATION.find(line)?.let {
            val (g, n) = it.destructured
            return ModuleCoordinate(g, n)
        }
        return null
    }

    companion object {
        private val SECTION = Regex("""\[([A-Za-z]+)]""")
        private val VERSION_ENTRY = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]+)".*""")
        private val VERSION_REF = Regex("""version\.ref\s*=\s*"([^"]+)"""")

        // \bversion won't match "version.ref" here because of the required = after optional spaces
        private val INLINE_VERSION = Regex("""[,{\s]version\s*=\s*"([^"]+)"""")
        private val MODULE_NOTATION = Regex("""module\s*=\s*"([^":]+):([^":]+)"""")
        private val GROUP_FIELD = Regex("""group\s*=\s*"([^"]+)"""")
        private val NAME_FIELD = Regex("""name\s*=\s*"([^"]+)"""")
        private val PLUGIN_ID = Regex("""id\s*=\s*"([^"]+)"""")

        // someAlias = "group:name:version"
        private val STRING_NOTATION = Regex("""=\s*"([^":]+):([^":]+)(?::[^"]+)?"""")
        private val STRING_NOTATION_VERSION = Regex("""=\s*"[^":]+:[^":]+:([^"]+)"""")
    }
}
