# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Until 1.0, minor versions may contain breaking changes;
they are listed under **Changed** or **Removed**.

The release workflow publishes the section of a version as its GitHub release notes, so every
release needs a `## [x.y.z]` section here before it is tagged. Pre-releases such as `x.y.z-rc1` use
their own section if there is one, and the `x.y.z` section otherwise.

## [Unreleased]

## [0.2.0] - 2026-10-24

### Added

- Binary compatibility check: the public API is recorded in `api/maxapi.api`, and `./gradlew check`
  fails when it changes without `./gradlew apiDump`.
- `spec/schema.yaml` and a test that checks the client, the model subtypes and the enums against it.
- API reference on GitHub Pages, published with every stable release.
- Release notes are taken from this file.
- Dependabot updates for Gradle dependencies and GitHub Actions.

### Changed

- The examples set `bot.username` from `getMyInfo`, so addressed commands need no extra request.

## [0.1.0-rc1] - 2026-10-24

First release candidate.

### Added

- `MaxClient` with all 33 methods of Max Bot API 0.0.33 as suspend functions, plus the blocking
  `MaxBlockingClient` and the `CompletableFuture`-based `MaxAsyncClient` for Java.
- Typed models for users, chats, messages, attachments, keyboards, markup, updates and
  subscriptions; unknown types and enum values are kept instead of failing.
- File uploads in one call, retries with backoff and `Retry-After` support.
- `MaxBot`: command, message and callback handlers, long polling with per-chat ordering and
  graceful stop, webhook handler with secret verification.
- Kotlin DSL and Java builders for messages and keyboards.

[Unreleased]: https://github.com/SecurityTrip/maxapi/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/SecurityTrip/maxapi/compare/v0.1.0-rc1...v0.2.0
[0.1.0-rc1]: https://github.com/SecurityTrip/maxapi/releases/tag/v0.1.0-rc1
