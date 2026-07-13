package eu.kennytv.dependencymanager.scan

import eu.kennytv.dependencymanager.model.ScannedDependency

/** Scans a single file's text for dependency declarations. Paths use forward slashes. */
interface FileScanner {
    fun matches(relativePath: String): Boolean

    fun scan(relativePath: String, content: String): List<ScannedDependency>
}
