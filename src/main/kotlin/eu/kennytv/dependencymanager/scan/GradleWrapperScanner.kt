package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.FileLocation
import eu.kennytv.dependencymanager.model.ScannedDependency

class GradleWrapperScanner : FileScanner {

    override fun matches(relativePath: String): Boolean =
        relativePath.endsWith("gradle/wrapper/gradle-wrapper.properties")

    override fun scan(relativePath: String, content: String): List<ScannedDependency> {
        var checksumLine: Int? = null
        var wrapper: ScannedDependency.GradleWrapper? = null
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.startsWith("distributionSha256Sum=")) {
                checksumLine = index
            } else if (line.startsWith("distributionUrl=")) {
                val match = DISTRIBUTION.find(line) ?: return@forEachIndexed
                wrapper = ScannedDependency.GradleWrapper(
                    currentVersion = match.groupValues[1],
                    distType = match.groupValues[2],
                    checksumLine = null,
                    location = FileLocation(relativePath, index),
                )
            }
        }
        val found = wrapper ?: return emptyList()
        return listOf(found.copy(checksumLine = checksumLine))
    }

    companion object {
        private val DISTRIBUTION = Regex("""gradle-([\d][\w.]*?)-(bin|all)\.zip""")
    }
}
