package eu.kennytv.dependencymanager

import eu.kennytv.dependencymanager.model.ActionRefType
import eu.kennytv.dependencymanager.model.DeclarationKind
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.scan.GitHubActionsScanner
import eu.kennytv.dependencymanager.scan.GradleBuildScanner
import eu.kennytv.dependencymanager.scan.GradleWrapperScanner
import eu.kennytv.dependencymanager.scan.VersionCatalogScanner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GitHubActionsScannerTest {

    private val scanner = GitHubActionsScanner()

    @Test
    fun `parses all three pinning styles`() {
        val workflow = """
            jobs:
              build:
                steps:
                  - uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7.0.0
                  - name: Setup Gradle
                    uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0
                  - uses: actions/setup-java@v5.5.0
                  - uses: actions/cache@v5
                  - uses: ./local-action
                  # - uses: commented/out@v1
                  - uses: docker://alpine:3
                  - uses: some/branch-ref@main
        """.trimIndent()

        val found = scanner.scan(".github/workflows/build.yml", workflow)
            .filterIsInstance<ScannedDependency.GitHubAction>()

        assertEquals(4, found.size)

        val checkout = found[0]
        assertEquals("actions/checkout", checkout.slug)
        assertEquals(ActionRefType.SHA, checkout.refType)
        assertEquals("9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0", checkout.ref)
        assertEquals("v7.0.0", checkout.commentVersion)
        assertEquals("v7.0.0", checkout.currentVersion)

        val setupGradle = found[1]
        assertEquals("gradle/actions/setup-gradle", setupGradle.slug)
        assertEquals("gradle", setupGradle.owner)
        assertEquals("actions", setupGradle.repo)
        assertEquals("setup-gradle", setupGradle.subPath)
        assertEquals(ActionRefType.SHA, setupGradle.refType)

        val setupJava = found[2]
        assertEquals(ActionRefType.FULL_TAG, setupJava.refType)
        assertEquals("v5.5.0", setupJava.ref)

        val cache = found[3]
        assertEquals(ActionRefType.MOVING_MAJOR, cache.refType)
        assertEquals("v5", cache.ref)
    }
}

class GradleBuildScannerTest {

    private val scanner = GradleBuildScanner()

    @Test
    fun `finds inline coordinates and plugin declarations`() {
        val script = """
            plugins {
                id("com.gradleup.shadow") version "9.4.2"
                kotlin("jvm") version "2.2.20"
            }
            dependencies {
                // version must be manually kept in sync with the one in root project settings.gradle.kts
                implementation("com.gradleup.shadow:shadow-gradle-plugin:9.4.2")
                implementation("io.netty:netty-all:${'$'}{nettyVersion}")
                implementation(project(":api"))
                implementation(libs.gson)
            }
        """.trimIndent()

        val found = scanner.scan("build-logic/build.gradle.kts", script)
            .filterIsInstance<ScannedDependency.MavenDependency>()

        assertEquals(3, found.size)

        val shadowPlugin =
            found.first { it.declaration == DeclarationKind.PLUGIN_BLOCK && it.pluginIds == listOf("com.gradleup.shadow") }
        assertEquals("9.4.2", shadowPlugin.currentVersion)
        assertEquals("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin", shadowPlugin.modules[0].toString())

        val kotlinPlugin = found.first { it.pluginIds == listOf("org.jetbrains.kotlin.jvm") }
        assertEquals("2.2.20", kotlinPlugin.currentVersion)

        val inline = found.first { it.declaration == DeclarationKind.INLINE }
        assertEquals("com.gradleup.shadow:shadow-gradle-plugin", inline.modules[0].toString())
        assertEquals("9.4.2", inline.currentVersion)
    }
}

class VersionCatalogScannerTest {

    private val scanner = VersionCatalogScanner()

    private val catalog = """
        metadata.format.version = "1.1"

        [versions]
        gson = "2.14.0"
        junit = "6.1.0"
        viaProxy = "[3.0.0,4.0.0)"
        mcstructs = "5-3.3.1-SNAPSHOT"
        strictNetty = { strictly = "4.1.100" }
        multiBound = { require = "1.2", prefer = "1.3" }

        [libraries]
        gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
        jupiterApi = { group = "org.junit.jupiter", name = "junit-jupiter-api", version.ref = "junit" }
        jupiterEngine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit" }
        text = { group = "com.viaversion.mcstructs", name = "text", version.ref = "mcstructs" }
        viaProxy = { group = "net.raphimc", name = "ViaProxy", version.ref = "viaProxy" }
        strictNetty = { module = "io.netty:netty-all", version.ref = "strictNetty" }
        multiBoundLib = { module = "a.b:c", version.ref = "multiBound" }
        adventure = { module = "net.kyori:adventure-api", version = "4.17.0" }
        richInline = { module = "d.e:f", version = { strictly = "2.0.0" } }
        bomManaged = { group = "some.group", name = "no-version" }

        [bundles]
        junit = ["jupiterApi", "jupiterEngine"]
    """.trimIndent()

    @Test
    fun `groups shared version refs and keeps inline versions separate`() {
        val found = scanner.scan("gradle/libs.versions.toml", catalog)
            .filterIsInstance<ScannedDependency.MavenDependency>()

        val junit = found.first { it.versionRefKey == "junit" }
        assertEquals(2, junit.modules.size)
        assertEquals("6.1.0", junit.currentVersion)
        assertTrue(junit.displayName.contains("junit"))

        val gson = found.first { it.versionRefKey == "gson" }
        assertEquals("com.google.code.gson:gson", gson.modules[0].toString())

        val adventure = found.first { it.versionRefKey == null && it.modules[0].name == "adventure-api" }
        assertEquals("net.kyori:adventure-api", adventure.modules[0].toString())
        assertEquals("4.17.0", adventure.currentVersion)

        // rich versions are not supported and must be left alone
        assertNull(found.firstOrNull { it.versionRefKey == "strictNetty" })
        assertNull(found.firstOrNull { it.versionRefKey == "multiBound" })
        assertNull(found.firstOrNull { it.versionRefKey == null && it.modules[0].name == "f" })

        // range + snapshot entries are still scanned; the resolver decides to skip them
        assertEquals("[3.0.0,4.0.0)", found.first { it.versionRefKey == "viaProxy" }.currentVersion)
        assertEquals("5-3.3.1-SNAPSHOT", found.first { it.versionRefKey == "mcstructs" }.currentVersion)

        // metadata.format.version must not be picked up; bom-managed library has no version
        assertNull(found.firstOrNull { it.versionRefKey == "metadata" })
        assertNull(found.firstOrNull { it.modules.any { m -> m.name == "no-version" } })
    }
}

class GradleWrapperScannerTest {

    @Test
    fun `parses version, distribution type and checksum line`() {
        val properties = """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14
            distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
            networkTimeout=10000
        """.trimIndent()

        val found = GradleWrapperScanner().scan("gradle/wrapper/gradle-wrapper.properties", properties)
        assertEquals(1, found.size)
        val wrapper = found[0] as ScannedDependency.GradleWrapper
        assertEquals("9.6.1", wrapper.currentVersion)
        assertEquals("bin", wrapper.distType)
        assertEquals(2, wrapper.checksumLine)
        assertEquals(3, wrapper.location.line)
    }
}
