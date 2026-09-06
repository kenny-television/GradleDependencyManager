[![Install from JetBrains Marketplace](https://img.shields.io/badge/Install-JetBrains%20Marketplace-50A050?style=for-the-badge&logo=jetbrains)](https://plugins.jetbrains.com/plugin/32918)

# Dependency Manager for Gradle

An IntelliJ plugin for interactive dependency updates. Instead of one bot PR per dependency (or having to deal with PRs
in general that often you still need to verify yourself), you open a tool window once a month, review every available
update with its official changelog, deselect what you don't want and apply the rest as a **single commit**.

Made for projects where only one person is responsible for dependency updates/where dependency PRs provide more noise than use.

## Supported dependencies

| Ecosystem                                         | Formats                                                                                                                                                                                                                         |
|---------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| GitHub Actions (`.github/workflows/*.yml`)        | SHA-pinned with `# vX.Y.Z` comment, full tags (`@v5.5.0`), moving majors (`@v5`)                                                                                                                                                |
| Gradle build scripts (`*.gradle.kts`, `*.gradle`) | Inline `"group:artifact:version"` strings, `id("...") version "..."` / `kotlin("...") version "..."` plugin declarations, versions with variables (`"io.netty:netty-all:$nettyVersion"`, including `gradle.properties` entries) |
| Gradle version catalogs (`*.versions.toml`)       | `[versions]` refs, inline versions                                                                                                                                                                                              |
| Gradle wrapper                                    | `distributionUrl` version, `distributionSha256Sum`; `gradlew wrapper` runs automatically after the update so scripts and wrapper jar refresh too                                                                                |

## Usage

1. Open the **Dependency Updates** tool window (right side). Opening it runs a check, the refresh button re-runs it.
2. Select an entry to read its official release notes (GitHub releases).
3. Uncheck anything you don't want. Right-click for ignore rules: *Ignore This Dependency*, *Ignore This Version*,
   *Ignore Major Updates*.
4. Press apply. All checked updates are written, verified with `gradlew build` (toggleable; if the build fails, changes
   stay applied but nothing is committed) and committed as one commit (also toggleable).

A notification reminds you when a project hasn't been checked for a while - only in projects that
actually hold workflow, build script, version catalog or wrapper files. The interval
(default 30 days) and the reminder itself are configurable per project under
**Settings → Tools → Dependency Manager**; all plugin notifications can also be
muted globally via Settings → Appearance & Behavior → Notifications.

## Version filtering

- Unstable versions (alpha/beta/RC/M/EAP/SNAPSHOT) are only offered when the current version is itself unstable.
- Range/dynamic versions (`[3.0.0,4.0.0)`, `1.+`, `latest.release`) are skipped.
- SHA-pinned actions require version comments to be identified.

## Action pinning

Next to the updates, workflow refs are checked against
[immutable releases](https://github.blog/changelog/2025-08-26-releases-now-support-immutability-in-public-preview/),
which freeze a release's tag so it can no longer be moved or deleted:

- **Warning** for a ref that can still move: a moving major (`@v5`), or a tag whose release isn't immutable. What runs
  in the workflow can change without the ref changing, so pin it to the full commit SHA with a `# vX.Y.Z` comment.
- **Info** for a SHA pin whose release *is* immutable - the tag alone would already resolve to the same code, so the
  pin isn't needed for that version.

## Ignore rules: `.dependency-updates.toml`

Lives in the project root by default so it can be committed and shared; if you want to keep the root clean, you can also
move it to `.idea/dependency-updates.toml`.

```toml
ignore = [
    "com.google.guava:guava", # fully ignored
    { dependency = "io.netty:*", versions = ["4.1.*"] }, # versions 4.1.x are ignored
    { dependency = "actions/checkout", type = "major" }, # show minor/patch, ignore majors
]
```

## GitHub API access

Changelogs and action lookups use the GitHub API. To avoid heavy rate-limiting, `GITHUB_TOKEN` / `GH_TOKEN`
or [gh CLI](https://cli.github.com/) (`gh auth token`) are used.

## Building

```
./gradlew buildPlugin
./gradlew runIde
```

Testing:
```
./gradlew test
./gradlew verifyPlugin
```
