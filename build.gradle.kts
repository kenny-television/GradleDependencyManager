import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    java
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "eu.kennytv"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.4")
        // Changelog markdown -> HTML
        bundledModule("intellij.libraries.markdown")
        // Maven version ordering (ComparableVersion)
        bundledModule("intellij.libraries.maven.resolver.provider")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "eu.kennytv.dependency-manager"
        name = "Dependency Manager for Gradle"
        version = project.version.toString()
        changeNotes = """
            <h3>0.1.1</h3>
            <ul>
                <li>Initial release</li>
                <li>Scans Gradle build scripts, version catalogs, the Gradle wrapper, and GitHub Actions workflows</li>
                <li>Release notes shown per update, batch apply as a single commit</li>
                <li>Ignore rules via .dependency-updates.toml</li>
            </ul>
        """.trimIndent()
        vendor {
            name = "Nassim Jahnke"
        }
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
    signing {
        val signingDir = File(System.getProperty("user.home"), ".jetbrains-plugin-signing")
        certificateChainFile = signingDir.resolve("chain.crt")
        privateKeyFile = signingDir.resolve("private.pem")
    }
    buildSearchableOptions = false
}

kotlin {
    compilerOptions {
        // Don't generate DefaultImpls bridges for platform interface defaults; the verifier
        // flags the generated overrides of deprecated methods as deprecated API usage.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

tasks.test {
    useJUnitPlatform()
}
