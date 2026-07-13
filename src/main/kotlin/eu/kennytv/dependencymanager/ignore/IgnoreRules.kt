package eu.kennytv.dependencymanager.ignore

import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.model.UpdateType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * An ignore rule. With only [dependency] set the dependency is ignored
 * entirely; [versions] hides matching versions, [type] hides updates of that kind.
 */
data class IgnoreRule(
    /** glob matched against the dependency's match keys */
    val dependency: String,
    val versions: List<String> = emptyList(),
    val type: UpdateType? = null,
) {
    fun matches(dep: ScannedDependency): Boolean {
        val regex = globToRegex(dependency)
        return dep.matchKeys.any { regex.matches(it) }
    }

    fun ignoresCandidate(candidateVersion: String, updateType: UpdateType): Boolean {
        if (versions.isEmpty() && type == null) return true // whole dependency ignored
        if (versions.any { globToRegex(it).matches(candidateVersion) }) return true
        return type != null && type == updateType
    }

    companion object {
        private fun globToRegex(glob: String): Regex =
            Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    }
}

class IgnoreRules(val rules: List<IgnoreRule>) {

    fun isIgnored(dep: ScannedDependency, candidateVersion: String, updateType: UpdateType): Boolean =
        rules.any { it.matches(dep) && it.ignoresCandidate(candidateVersion, updateType) }

    /** Ignored outright, with no version/type filter. */
    fun isFullyIgnored(dep: ScannedDependency): Boolean =
        rules.any { it.matches(dep) && it.versions.isEmpty() && it.type == null }

    companion object {
        const val FILE_NAME = ".dependency-updates.toml"

        private val ARRAY_START = Regex("""(?m)^ignore\s*=\s*\[""")
        private val BLOCK_KEY_VALUE = Regex("""^([A-Za-z]+)\s*=\s*(.+?)\s*(?:#.*)?$""")
        private val DEPENDENCY_FIELD = Regex("""dependency\s*=\s*"([^"]+)"""")
        private val VERSIONS_FIELD = Regex("""versions\s*=\s*\[([^]]*)]""")
        private val TYPE_FIELD = Regex("""type\s*=\s*"([^"]+)"""")

        val FILE_HEADER = """
            |# Ignore rules for dependency updates (dependabot-style), managed by the Dependency
            |# Manager plugin. Elements are either dependency globs (matched against group:artifact
            |# coordinates, action owner/repo slugs, plugin ids, or "gradle" for the wrapper), or
            |# tables restricting only some updates:
            |#   "com.google.guava:guava"                            ignore entirely
            |#   { dependency = "io.netty:*", versions = ["4.1.*"] } never offer matching versions
            |#   { dependency = "actions/checkout", type = "major" } keep minor/patch, hide majors
            |""".trimMargin()

        // Project root by default (committable like a dependabot.yml), but an existing
        // file under .idea/ is honored for those who want the root kept clean.
        fun filePath(root: Path): Path {
            val rootFile = root.resolve(FILE_NAME)
            if (Files.exists(rootFile)) return rootFile
            val ideaFile = root.resolve(".idea").resolve(FILE_NAME)
            if (Files.exists(ideaFile)) return ideaFile
            return rootFile
        }

        fun load(root: Path): IgnoreRules {
            val file = filePath(root)
            if (!Files.exists(file)) return IgnoreRules(emptyList())
            return IgnoreRules(parse(Files.readString(file)))
        }

        fun parse(text: String): List<IgnoreRule> = parseIgnoreArray(text) + parseLegacyBlocks(text)

        /** Appends the rule; returns false if an identical one already exists. */
        fun append(root: Path, rule: IgnoreRule): Boolean {
            val file = filePath(root)
            val text = if (Files.exists(file)) Files.readString(file) else null
            if (text != null && rule in parse(text)) return false

            val newText = when {
                text == null -> FILE_HEADER + "ignore = [\n    ${formatElement(rule)},\n]\n"
                // Legacy array-of-tables file: a top-level `ignore` key after a [[ignore]]
                // header would be invalid TOML, so stay in block style there.
                text.contains("[[ignore]]") -> text + formatLegacyBlock(rule)
                ARRAY_START.containsMatchIn(text) -> insertIntoArray(text, rule)
                else -> text + "\nignore = [\n    ${formatElement(rule)},\n]\n"
            }
            Files.writeString(
                file,
                newText,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            return true
        }

        private fun parseIgnoreArray(text: String): List<IgnoreRule> {
            val open = (ARRAY_START.find(text) ?: return emptyList()).range.last
            val close = findMatchingBracket(text, open) ?: return emptyList()
            return splitTopLevel(text.substring(open + 1, close)).mapNotNull { parseElement(it.trim()) }
        }

        private fun parseElement(element: String): IgnoreRule? {
            if (element.startsWith("\"")) {
                return IgnoreRule(element.removeSurrounding("\""))
            }
            if (!element.startsWith("{")) return null
            val dependency = DEPENDENCY_FIELD.find(element)?.groupValues?.get(1) ?: return null
            val versions = VERSIONS_FIELD.find(element)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().removeSurrounding("\"") }?.filter { it.isNotEmpty() }
                .orEmpty()
            val type = TYPE_FIELD.find(element)?.groupValues?.get(1)
                ?.let { runCatching { UpdateType.valueOf(it.uppercase()) }.getOrNull() }
            return IgnoreRule(dependency, versions, type)
        }

        private fun formatElement(rule: IgnoreRule): String {
            if (rule.versions.isEmpty() && rule.type == null) return "\"${rule.dependency}\""
            return buildString {
                append("{ dependency = \"").append(rule.dependency).append("\"")
                if (rule.versions.isNotEmpty()) {
                    append(", versions = [").append(rule.versions.joinToString(", ") { "\"$it\"" }).append("]")
                }
                rule.type?.let { append(", type = \"").append(it.name.lowercase()).append("\"") }
                append(" }")
            }
        }

        private fun insertIntoArray(text: String, rule: IgnoreRule): String {
            val open = ARRAY_START.find(text)!!.range.last
            val close = findMatchingBracket(text, open)
                ?: return text + "\n# (could not insert rule: unterminated ignore array)\n"
            val element = formatElement(rule)
            if (text.substring(open + 1, close).isBlank()) {
                return text.replaceRange(open..close, "[\n    $element,\n]")
            }
            // insert after the last element so trailing comments before ] stay put
            var lastContent = close - 1
            while (lastContent > open && text[lastContent].isWhitespace()) lastContent--
            val comma = if (text[lastContent] == ',') "" else ","
            return text.take(lastContent + 1) + comma + "\n    $element," + text.substring(lastContent + 1)
        }

        // Index of the `]` closing the bracket at [open]; brackets in strings and comments don't count.
        private fun findMatchingBracket(text: String, open: Int): Int? {
            var depth = 0
            var index = open
            while (index < text.length) {
                when (text[index]) {
                    '"' -> {
                        index++
                        while (index < text.length && text[index] != '"') {
                            if (text[index] == '\\') index++
                            index++
                        }
                    }

                    '#' -> {
                        while (index < text.length && text[index] != '\n') index++
                        continue
                    }

                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
                index++
            }
            return null
        }

        // Splits on commas not nested in strings, tables, or arrays.
        private fun splitTopLevel(inner: String): List<String> {
            val elements = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var index = 0
            while (index < inner.length) {
                val c = inner[index]
                when {
                    c == '"' -> {
                        current.append(c)
                        index++
                        while (index < inner.length && inner[index] != '"') {
                            if (inner[index] == '\\') {
                                current.append(inner[index])
                                index++
                            }
                            if (index < inner.length) current.append(inner[index])
                            index++
                        }
                        if (index < inner.length) current.append(inner[index])
                    }

                    c == '#' -> while (index + 1 < inner.length && inner[index + 1] != '\n') index++
                    c == '{' || c == '[' -> {
                        depth++
                        current.append(c)
                    }

                    c == '}' || c == ']' -> {
                        depth--
                        current.append(c)
                    }

                    c == ',' && depth == 0 -> {
                        elements += current.toString()
                        current.clear()
                    }

                    else -> current.append(c)
                }
                index++
            }
            elements += current.toString()
            return elements.map { it.trim() }.filter { it.isNotEmpty() }
        }

        // legacy [[ignore]] array-of-tables format
        private fun parseLegacyBlocks(text: String): List<IgnoreRule> {
            val rules = mutableListOf<IgnoreRule>()
            var inBlock = false
            var dependency: String? = null
            var versions: List<String> = emptyList()
            var type: UpdateType? = null

            fun push() {
                dependency?.let { rules += IgnoreRule(it, versions, type) }
                dependency = null
                versions = emptyList()
                type = null
            }

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("#") || line.isEmpty()) continue
                if (line == "[[ignore]]") {
                    push()
                    inBlock = true
                    continue
                }
                if (line.startsWith("[")) { // any other table header ends the block
                    push()
                    inBlock = false
                    continue
                }
                if (!inBlock) continue
                val match = BLOCK_KEY_VALUE.matchEntire(line) ?: continue
                val (key, rawValue) = match.destructured
                when (key) {
                    "dependency" -> dependency = rawValue.trim().removeSurrounding("\"")
                    "versions" -> versions = rawValue.trim().removePrefix("[").removeSuffix("]")
                        .split(',').map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }

                    "type" -> type = runCatching {
                        UpdateType.valueOf(
                            rawValue.trim().removeSurrounding("\"").uppercase()
                        )
                    }.getOrNull()
                }
            }
            push()
            return rules
        }

        private fun formatLegacyBlock(rule: IgnoreRule): String = buildString {
            append("\n[[ignore]]\n")
            append("dependency = \"").append(rule.dependency).append("\"\n")
            if (rule.versions.isNotEmpty()) {
                append("versions = [").append(rule.versions.joinToString(", ") { "\"$it\"" }).append("]\n")
            }
            rule.type?.let { append("type = \"").append(it.name.lowercase()).append("\"\n") }
        }
    }
}
