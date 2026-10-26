# Contributing

Bug reports and pull requests are welcome at
[github.com/SecurityTrip/maxapi](https://github.com/SecurityTrip/maxapi).

## Before opening a pull request

```bash
./gradlew build
```

It must pass. It runs the tests of all modules and checks that (the tests of `maxapi-jdbc`,
`maxapi-redis` and the Spring Boot starter start PostgreSQL and Redis with Testcontainers, so they
need Docker):

- every library module keeps 100% line coverage and at least 95% branch coverage
  (`<module>/build/reports/kover/html/index.html` shows what is missing);
- the public API matches the dumps in `<module>/api/`. If you changed the API on purpose, run
  `./gradlew apiDump` and commit the updated dumps with the code;
- the library still matches the API specification in `maxapi/spec/schema.yaml`.

Also:

- add an entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md);
- document public API with KDoc, and update [README.md](README.md) when behavior visible to users changes;
- keep Java usable: `fun interface` for callbacks, builders or `@JvmOverloads` for optional
  parameters, `*Blocking` variants of `suspend` functions;
- declare tests written as `= runBlocking { ... }` with an explicit `: Unit` return type, otherwise
  JUnit may silently skip them.

## Compatibility

After 1.0 the public API follows [semantic versioning](https://semver.org/): no breaking changes in
minor and patch versions. Deprecate first (`@Deprecated` with `ReplaceWith`) and remove in the next
major version. `./gradlew apiCheck` shows every API change in the diff of the dumps.
