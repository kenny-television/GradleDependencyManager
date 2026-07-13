package eu.kennytv.dependencymanager

import eu.kennytv.dependencymanager.ignore.IgnoreRule
import eu.kennytv.dependencymanager.ignore.IgnoreRules
import eu.kennytv.dependencymanager.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IgnoreRulesTest {

    private fun mavenDep(group: String, name: String, version: String) = ScannedDependency.MavenDependency(
        modules = listOf(ModuleCoordinate(group, name)),
        currentVersion = version,
        versionRefKey = null,
        pluginIds = emptyList(),
        declaration = DeclarationKind.INLINE,
        locations = listOf(FileLocation("build.gradle.kts", 0)),
    )

    @Test
    fun `parses the compact ignore array`() {
        val rules = IgnoreRules.parse(
            """
            # comment
            ignore = [
                "com.google.guava:guava",
                { dependency = "io.netty:*", versions = ["4.1.*", "5.0.0"], type = "major" }, # trailing comment
                { dependency = "actions/checkout", type = "major" },
            ]
            """.trimIndent()
        )
        assertEquals(3, rules.size)
        assertEquals(IgnoreRule("com.google.guava:guava"), rules[0])
        assertEquals(IgnoreRule("io.netty:*", listOf("4.1.*", "5.0.0"), UpdateType.MAJOR), rules[1])
        assertEquals(IgnoreRule("actions/checkout", type = UpdateType.MAJOR), rules[2])
    }

    @Test
    fun `still parses the legacy block format`() {
        val rules = IgnoreRules.parse(
            """
            # comment
            [[ignore]]
            dependency = "com.google.guava:guava"

            [[ignore]]
            dependency = "io.netty:*"
            versions = ["4.1.*", "5.0.0"]
            type = "major"
            """.trimIndent()
        )
        assertEquals(2, rules.size)
        assertEquals("com.google.guava:guava", rules[0].dependency)
        assertEquals(listOf("4.1.*", "5.0.0"), rules[1].versions)
        assertEquals(UpdateType.MAJOR, rules[1].type)
    }

    @Test
    fun `append builds and extends a compact array`(@TempDir root: Path) {
        assertTrue(IgnoreRules.append(root, IgnoreRule("io.netty:netty-all")))
        assertTrue(IgnoreRules.append(root, IgnoreRule("com.google.guava:guava", type = UpdateType.MAJOR)))
        assertTrue(IgnoreRules.append(root, IgnoreRule("actions/checkout", versions = listOf("v9.*"))))

        val text = Files.readString(root.resolve(IgnoreRules.FILE_NAME))
        assertEquals(1, Regex("(?m)^ignore = \\[").findAll(text).count(), text)
        assertTrue(text.contains("    \"io.netty:netty-all\",\n"), text)
        assertTrue(text.contains("    { dependency = \"com.google.guava:guava\", type = \"major\" },\n"), text)
        assertTrue(text.contains("    { dependency = \"actions/checkout\", versions = [\"v9.*\"] },\n"), text)
        assertEquals(3, IgnoreRules.load(root).rules.size)
    }

    @Test
    fun `append stays in block style for legacy files`(@TempDir root: Path) {
        Files.writeString(root.resolve(IgnoreRules.FILE_NAME), "[[ignore]]\ndependency = \"a.b:c\"\n")
        assertTrue(IgnoreRules.append(root, IgnoreRule("d.e:f")))
        val text = Files.readString(root.resolve(IgnoreRules.FILE_NAME))
        assertTrue(text.contains("[[ignore]]\ndependency = \"d.e:f\""), text)
        assertFalse(text.contains("ignore = ["), text)
        assertEquals(2, IgnoreRules.load(root).rules.size)
    }

    @Test
    fun `whole dependency ignore`() {
        val rules = IgnoreRules(listOf(IgnoreRule("com.google.guava:guava")))
        val guava = mavenDep("com.google.guava", "guava", "17.0")
        assertTrue(rules.isFullyIgnored(guava))
        assertTrue(rules.isIgnored(guava, "33.0.0", UpdateType.MAJOR))
        assertFalse(rules.isFullyIgnored(mavenDep("com.google.code.gson", "gson", "2.14.0")))
    }

    @Test
    fun `version and type filters`() {
        val rules = IgnoreRules(
            listOf(
                IgnoreRule("io.netty:*", versions = listOf("4.1.*")),
                IgnoreRule("org.junit.jupiter:*", type = UpdateType.MAJOR),
            )
        )
        val netty = mavenDep("io.netty", "netty-all", "4.0.20.Final")
        assertFalse(rules.isFullyIgnored(netty))
        assertTrue(rules.isIgnored(netty, "4.1.100.Final", UpdateType.MINOR))
        assertFalse(rules.isIgnored(netty, "4.2.0.Final", UpdateType.MINOR))

        val junit = mavenDep("org.junit.jupiter", "junit-jupiter-api", "6.1.0")
        assertTrue(rules.isIgnored(junit, "7.0.0", UpdateType.MAJOR))
        assertFalse(rules.isIgnored(junit, "6.2.0", UpdateType.MINOR))
    }

    @Test
    fun `append deduplicates identical rules`(@TempDir root: Path) {
        val rule = IgnoreRule("com.google.guava:guava", versions = listOf("33.6.0-jre"))
        assertTrue(IgnoreRules.append(root, rule))
        assertFalse(IgnoreRules.append(root, rule))
        assertTrue(IgnoreRules.append(root, IgnoreRule("com.google.guava:guava", type = UpdateType.MAJOR)))
        assertEquals(2, IgnoreRules.load(root).rules.size)
    }

    @Test
    fun `an existing file under idea directory is honored`(@TempDir root: Path) {
        val ideaDir = Files.createDirectories(root.resolve(".idea"))
        Files.writeString(ideaDir.resolve(IgnoreRules.FILE_NAME), "[[ignore]]\ndependency = \"a.b:c\"\n")

        assertEquals(1, IgnoreRules.load(root).rules.size)
        assertTrue(IgnoreRules.append(root, IgnoreRule("d.e:f")))
        // the append landed in the .idea file, not a new root file
        assertEquals(2, IgnoreRules.load(root).rules.size)
        assertFalse(Files.exists(root.resolve(IgnoreRules.FILE_NAME)))
    }

    @Test
    fun `glob matches action slugs`() {
        val rules = IgnoreRules(listOf(IgnoreRule("actions/*")))
        val action = ScannedDependency.GitHubAction(
            owner = "actions", repo = "checkout", subPath = null,
            ref = "v7", refType = eu.kennytv.dependencymanager.model.ActionRefType.MOVING_MAJOR,
            commentVersion = null,
            locations = listOf(FileLocation(".github/workflows/build.yml", 3)),
        )
        assertTrue(rules.isFullyIgnored(action))
    }
}
