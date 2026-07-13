package eu.kennytv.dependencymanager

import eu.kennytv.dependencymanager.apply.UpdateApplier
import eu.kennytv.dependencymanager.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UpdateApplierTest {

    @TempDir
    lateinit var root: Path

    private fun write(relative: String, content: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return path
    }

    @Test
    fun `rewrites sha pinned action with comment`() {
        val path = write(
            ".github/workflows/build.yml",
            "steps:\n  - uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7.0.0\n",
        )
        val dep = ScannedDependency.GitHubAction(
            owner = "actions", repo = "checkout", subPath = null,
            ref = "9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", refType = ActionRefType.SHA,
            commentVersion = "v7.0.0",
            locations = listOf(FileLocation(".github/workflows/build.yml", 1)),
        )
        val candidate = UpdateCandidate(
            dep,
            newVersion = "v7.1.0",
            newRef = "aabbccddeeff00112233445566778899aabbccdd",
            updateType = UpdateType.MINOR
        )

        val result = UpdateApplier(root).apply(listOf(candidate))

        assertTrue(result.failed.isEmpty())
        assertEquals(
            "steps:\n  - uses: actions/checkout@aabbccddeeff00112233445566778899aabbccdd # v7.1.0\n",
            Files.readString(path),
        )
    }

    @Test
    fun `rewrites catalog version and inline coordinate, preserving comments and crlf`() {
        val toml = write(
            "gradle/libs.versions.toml",
            "[versions]\r\n# a load-bearing comment\r\ngson = \"2.14.0\"\r\n",
        )
        val kts = write(
            "build-logic/build.gradle.kts",
            "dependencies {\n    // keep in sync\n    implementation(\"com.gradleup.shadow:shadow-gradle-plugin:9.4.2\")\n}\n",
        )
        val settings = write(
            "settings.gradle.kts",
            "plugins {\n    id(\"com.gradleup.shadow\") version \"9.4.2\"\n}\n",
        )

        val gson = ScannedDependency.MavenDependency(
            modules = listOf(ModuleCoordinate("com.google.code.gson", "gson")),
            currentVersion = "2.14.0", versionRefKey = "gson", pluginIds = emptyList(),
            declaration = DeclarationKind.CATALOG,
            locations = listOf(FileLocation("gradle/libs.versions.toml", 2)),
        )
        // one dependency with two locations updates both files in lockstep
        val shadow = ScannedDependency.MavenDependency(
            modules = listOf(ModuleCoordinate("com.gradleup.shadow", "shadow-gradle-plugin")),
            currentVersion = "9.4.2", versionRefKey = null, pluginIds = emptyList(),
            declaration = DeclarationKind.INLINE,
            locations = listOf(
                FileLocation("build-logic/build.gradle.kts", 2),
                FileLocation("settings.gradle.kts", 1),
            ),
        )

        val result = UpdateApplier(root).apply(
            listOf(
                UpdateCandidate(gson, newVersion = "2.15.0", updateType = UpdateType.MINOR),
                UpdateCandidate(shadow, newVersion = "9.5.0", updateType = UpdateType.MINOR),
            )
        )

        assertTrue(result.failed.isEmpty(), result.failed.joinToString { it.second })
        assertEquals("[versions]\r\n# a load-bearing comment\r\ngson = \"2.15.0\"\r\n", Files.readString(toml))
        assertTrue(Files.readString(kts).contains("shadow-gradle-plugin:9.5.0"))
        assertTrue(Files.readString(kts).contains("// keep in sync"))
        assertTrue(Files.readString(settings).contains("version \"9.4.2\"").not())
        assertTrue(Files.readString(settings).contains("version \"9.5.0\""))
    }

    @Test
    fun `updates wrapper url and checksum together`() {
        val path = write(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionBase=GRADLE_USER_HOME\n" +
                "distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip\n",
        )
        val dep = ScannedDependency.GradleWrapper(
            currentVersion = "9.6.1", distType = "bin", checksumLine = 1,
            location = FileLocation("gradle/wrapper/gradle-wrapper.properties", 2),
        )
        val newChecksum = "a".repeat(64)
        val result = UpdateApplier(root).apply(
            listOf(UpdateCandidate(dep, newVersion = "9.7.0", updateType = UpdateType.MINOR, checksum = newChecksum))
        )

        assertTrue(result.failed.isEmpty(), result.failed.joinToString { it.second })
        val content = Files.readString(path)
        assertTrue(content.contains("gradle-9.7.0-bin.zip"))
        assertTrue(content.contains("distributionSha256Sum=$newChecksum"))
    }

    @Test
    fun `rewrites a gradle properties variable definition`() {
        val path = write("gradle.properties", "projectVersion=5.11.0\nnettyVersion=4.1.100\n")
        val dep = ScannedDependency.MavenDependency(
            modules = listOf(ModuleCoordinate("io.netty", "netty-all")),
            currentVersion = "4.1.100", versionRefKey = "nettyVersion", pluginIds = emptyList(),
            declaration = DeclarationKind.INLINE,
            locations = listOf(FileLocation("gradle.properties", 1)),
        )
        val result = UpdateApplier(root).apply(
            listOf(UpdateCandidate(dep, newVersion = "4.1.130", updateType = UpdateType.PATCH))
        )
        assertTrue(result.failed.isEmpty(), result.failed.joinToString { it.second })
        assertEquals("projectVersion=5.11.0\nnettyVersion=4.1.130\n", Files.readString(path))
    }

    @Test
    fun `fails cleanly when the declaration has changed since the scan`() {
        write("build.gradle.kts", "dependencies { implementation(\"a.b:c:2.0.0\") }\n")
        val dep = ScannedDependency.MavenDependency(
            modules = listOf(ModuleCoordinate("a.b", "c")),
            currentVersion = "1.0.0", versionRefKey = null, pluginIds = emptyList(),
            declaration = DeclarationKind.INLINE,
            locations = listOf(FileLocation("build.gradle.kts", 0)),
        )
        val result = UpdateApplier(root).apply(
            listOf(UpdateCandidate(dep, newVersion = "3.0.0", updateType = UpdateType.MAJOR))
        )
        assertEquals(1, result.failed.size)
        assertTrue(result.applied.isEmpty())
    }
}
