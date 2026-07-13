package eu.kennytv.dependencymanager

import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.scan.ProjectScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectScannerTest {

    @TempDir
    lateinit var root: Path

    private fun write(relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent ?: root)
        Files.writeString(path, content)
    }

    @Test
    fun `identical action pins across workflow files merge into one entry`() {
        val checkout = "  - uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7.0.0\n"
        write(".github/workflows/build.yml", "steps:\n$checkout")
        write(".github/workflows/publish.yml", "steps:\n$checkout  - uses: actions/setup-java@v5.5.0\n")

        val actions = ProjectScanner().scan(root).dependencies.filterIsInstance<ScannedDependency.GitHubAction>()

        assertEquals(2, actions.size)
        val merged = actions.first { it.slug == "actions/checkout" }
        assertEquals(2, merged.locations.size)
        assertEquals(
            setOf(".github/workflows/build.yml", ".github/workflows/publish.yml"),
            merged.locations.map { it.relativePath }.toSet()
        )
        assertEquals(1, actions.first { it.slug == "actions/setup-java" }.locations.size)
    }

    @Test
    fun `resolves version variables from the same script and gradle properties`() {
        write(
            "build.gradle.kts",
            """
            val nettyVersion = "4.1.100"
            dependencies {
                implementation("io.netty:netty-all:${'$'}nettyVersion")
                implementation("io.netty:netty-buffer:${'$'}{nettyVersion}")
                implementation("com.google.code.gson:gson:${'$'}{gsonVersion}")
                implementation("some.group:no-definition:${'$'}{unknownVersion}")
            }
            """.trimIndent(),
        )
        write("gradle.properties", "gsonVersion=2.14.0\n")

        val deps = ProjectScanner().scan(root).dependencies.filterIsInstance<ScannedDependency.MavenDependency>()

        val netty = deps.first { it.versionRefKey == "nettyVersion" }
        assertEquals("4.1.100", netty.currentVersion)
        assertEquals(2, netty.modules.size) // both usages share the variable
        assertEquals("build.gradle.kts", netty.locations.single().relativePath)
        assertEquals(0, netty.locations.single().line) // points at the definition

        val gson = deps.first { it.versionRefKey == "gsonVersion" }
        assertEquals("2.14.0", gson.currentVersion)
        assertEquals("gradle.properties", gson.locations.single().relativePath)

        // no definition anywhere -> not reported
        assertTrue(deps.none { it.modules.any { m -> m.name == "no-definition" } })
    }
}
