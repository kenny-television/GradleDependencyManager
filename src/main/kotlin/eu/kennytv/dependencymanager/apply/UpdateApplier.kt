package eu.kennytv.dependencymanager.apply

import eu.kennytv.dependencymanager.model.ActionRefType
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.model.UpdateCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Applies selected updates as in-place text edits and optionally commits the result. */
class UpdateApplier(private val root: Path) {

    data class ApplyResult(
        val applied: List<UpdateCandidate>,
        val failed: List<Pair<UpdateCandidate, String>>,
        val changedFiles: Set<Path>,
    )

    data class ExecResult(val success: Boolean, val output: String)

    fun apply(selected: List<UpdateCandidate>): ApplyResult {
        val applied = mutableListOf<UpdateCandidate>()
        val failed = mutableListOf<Pair<UpdateCandidate, String>>()
        val changedFiles = mutableSetOf<Path>()

        // shared so that multiple updates to the same file stack
        val fileContents = mutableMapOf<Path, MutableList<String>>()

        for (candidate in selected) {
            try {
                val touched = applyOne(candidate, fileContents)
                changedFiles += touched
                applied += candidate
            } catch (e: ApplyException) {
                failed += candidate to e.message!!
            } catch (e: Exception) {
                failed += candidate to (e.message ?: e.javaClass.simpleName)
            }
        }

        for ((path, lines) in fileContents) {
            if (path in changedFiles) {
                Files.writeString(path, lines.joinToString("\n"))
            }
        }
        return ApplyResult(applied, failed, changedFiles)
    }

    private class ApplyException(message: String) : Exception(message)

    private fun applyOne(candidate: UpdateCandidate, fileContents: MutableMap<Path, MutableList<String>>): Set<Path> {
        val dep = candidate.dependency

        // collect all edits before writing any, so a failed location doesn't leave things half-applied
        data class Edit(val lines: MutableList<String>, val index: Int, val newLine: String)

        val edits = mutableListOf<Edit>()
        val touched = mutableSetOf<Path>()

        for (location in dep.locations) {
            val path = root.resolve(location.relativePath)
            val lines = fileContents.getOrPut(path) {
                if (!Files.exists(path)) throw ApplyException("${location.relativePath} no longer exists")
                // split("\n") keeps \r attached to line ends, so CRLF files round-trip unchanged
                Files.readString(path).split("\n").toMutableList()
            }

            val lineIndex = findLine(lines, location.line) { rewrite(candidate, dep, it) != null }
                ?: throw ApplyException("${location.relativePath}: declaration of ${dep.displayName} ${dep.currentVersion} not found (file changed since the scan?)")
            edits += Edit(lines, lineIndex, rewrite(candidate, dep, lines[lineIndex])!!)
            // not +=, Path is Iterable<Path>
            touched.add(path)

            if (dep is ScannedDependency.GradleWrapper && dep.checksumLine != null) {
                val checksum = candidate.checksum
                    ?: throw ApplyException("wrapper pins distributionSha256Sum but no new checksum was resolved")
                val checksumIndex = findLine(lines, dep.checksumLine) { it.startsWith("distributionSha256Sum=") }
                    ?: throw ApplyException("distributionSha256Sum line not found")
                val eol = if (lines[checksumIndex].endsWith("\r")) "\r" else ""
                edits += Edit(lines, checksumIndex, "distributionSha256Sum=$checksum$eol")
            }
        }

        for (edit in edits) {
            edit.lines[edit.index] = edit.newLine
        }
        return touched
    }

    // Tries the recorded line first; the file may have shifted since the scan.
    private fun findLine(lines: List<String>, preferred: Int, matches: (String) -> Boolean): Int? {
        if (preferred < lines.size && matches(lines[preferred])) return preferred
        return lines.indexOfFirst(matches).takeIf { it >= 0 }
    }

    private fun rewrite(candidate: UpdateCandidate, dep: ScannedDependency, line: String): String? = when (dep) {
        is ScannedDependency.GitHubAction -> rewriteAction(candidate, dep, line)
        is ScannedDependency.MavenDependency -> replaceDelimitedVersion(line, dep.currentVersion, candidate.newVersion)
        is ScannedDependency.GradleWrapper ->
            if (line.contains("gradle-${dep.currentVersion}-${dep.distType}.zip")) {
                line.replace(
                    "gradle-${dep.currentVersion}-${dep.distType}.zip",
                    "gradle-${candidate.newVersion}-${dep.distType}.zip"
                )
            } else null
    }

    private fun rewriteAction(candidate: UpdateCandidate, dep: ScannedDependency.GitHubAction, line: String): String? {
        if (!line.contains("${dep.slug}@${dep.ref}")) return null
        val newRef = candidate.newRef ?: return null
        var result = line.replace("@${dep.ref}", "@$newRef")
        if (dep.refType == ActionRefType.SHA) {
            val display = candidate.newVersion
            result = if (dep.commentVersion != null) {
                result.replaceFirst(Regex("#\\s*${Regex.escape(dep.commentVersion)}"), "# $display")
            } else {
                result.trimEnd('\r') + " # $display" + if (result.endsWith("\r")) "\r" else ""
            }
        }
        return result
    }

    // Only replaces the version where it appears as a whole token (preceded by quote/colon/=,
    // followed by quote or end of line), so lookalike substrings elsewhere on the line survive.
    private fun replaceDelimitedVersion(line: String, current: String, new: String): String? {
        var index = line.indexOf(current)
        while (index >= 0) {
            val before = line.getOrNull(index - 1)
            val after = line.getOrNull(index + current.length)
            val beforeOk = before == '"' || before == '\'' || before == ':' || before == '='
            val afterOk = after == '"' || after == '\'' || after == null || after == '\r'
            if (beforeOk && afterOk) {
                return line.substring(0, index) + new + line.substring(index + current.length)
            }
            index = line.indexOf(current, index + 1)
        }
        return null
    }

    fun commit(changedFiles: Collection<Path>, message: String): ExecResult {
        val relative = changedFiles.map { root.relativize(it).joinToString("/") { part -> part.fileName.toString() } }
        val add = git(listOf("add", "--") + relative)
        if (!add.success) return add
        return git(listOf("commit", "-m", message))
    }

    private fun git(args: List<String>): ExecResult = try {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        ExecResult(finished && process.exitValue() == 0, output.trim())
    } catch (e: Exception) {
        ExecResult(false, e.message ?: e.javaClass.simpleName)
    }

    // Output goes to a temp file rather than a pipe; big builds would fill and deadlock the pipe.
    fun runGradle(tasks: List<String>, isCancelled: () -> Boolean = { false }): ExecResult {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val script = root.resolve(if (isWindows) "gradlew.bat" else "gradlew")
        if (!Files.exists(script)) return ExecResult(false, "No Gradle wrapper script found (${script.fileName})")

        val command = (if (isWindows) listOf("cmd.exe", "/c", script.toString()) else listOf(script.toString())) +
            tasks + "--console=plain"
        val log = Files.createTempFile("dependency-manager-gradle-", ".log")
        return try {
            val process = ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30)
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (isCancelled()) {
                    process.destroy()
                    return ExecResult(false, "Cancelled")
                }
                if (System.currentTimeMillis() > deadline) {
                    process.destroy()
                    return ExecResult(false, "Timed out after 30 minutes")
                }
            }
            val output = Files.readString(log).takeLast(4000).trim()
            ExecResult(process.exitValue() == 0, output)
        } catch (e: Exception) {
            ExecResult(false, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { Files.deleteIfExists(log) }
        }
    }
}
