# Changelog

## [Unreleased]

## [0.2.0] - 2026-09-06

- Flags GitHub Actions refs that can still be moved (moving majors, tags of releases that aren't immutable), and points out SHA pins whose release is immutable anyway
- The reminder notification stays quiet in projects without any workflow, build script, version catalog or wrapper file

## [0.1.2] - 2026-07-17

- Added compatibility with 2026.2

## [0.1.1] - 2026-07-14

- Initial release
- Scans Gradle build scripts, version catalogs, the Gradle wrapper, and GitHub Actions workflows
- Release notes shown per update, batch apply as a single commit
- Ignore rules via .dependency-updates.toml

[Unreleased]: https://github.com/kennytv/GradleDependencyManager/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/kennytv/GradleDependencyManager/compare/v0.1.2...v0.2.0
[0.1.2]: https://github.com/kennytv/GradleDependencyManager/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/kennytv/GradleDependencyManager/commits/v0.1.1
