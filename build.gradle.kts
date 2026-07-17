import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    java
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "eu.kennytv"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.4")
        // Maven version ordering (ComparableVersion)
        bundledModule("intellij.libraries.maven.resolver.provider")
        testFramework(TestFrameworkType.Platform)
    }

    // Changelog markdown -> HTML; the platform's own markdown module is private as of 2026.2
    implementation("org.commonmark:commonmark:0.29.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.29.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.29.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.29.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "eu.kennytv.dependency-manager"
        name = "Dependency Manager for Gradle"
        version = project.version.toString()
        changeNotes = provider {
            changelog.renderItem(changelog.get(project.version.toString()), Changelog.OutputType.HTML)
        }
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

changelog {
    repositoryUrl = "https://github.com/kennytv/GradleDependencyManager"
    groups.empty()
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
