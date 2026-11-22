# Developing the plugins

[← Back to the README](../README.md)

## Running plugin tests

```
./gradlew test                          unit tests, and the Groovy and Kotlin DSL tests on the Gradle that builds the plugin
./gradlew integrationTest               the same DSL tests plus the full pipeline, on the latest Gradle 8 and Gradle 9
./gradlew check                         both of the above (check -x integrationTest skips the Gradle matrix)

```

The tests cover the Groovy and Kotlin DSL, Isolated Projects and the configuration cache, and the integration tests run on pinned Gradle 8 and 9 versions (currently 8.14.6 and 9.9.1). A Gradle deprecation warning fails these runs.

```
./gradlew integrationTestGradle9 -PintegrationGradle9=9.9.2            another version (same for integrationGradle8)
./gradlew integrationTest -PintegrationGradleHome8=/opt/gradle-8.14.6 \
                          -PintegrationGradleHome9=/opt/gradle-9.9.1   installed distributions instead of a download
./gradlew integrationTest -PintegrationWarningMode=all                 print deprecations instead of failing on them
```

The tests build their own jars and vendor folders, so they need nothing but the Gradle distributions (and, apart from `VendorPluginEndToEndTest`, no Maven repository).

## Updating dependencies

Every dependency of the build is checked against the SHA-256 checksums in `gradle/verification-metadata.xml`. After changing a version, update the checksums with:

```
gradle/update-verification-metadata.sh
```

It downloads everything into an empty Gradle home, adds the checksums of new versions and stops if a version that is already listed is served with a different checksum.

Dependabot proposes updates every Monday, at the earliest 7 days after a release. For its pull requests the workflow `Dependabot verification metadata` runs the same script, commits the new checksums to the pull request and starts CI on that commit. Review the release notes of the update before merging.
