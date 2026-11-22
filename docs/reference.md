# Reference

[← Back to the README](../README.md)

Every property, DSL option, task and error message of the plugins.

## Distributor properties (`vendor.properties`) and their DSL names

| Property | DSL (`vendorDistributor { }`) | Env variable | Default | Description |
| ----- | ----- | ----- | ----- | ----- |
| `vendor.version` | — | `VENDOR_VERSION` | *(required)* | Release version of the SDK and bundles. |
| `vendor.defaultGroup` | — | — | `lv.brain.vendor` | Default Maven/Ivy group for bundles and unmatched JARs. |
| `vendor.dir` | `vendorDir` | `VENDOR_DIR` | `vendor` | Directory containing the vendor JARs to scan. |
| `vendor.slimJars` | `slimJars` | — | `false` | When `true`, creates API-only `-slim.jar` files for compilation. See [API-only jars](distributor.md#api-only-jars-vendorslimjars). |
| `vendor.defaultCategory` | `defaultCategory` | — | `core` | Category for JARs that don't match any `category` rule. When not set explicitly: used for everything if no category is declared, otherwise unmatched JARs are left out. |
| `vendor.compilePackages` | `compilePackages` | — | *(not set)* | Package prefixes (comma separated, `com.vendor`) whose jars are compiled against. Every other jar is runtime only, except jars that the public API of a compile jar needs. Not set: all jars are on the compile classpath. |
| `vendor.dependencyExcludeClasses` | `dependencyExcludeClasses` | — | *(not set)* | Classes and packages (comma separated, `com.vendor.legacy`, `com.vendor.Old*`) that the check for dependencies between the jars does not see: they create no dependency, and no dependency on their jar. |
| `vendor.dependencyExcludeJars` | `dependencyExcludeJars` | — | *(not set)* | Jars (comma separated) that the check does not see, as a prefix of the path below `vendor.dir` (`client/lib/legacy-`) or an Ant-style pattern. They are still packaged. |
| `vendor.rule.<n>` | — | — | — | Regex rule `<regex> -> <group> [open-source\|vendor-public\|vendor-private]` for JARs without Maven metadata; it wins over the manifest. Rules are tried in the alphabetical order of their keys (`vendor.rule.10` comes before `vendor.rule.2`). See [How a jar's `group:artifact:version` is chosen](distributor.md#how-a-jars-groupartifactversion-is-chosen). |
| `vendor.mavenCheck.url` | — | `VENDOR_MAVEN_CHECK_URL` | Maven Central | The repository `verifyVendorAgainstMaven` compares with. |
| `vendor.mavenCheck.failOn` | — | — | *(not set)* | `differs`, `notfound`, `dependencies`, `licenses` (comma separated): findings of `verifyVendorAgainstMaven` that fail the build. |
| `vendor.category.<name>` | `category("<name>") { include(...) }` | — | — | Comma-separated Ant-style patterns (relative to `vendor.dir`) assigning jars to a category; same rules as `include(...)`. |
| `vendor.gitExclude` | — | `VENDOR_GIT_EXCLUDE` | `true` | Keeps `vendor.local.properties` and `.vendor/` out of Git. See [Keeping local files out of Git](consumer.md#keeping-local-files-out-of-git). |
| `vendor.patcher` | `patcher` | — | `false` | Applies the numbered patches before scanning and packaging. See [Patching vendor files](patcher.md). |
| `vendor.patchesDir` | `patchesDir` | — | `internal-patches` | Folder with the numbered patch files. |
| `vendor.dependencyAdd` | `dependencyAdd` | — | *(not set)* | Dependencies added by hand, comma separated `from->to` module names. See [Fixing dependencies by hand](distributor.md#fixing-dependencies-by-hand). |
| `vendor.dependencyRemove` | `dependencyRemove` | — | *(not set)* | Found dependencies that are dropped, comma separated `from->to` module names. |
| `vendor.pomCheck` | `pomCheck` | — | `off` | `warn` also reads the code of jars with a `pom.xml` and warns about vendor jars it uses that the `pom.xml` does not list; `add` adds those dependencies too. `true`/`false` mean `warn`/`off`. |
| `vendor.missingClasses` | `missingClasses` | — | `warn` | `warn`, `fail` or `off` for classes that vendor code uses but no vendor jar or the JDK has. See [Delivering installations](delivery-checks.md#delivering-installations). |
| `vendor.missingClasses.scope` | — | — | `vendor` | `vendor`: classes that only open-source jars use are only counted; `all`: they are reported like the others. |
| `vendor.licenses.allow` | — | — | *(not set)* | Licence patterns a jar may have (SPDX ids or names, `*` wildcard). See [Licence policy](delivery-checks.md#licence-policy). |
| `vendor.licenses.deny` | — | — | *(not set)* | Licence patterns no jar may have. |
| `vendor.licenses.unknown` | — | — | `warn` | `warn`, `fail` or `off` for jars without a declared licence, while a policy is set. |
| `vendor.publish.url` | — | `VENDOR_PUBLISH_URL` | *(not set)* | The Maven repository `publishVendorMavenRepository` uploads to (`https://...` or `file:...`). |
| `vendor.publish.username` | — | `VENDOR_PUBLISH_USERNAME` | *(not set)* | The user for `vendor.publish.url`; the password comes from the Gradle property `vendor.publish.password` or `VENDOR_PUBLISH_PASSWORD`. |
| `vendor.jpmsCheck` | `jpmsCheck` | — | `warn` | `warn`, `fail` or `off` for jars that would break on the Java module path. |
| `vendor.signing.key` | — | `VENDOR_SIGNING_KEY` (the key itself) | *(not set)* | The private key file that signs the distribution ZIPs. See [Signed distributions](delivery-checks.md#signed-distributions). |
| `vendor.diff.baseline` | — | — | *(not set)* | The earlier distribution ZIP that `diffVendorDistribution` compares with (or pass `--baseline=<zip>`). |
| `vendor.scanExcludes` | `scanExcludes` | — | *(not set)* | Folders and files below `vendor.dir` (comma separated folder names or Ant-style patterns) that are never scanned or packaged, in addition to `ivy/`, a top-level `build/` and hidden folders. |
| `vendor.zip.include` | `distributionZip { include(...) }` | — | `**` | Files of `vendor.dir` packaged into the distribution ZIP. The jars of the categories are always packaged. |
| `vendor.zip.exclude` | `distributionZip { exclude(...) }` | — | *(not set)* | Files left out of the distribution ZIP. |
| `vendor.slimZip` | `slimZip` | — | `false` | Also builds `vendor-sdk-<version>-slim.zip`. See [What goes into the ZIP](distributor.md#what-goes-into-the-zip). |
| `vendor.slimZip.include` | `slimDistributionZip { include(...) }` | — | *(not set)* | Further files packaged into the slim ZIP. |
| `vendor.slimZip.exclude` | `slimDistributionZip { exclude(...) }` | — | *(not set)* | Files left out of the slim ZIP. |
| — | `classifier { relativePath, jar -> category or null }` | — | — | Custom classifier, runs before the includes. Disables up-to-date checks and the configuration cache for the vendor tasks. |

## Consumer properties (`vendor.properties` / `vendor.local.properties`)

| Property | Env variable | Default | Description |
| ----- | ----- | ----- | ----- |
| `vendor.version` | `VENDOR_VERSION` | *(required)* | Version of the vendor SDK to download/resolve. |
| `vendor.defaultGroup` | — | `lv.brain.vendor` | Group prefix used by `vendor.bundle(...)`. |
| `vendor.url` | `VENDOR_URL` | — | Download URL template (supports `{version}` placeholder). |
| `vendor.authToken` | `VENDOR_AUTH_TOKEN` | — | Optional HTTP Basic auth token for private artifact servers. |
| `vendor.allowInsecureProtocol` | — | Auto (`true` for `http://`) | Allows plain HTTP URLs in Gradle 8+. |
| `vendor.cacheMode` | `VENDOR_CACHE_MODE` | `gradle` | `gradle` (`~/.gradle/caches/vendor-sdk`) or `project` (`.vendor/`). `vendor.cache` / `VENDOR_CACHE` is accepted as an alias. |
| `vendor.localZip` | `VENDOR_LOCAL_ZIP` | — | Local ZIP file path override (skips remote download). |
| `vendor.localDir` | `VENDOR_LOCAL_DIR` | — | Local unpacked directory override (skips ZIP extraction). |
| `vendor.clashCheck` | — | `auto` | `auto` checks when the distribution has runtime-only jars, `true` always, `false` never. See "Vendor jars that clash with your own dependencies". |
| `vendor.clashIgnore` | — | — | Comma-separated `group:artifact` patterns (`*` is a wildcard) that the clash check skips. |
| `vendor.sha256` | `VENDOR_SHA256` | — | The SHA-256 the distribution ZIP must have; checked before it is unpacked. |
| `vendor.signing.publicKey` | `VENDOR_SIGNING_PUBLIC_KEY` | — | The public key (file path or one-line base64) whose signature the ZIP must carry. |
| `vendor.distributions` | — | — | Names of further distributions, each configured with `vendor.<name>.<key>`. See [Several vendor distributions in one build](consumer.md#several-vendor-distributions-in-one-build). |
| `vendor.duplicateClasses` | — | `warn` | `warn`, `fail` or `off` for classes a vendor jar shares with another jar of the runtime classpath. |
| `vendor.gitExclude` | `VENDOR_GIT_EXCLUDE` | `true` | Keeps `vendor.local.properties` and `.vendor/` out of Git. See [Keeping local files out of Git](consumer.md#keeping-local-files-out-of-git). | 

**Precedence**, highest first: `-Pkey=value`, `-Dkey=value`, the environment variable (where the table lists one), `vendor.local.properties`, `vendor.properties`. `vendor.rule.<n>` and `vendor.category.<name>` are only read from the files.

## Consumer helper (`vendor { }`)

| Call | Result |
| ----- | ----- |
| `vendor.bundle("client")` | `<vendor.defaultGroup>.bundle:client:<vendor.version>`: every module of the category (its compile jars on the compile classpath, all of them at runtime). |
| `vendor.module(name = "x")`, `vendor.module(group = "g", name = "x")` | The exact coordinates the distribution publishes the module under. Unknown, ambiguous (several groups or versions) and bundle names fail with an explanation. |
| `vendor.from("acme")` | A named distribution of `vendor.distributions`, with the same `bundle`, `module`, `file` and `files`. |
| `vendor.files("conf")` | A file tree of that folder of the unpacked distribution, for `Copy`, `Zip` or `distributions { }`. |
| `vendor.file("conf/app.properties")` | One file of the unpacked distribution. |

## Tasks

| Task | Where | Output |
| ----- | ----- | ----- |
| `resolveVendorModules` | distributor, root project | `build/vendor/module-dependencies.json` |
| `generateVendorIvyRepo` | distributor, root project | `<vendor.dir>/ivy/` |
| `packageVendorDistribution` | distributor, root project | `build/distributions/vendor-sdk-<vendor.version>.zip` |
| `packageVendorSlimDistribution` | distributor, root project, with `vendor.slimZip=true` | `build/distributions/vendor-sdk-<vendor.version>-slim.zip` |
| `generateVendorSbom` | distributor, root project | `build/vendor/vendor-sbom.cdx.json` (also in the ZIP) |
| `vendorReport` | distributor, root project | `build/reports/vendor/index.html` |
| `verifyVendorAgainstMaven` | distributor, root project | `build/reports/vendor-maven-check/report.txt`, `report.json` |
| `generateVendorSigningKey` | distributor, root project | `build/vendor-signing/vendor-signing.key` and `.pub` |
| `diffVendorDistribution` | distributor, root project | `build/reports/vendor-diff/diff.txt`, `diff.json` |
| `exportVendorMavenRepository` | distributor, root project | `build/vendor-maven-repo/` |
| `publishVendorMavenRepository` | distributor, root project | uploads to `vendor.publish.url` |
| `applyVendorPatches` | patcher (distributor root project with `vendor.patcher=true`) | `build/vendor-patched/<vendor folder name>/` |
| `checkVendorPatches` | patcher | `build/vendor-patches/check.ok`, report in `build/reports/vendor-patches/` |
| `vendorPatchStatus` | patcher | one line per step, report in `build/reports/vendor-patches/status.txt` |
| `prepareVendorPatchWorkspace` | patcher | `build/vendor-patch-workspace/<vendor folder name>/` (`--reset` discards unsaved changes) |
| `createVendorPatch` | patcher | the next numbered steps in the patches folder (`--name=<what it fixes>`) |
| `refreshVendorPatches` | patcher | rewrites `.patch` steps that apply only at moved lines |
| `verify<SourceSet>VendorClasspath` (`verifyVendorClasspath`, `verifyTestVendorClasspath`, ...) | consumer, every project with a Java plugin, unless the clash check and `vendor.duplicateClasses` are both off | `build/vendor/<task name>.ok` |

## `module-dependencies.json`

`resolveVendorModules` writes a report to `build/vendor/module-dependencies.json` (it is also included in the ZIP). Use it when troubleshooting: it lists every selected module with its coordinates, category, whether it is a compile jar, where its coordinates come from (`identity`: `pom.properties`, `pom.xml`, `manifest`, `rule` or `file-name`), the Gradle plugin ids it defines, and its dependencies, its SHA-256, Java version and licenses, together with the classes found in several jars (`sharedClasses`), the classes no jar has (`missingClasses`), the findings of the pom check (`pomCheck`), the excluded jars, the dependencies left out as redundant, and the circular dependencies that were broken.

## Errors and what they mean

| Message starts with | Cause and fix |
| ----- | ----- |
| `The plugin '...' needs Gradle 8.11 or newer` | Upgrade the wrapper: `./gradlew wrapper --gradle-version 8.11` (or newer). |
| `No vendor.properties was found, so the ... plugin created a sample` / `... is still the generated sample` | Adjust the generated `vendor.properties` as its comments explain. See [The first run](../README.md#the-first-run). |
| `Missing required 'vendor.version'` | Set `vendor.version` in `vendor.properties`, `vendor.local.properties` or with `-Pvendor.version`. |
| `Either 'vendor.url', 'vendor.localZip', or 'vendor.localDir' must be configured.` | The consumer has nowhere to get the distribution from. |
| `Configured vendor.localDir does not exist` | Fix the path (relative paths are resolved against the consumer project root). |
| `Expected Ivy repository directory not found` | The unpacked distribution has no `ivy/` folder: it was not built by `packageVendorDistribution`, or `generateVendorIvyRepo` has not run for a `vendor.localDir`. Rebuild it. |
| `The vendor distribution in ... was built by an older distributor` | The distribution was built by a plugin version with another repository layout. Rebuild it with `packageVendorDistribution` of the same plugin version as the consumer and publish it again. |
| `Vendor modules share a repository folder` | Two jars get coordinates that map to the same folder (for example `a-1:2` and `a:1-2`). Give one of them another identity with a `vendor.rule.<n>`. |
| `Vendor module '...' has the group/name/version '...', which cannot be used` | The coordinates contain `/`, `\`, `..` or `:`. Give the jar another identity with a `vendor.rule.<n>`. |
| `vendor.dependencyAdd / vendor.dependencyRemove name unknown module(s)` | Use the `moduleId`s of `build/vendor/module-dependencies.json`; the message lists them. |
| `These dependencies are both added and removed` | Keep each `from->to` in one of the two lists only. |
| `Invalid vendor.dependencyAdd entry` | Write each entry as `from->to`. |
| `Vendor: <n> class(es) in <m> package(s) are used by vendor jars but are in no vendor jar` | A jar is missing from the vendor folder, or the classes are optional. See [Delivering installations](delivery-checks.md#delivering-installations). |
| `The vendor distribution ... has the SHA-256 ..., but vendor.sha256 expects ...` | The ZIP is damaged or another release; check `vendor.version` and `vendor.url`, or update `vendor.sha256`. |
| `vendor.file(s)('...'): ... is not in the vendor distribution` | The distributor did not package that folder (`vendor.zip.include`), or the consumer uses a slim ZIP. |
| `Vendor jars share classes with other jars of the runtime classpath` | See "Duplicate classes" in [Delivering installations](delivery-checks.md#delivering-installations). |
| `... is only compatible with JVM runtime version 21 or newer` / `... compatible with Java 21` | A vendor jar needs a newer Java than the project targets. Raise the project's Java version or ask the vendor for an older build. |
| `Vendor module path check: ...` | A warning: jars with the same module name, or packages split between named modules. See [Delivering installations](delivery-checks.md#delivering-installations). |
| `... has no signature` / `The signature of the vendor distribution ... does not match vendor.signing.publicKey` | The `.sig` file is missing, or the ZIP was changed or signed with another key; nothing was unpacked. |
| `Vendor distributions publish the same module with different content` | See [Several vendor distributions in one build](consumer.md#several-vendor-distributions-in-one-build). |
| `This build has no default vendor distribution` / `vendor.from('...'): there is no vendor distribution` | Use `vendor.from("<name>")` with a name of `vendor.distributions`. |
| `diffVendorDistribution needs the earlier distribution` | Pass `--baseline=<zip>` or set `vendor.diff.baseline`. |
| `Uploading ... failed with HTTP ...` | Check `vendor.publish.url` and the credentials; a release repository refuses a version that is already there. |
| `Vendor: <n> jar(s) got the default group` | A warning: add a `vendor.rule.<n>` with `open-source`, `vendor-public` or `vendor-private` for those jars. See [How a jar's `group:artifact:version` is chosen](distributor.md#how-a-jars-groupartifactversion-is-chosen). |
| `Vendor Maven check: ... is not the published jar` | The vendor ships a changed copy of an open-source jar. See [Checking the vendor jars against Maven Central](delivery-checks.md#checking-the-vendor-jars-against-maven-central). |
| `Vendor licence policy: <n> jar(s) have a licence that ... do not accept` | Remove the jars, accept the licence in `vendor.licenses.allow`, or mark the vendor's own code `vendor-private`. See [Licence policy](delivery-checks.md#licence-policy). |
| `Vendor licence policy: <n> jar(s) declare no licence` | A warning; run `verifyVendorAgainstMaven` to read their published licences, or set `vendor.licenses.unknown=off`. |
| `Vendor Maven check failed (vendor.mavenCheck.failOn)` | A finding listed in `vendor.mavenCheck.failOn`; the report names the jars. |
| `Vendor pom check: ...` | A warning: a `pom.xml` misses a dependency the code uses. Add it with `vendor.dependencyAdd` if it is needed. |
| `The Gradle plugin id '...' is defined by two vendor jars` | Keep only one of the jars, or leave one out with `vendor.scanExcludes`. |
| `Invalid properties for distributor/consumer in vendor.properties` | A key of the other role is in the file, see "Consumer properties" and "Distributor properties". |
| `The settings script calls include(...) with vendor file pattern(s) outside a category` | Move the pattern into `category("<name>") { include(...) }`. |
| `vendor.module(): no module ...` / `vendor.module(): '<name>' exists in several groups` | Check the name against the listed modules, or pass the group. |
| `Vendor runtime jars clash with the dependencies of ...` | See "Vendor jars that clash with your own dependencies". |
| `Breaking circular vendor JAR dependency` | A warning, not an error: see "Troubleshooting: circular vendor jar dependencies". |
| `Vendor: <n> class(es) are defined in more than one jar` | Information: shaded or copied classes. `--info` names the jars; see "Troubleshooting: circular vendor jar dependencies". |
| `Vendor jars named only by their file may be the same library as ...` | A warning of the clash check: see "Vendor jars that clash with your own dependencies". |
| `Vendor patch step <n> of <m> failed` | A patch step does not apply; the message names the file, the hunk, the likely cause and the fix. See [Patching vendor files](patcher.md). |
| `Invalid patch steps in ...` | Two steps share a number, or a numbered file is not a `.patch`/`.diff`. |
