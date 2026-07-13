package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.DeclarationKind
import eu.kennytv.dependencymanager.model.FileLocation
import eu.kennytv.dependencymanager.model.ModuleCoordinate
import eu.kennytv.dependencymanager.model.ScannedDependency

/**
 * Resolves dependencies declared via a variable `implementation("io.netty:netty-all:${'$'}{nettyVersion}")` (`val`/`def`/`ext` or a gradle.properties entry).
 */
class VariableVersionScanner {

    private data class Usage(val module: ModuleCoordinate, val variable: String, val filePath: String)
    private data class Definition(
        val variable: String,
        val version: String,
        val location: FileLocation,
        /** lower wins: 1 build script, 2 root gradle.properties, 3 other gradle.properties */
        val precedence: Int,
    )

    private val usages = mutableListOf<Usage>()
    private val definitions = mutableListOf<Definition>()

    fun collectBuildScript(relativePath: String, content: String) {
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("#")) return@forEachIndexed

            for (match in USAGE.findAll(line)) {
                usages += Usage(
                    ModuleCoordinate(match.groupValues[1], match.groupValues[2]),
                    match.groupValues[3],
                    relativePath
                )
            }
            for (regex in DEFINITION_PATTERNS) {
                val match = regex.find(line) ?: continue
                val version = match.groupValues[2]
                if (version.isNotEmpty() && version.first().isDigit()) {
                    definitions += Definition(
                        match.groupValues[1],
                        version,
                        FileLocation(relativePath, index),
                        precedence = 1
                    )
                }
            }
        }
    }

    fun collectProperties(relativePath: String, content: String) {
        val precedence = if (relativePath == "gradle.properties") 2 else 3
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.startsWith("#") || line.startsWith("!")) return@forEachIndexed
            val match = PROPERTY.matchEntire(line) ?: return@forEachIndexed
            val version = match.groupValues[2].trim()
            if (version.isNotEmpty() && version.first().isDigit()) {
                definitions += Definition(match.groupValues[1], version, FileLocation(relativePath, index), precedence)
            }
        }
    }

    fun resolvedDependencies(): List<ScannedDependency> =
        usages.groupBy { it.variable }.mapNotNull { (variable, uses) ->
            val candidates = definitions.filter { it.variable == variable }
            if (candidates.isEmpty()) return@mapNotNull null
            // A definition in the file wins over project-wide ones
            val definition = candidates.firstOrNull { def -> uses.any { it.filePath == def.location.relativePath } }
                ?: candidates.minBy { it.precedence }
            ScannedDependency.MavenDependency(
                modules = uses.map { it.module }.distinct(),
                currentVersion = definition.version,
                versionRefKey = variable,
                pluginIds = emptyList(),
                declaration = DeclarationKind.INLINE,
                locations = listOf(definition.location),
            )
        }

    companion object {
        // "group:artifact:$var" or "group:artifact:${var}"; [$] avoids Kotlin template escapes
        private val USAGE = Regex(""""([A-Za-z][\w-]*(?:\.[\w-]+)+):([A-Za-z0-9_.-]+):[$]\{?([A-Za-z_]\w*)}?"""")
        private val DEFINITION_PATTERNS = listOf(
            // Kotlin DSL: val nettyVersion = "4.1.100"
            Regex("""^\s*(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*"([^"$]+)""""),
            // Groovy DSL: def nettyVersion = '4.1.100' / ext.nettyVersion = "4.1.100"
            Regex("""^\s*(?:def\s+|ext\.)([A-Za-z_]\w*)\s*=\s*["']([^"'$]+)["']"""),
            // Kotlin DSL extra properties: extra["nettyVersion"] = "4.1.100"
            Regex("""extra\["([A-Za-z_]\w*)"]\s*=\s*"([^"$]+)""""),
        )
        private val PROPERTY = Regex("""([A-Za-z_][\w.]*)\s*=\s*(.+)""")
    }
}
