package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.ScannedDependency
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name
import kotlin.io.path.readText

data class ScanResult(
    val dependencies: List<ScannedDependency>,
    val repositories: List<String>,
)

class ProjectScanner {

    private val scanners: List<FileScanner> = listOf(
        GitHubActionsScanner(),
        GradleBuildScanner(),
        VersionCatalogScanner(),
        GradleWrapperScanner(),
    )

    fun scan(root: Path): ScanResult {
        val dependencies = mutableListOf<ScannedDependency>()
        val repositories = linkedSetOf(MAVEN_CENTRAL)
        val variableScanner = VariableVersionScanner()

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relativePath = root.relativize(file).joinToString("/") { it.name }
                val applicable = scanners.filter { it.matches(relativePath) }
                val isBuildScript = relativePath.endsWith(".gradle.kts") || relativePath.endsWith(".gradle")
                val isGradleProperties =
                    relativePath == "gradle.properties" || relativePath.endsWith("/gradle.properties")
                if (applicable.isEmpty() && !isBuildScript && !isGradleProperties) return FileVisitResult.CONTINUE

                val content = try {
                    file.readText()
                } catch (_: Exception) {
                    return FileVisitResult.CONTINUE
                }
                for (scanner in applicable) {
                    dependencies += scanner.scan(relativePath, content)
                }
                if (isBuildScript) {
                    collectRepositories(content, repositories)
                    variableScanner.collectBuildScript(relativePath, content)
                }
                if (isGradleProperties) {
                    variableScanner.collectProperties(relativePath, content)
                }
                return FileVisitResult.CONTINUE
            }
        })

        dependencies += variableScanner.resolvedDependencies()
        repositories += GRADLE_PLUGIN_PORTAL
        return ScanResult(merge(dependencies), repositories.toList())
    }

    private fun collectRepositories(content: String, into: MutableSet<String>) {
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("//")) continue
            // maven("...") / url = uri("...") / url "..."
            if (!line.contains("maven") && !line.contains("url")) continue
            REPOSITORY_URL.findAll(line).forEach { into += it.groupValues[1].trimEnd('/') }
        }
    }

    // Identical declarations from different files are merged into one entry with multiple
    // locations, so e.g. the same pinned action across workflow files updates together.
    private fun merge(dependencies: List<ScannedDependency>): List<ScannedDependency> {
        val result = mutableListOf<ScannedDependency>()
        val mavenByKey = linkedMapOf<String, ScannedDependency.MavenDependency>()
        val actionByKey = linkedMapOf<String, ScannedDependency.GitHubAction>()
        for (dependency in dependencies) {
            when {
                dependency is ScannedDependency.MavenDependency && dependency.versionRefKey == null -> {
                    val key = "${dependency.modules}|${dependency.currentVersion}|${dependency.declaration}"
                    val existing = mavenByKey[key]
                    mavenByKey[key] = if (existing == null) dependency
                    else existing.copy(locations = existing.locations + dependency.locations)
                }

                dependency is ScannedDependency.GitHubAction -> {
                    val key = "${dependency.slug}|${dependency.ref}|${dependency.commentVersion}"
                    val existing = actionByKey[key]
                    actionByKey[key] = if (existing == null) dependency
                    else existing.copy(locations = existing.locations + dependency.locations)
                }

                else -> result += dependency
            }
        }
        result += actionByKey.values
        result += mavenByKey.values
        return result
    }

    companion object {
        const val MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
        const val GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2"

        private val SKIPPED_DIRS =
            setOf(".git", ".gradle", ".idea", "build", "out", "bin", "node_modules", "dist", "target")
        private val REPOSITORY_URL = Regex("""["'](https?://[^"'\s]+)["']""")
    }
}
