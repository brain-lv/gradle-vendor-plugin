# Consumer plugin (`lv.brain.vendor-consumer`)

[← Back to the README](../README.md)

The consumer plugin downloads a vendor distribution, keeps one unpacked copy per version and lets every project of your build use its jars like normal dependencies. Apply it in `settings.gradle(.kts)` of the application build.

## Setup

### Step 1: Configure `vendor.properties`

In the root of your application repository, create `vendor.properties` (commit this file to Git; on the first run without one, the plugin creates a sample for you, see [The first run](../README.md#the-first-run)):

```
vendor.version=2026.1.0
vendor.defaultGroup=lv.brain.vendor
vendor.url=https://artifacts.example.com/vendor-sdk/vendor-sdk-{version}.zip

```

> **Note:** `{version}` is automatically replaced with `vendor.version`.

### Step 2: Apply the Settings Plugin in `settings.gradle.kts`

```
plugins {
    id("lv.brain.vendor-consumer") version "<plugin-version>"
}

rootProject.name = "my-enterprise-app"
include(":app-server")

```

### Step 3: Add Dependencies in `app-server/build.gradle.kts`

Apply `lv.brain.vendor-consumer-project` to use the `vendor` helper inside `dependencies`:

```
plugins {
    java
    id("lv.brain.vendor-consumer-project")
}

repositories {
    mavenCentral()
}

dependencies {
    // Pulls in every JAR grouped under the "client" and "server" categories
    implementation(vendor.bundle("client"))
    implementation(vendor.bundle("server"))

    // Or depend on a single module from the vendor SDK, by name (add the group when the name is not unique)
    // implementation(vendor.module(name = "example-json"))
    // implementation(vendor.module(group = "org.example.json", name = "example-json"))
}

```

In a Groovy build script the same is `implementation vendor.bundle('client')` and `implementation vendor.module('example-json')` or `implementation vendor.module('org.example.json', 'example-json')`. No `plugins` entry is needed there: the settings plugin applies the project plugin to every project. The Kotlin DSL needs the entry to get the typed `vendor` accessor.

`vendor.module` returns the exact coordinates the distribution publishes the module under; its version is the version of the jar, not the version of the distribution. A name that exists in several groups needs the group, and an unknown name fails with the list of available modules.

When you run `./gradlew build`:

1. Gradle downloads `vendor-sdk-2026.1.0.zip` once and extracts it to `~/.gradle/caches/vendor-sdk/2026.1.0/`.

2. Compilation (`compileClasspath`) uses the jars the distribution marks for compiling: only the vendor's API with `vendor.compilePackages`, and the `-slim.jar` copies with `vendor.slimJars`.

3. Runtime and tests (`runtimeClasspath`, `testRuntimeClasspath`) automatically resolve the full original JARs.

**Downloading.** The archive is downloaded once per version and does not interfere with your other repositories. `vendor.authToken` is sent as HTTP Basic authentication with the user name `token`; plain `http://` URLs are accepted (`vendor.allowInsecureProtocol` defaults to `true` for them). Builds running at the same time share one unpacked copy.

The consumer plugin applies the `java` plugin to a project that has no Java plugin of its own, so `implementation` and the `vendor` helper exist everywhere.

## Local development and overrides

To test a locally built vendor ZIP or an unpacked folder without touching the committed `vendor.properties`, create `vendor.local.properties` next to it in the consumer project root. Git ignores this file automatically (see [Keeping local files out of Git](#keeping-local-files-out-of-git)), and its values override `vendor.properties` (`-P`, `-D` and the environment variables override both). Relative paths are resolved against the consumer project root. `vendor.url` can stay set in `vendor.properties`; nothing is downloaded when a local path is configured.

```
# Option A: the zip made by `./gradlew packageVendorDistribution` in the distributor project.
# It is unpacked once below ~/.gradle/caches/vendor-sdk/<version> (or .vendor/<version> with vendor.cacheMode=project).
vendor.localZip=../vendor-sdk-repo/build/distributions/vendor-sdk-2026.1.0.zip
# vendor.cacheMode=project

# Option B: the distributor's vendor folder itself, used in place (no unpacking).
# Run `./gradlew generateVendorIvyRepo` in the distributor project first, it creates <vendor.dir>/ivy.
# vendor.localDir=../vendor-sdk-repo/vendor
```

Only consumer keys belong in the consumer's files. `vendor.dir`, `vendor.slimJars`, `vendor.defaultCategory`, `vendor.compilePackages`, `vendor.dependencyExcludeClasses`, `vendor.dependencyExcludeJars`, `vendor.scanExcludes`, `vendor.zip.*`, `vendor.slimZip*`, `vendor.patcher`, `vendor.patchesDir`, `vendor.rule.<n>` and `vendor.category.<name>` are distributor settings and are rejected there (and the consumer keys are rejected in a distributor).

Smoke check after pointing the consumer at a local SDK: run `./gradlew compileJava`, and add this to the consumer's `build.gradle` to see which files are resolved (slim jars for compilation, original jars at runtime):

```groovy
tasks.register('printClasspaths') {
    doLast {
        println 'COMPILE=' + configurations.compileClasspath.files*.name.sort()
        println 'RUNTIME=' + configurations.runtimeClasspath.files*.name.sort()
    }
}
```

You can also override any setting via Gradle `-P` flags or environment variables:

```
./gradlew build -Pvendor.version=2026.2.0-SNAPSHOT

```

In a Groovy `build.gradle` the consumer helper works without the `plugins {}` block: `implementation vendor.bundle('client')`.

The `vendor` helper is available in every project before its build script runs, so this works in the root `build.gradle` too:

```groovy
subprojects {
    dependencies {
        implementation vendor.bundle('client')
    }
}
```

### Keeping local files out of Git

`vendor.local.properties` and the `.vendor/` folder (used with `vendor.cacheMode=project`) belong to one developer's machine and must never be committed. Both plugins keep them out of Git for you:

* Nothing is written to `.gitignore`, so applying the plugin leaves no change for you to commit.
* The entries are added to the repository's local exclude file, `.git/info/exclude`. Git reads it just like a `.gitignore`, but it is never committed or shared.
* It also works when the Gradle project is not at the root of the repository. For example, with this layout:

  ```
  my-repo/
  ├── .git/
  └── consumer-project/
      ├── settings.gradle.kts
      ├── vendor.properties
      └── vendor.local.properties
  ```

  `my-repo/.git/info/exclude` gets:

  ```
  # Added automatically by lv.brain.gradle vendor plugin
  /consumer-project/vendor.local.properties
  /consumer-project/.vendor/
  ```

* Only those two paths are excluded, so a `vendor.local.properties` or `.vendor` folder elsewhere in the repository is not affected.
* Entries that are already there are not added again, and anything else in the exclude file is left as it is.
* Outside a Git repository the plugin does nothing.

To check that it works, run `git status` after the first build: neither `vendor.local.properties` nor `.vendor/` should be listed. `git check-ignore -v consumer-project/vendor.local.properties` shows the line that excludes the file.

**Turning it off.** Set `vendor.gitExclude=false` if you manage ignores yourself (for example, a committed `.gitignore` shared by the whole team):

```
# vendor.properties (or vendor.local.properties)
vendor.gitExclude=false
```

It can also be set for a single run with `-Pvendor.gitExclude=false` or the `VENDOR_GIT_EXCLUDE=false` environment variable. Turning it off does not remove entries that were already added: delete the lines below the `# Added automatically by lv.brain.gradle vendor plugin` comment in `.git/info/exclude` if you want them gone.

## Gradle versions and Isolated Projects

Both settings plugins need **Gradle 8.11 or newer** and are compatible with the configuration cache and with **Isolated Projects** (`org.gradle.isolated-projects=true`, or `org.gradle.unsafe.isolated-projects=true` before Gradle 9.7). An older Gradle fails at once with a message that names the plugin and both versions. Every release is tested on the latest Gradle 8 and Gradle 9, and checked to use only the Kotlin standard library that Gradle 8.11 runs plugins with.

What that means for your builds:

* **Each project declares its own vendor dependencies.** `dependencies { implementation(vendor.bundle("client")) }` in the script of the project that needs it works as before, with or without Isolated Projects.
* **`allprojects { }` / `subprojects { }` in the root script** that use `implementation(...)` or `vendor.bundle(...)` configure the other projects from the outside, which Isolated Projects forbids for every plugin. Without Isolated Projects the consumer plugin still prepares all projects before the root script runs, so those scripts keep working. With Isolated Projects, move the `dependencies { }` block into the script of each project, or into a convention plugin that each project applies.
* **The distributor** tasks (`resolveVendorModules`, `generateVendorIvyRepo`, `packageVendorDistribution`) live in the root project.
* **`vendorDistributor { classifier { ... } }`** is a lambda and cannot be stored in the configuration cache. The build still runs, but its cache entry is discarded. Use category includes (`category("x") { include(...) }` or `vendor.category.<name>`) to keep the cache.
* The configuration cache entry is reused from the second build on, also right after a new version was downloaded.
* The authentication token is only used to download the archive; it is not stored in the configuration cache.

## Several vendor distributions in one build

A consumer build can use the distributions of several vendors. Name them in `vendor.distributions` and configure each with its own keys:

```properties
# vendor.properties of the consumer
vendor.distributions=acme, beta

vendor.acme.version=2026.1.0
vendor.acme.url=https://repo.example.com/acme/vendor-sdk-{version}.zip

vendor.beta.version=5.4.0
vendor.beta.defaultGroup=com.beta
vendor.beta.url=https://repo.example.com/beta/vendor-sdk-{version}.zip
vendor.beta.signing.publicKey=MCowBQYDK2VwAyEA...
```

```kotlin
dependencies {
    implementation(vendor.from("acme").bundle("client"))
    implementation(vendor.from("beta").module(name = "beta-core"))
}
```

* Every key of a single distribution works per name: `vendor.<name>.version`, `url`, `localZip`, `localDir`, `authToken`, `sha256`, `signing.publicKey`, `defaultGroup`, `allowInsecureProtocol`. The environment variables are `VENDOR_<NAME>_VERSION`, `VENDOR_<NAME>_URL`, and so on.
* The unnamed distribution (`vendor.version`, `vendor.url`, ...) can stay next to the named ones and is used by `vendor.bundle(...)` directly. Without it, `vendor.bundle(...)` fails and asks for `vendor.from(...)`.
* Each distribution is unpacked into its own folder (`<cache>/<name>/<version>`), so two vendors with the same version number never mix, and each module is looked up only in the distribution that has it.
* `vendor.cacheMode`, `vendor.clashCheck`, `vendor.clashIgnore`, `vendor.duplicateClasses` and `vendor.gitExclude` apply to all of them.
* When two distributions publish the same module (`group:name:version`) with different content, for example two bundles named `lv.brain.vendor.bundle:core` because both vendors kept the default group, the build fails and lists them. Build one of them with its own `vendor.defaultGroup` and set `vendor.<name>.defaultGroup` to the same value.

## Vendor jars that clash with your own dependencies

When the distribution was built with `vendor.compilePackages`, your project compiles against a few vendor jars but **runs** with all of them, including the vendor's copies of third-party libraries (for example `org.lite:commons-lite:3.8`). If your project also depends on that library at another version, it would compile against one jar and run with another. The plugin makes that a build error: the task `verifyVendorClasspath` (`verifyTestVendorClasspath` for the test source set, and so on) runs before `compileJava` and fails when the **same `group:artifact` has a different version**:

```
Vendor runtime jars clash with the dependencies of root project 'app' (source set 'main'):

  org.lite:commons-lite
      vendor distribution:  3.8
      project ':' asks for 3.12  [compile + runtime classpath]
      runtime classpath gets:  3.12
```

It counts as a clash when

1. the runtime classpath does not end up with the version the vendor distribution asks for, because your project or one of your dependencies requires another one, or
2. a dependency declared by one of your projects, `compileOnly` included, asks for another version than the vendor does.

A third-party library that asks for a *lower* version than the vendor's is not a clash (the vendor version wins, as usual). Ways out: use the vendor's version in your build, take the library from the vendor bundle, or accept the difference with `vendor.clashIgnore`.

* The check is fast, is skipped while nothing changed, and never runs during a Gradle sync.
* It only knows the coordinates the distribution gives a jar. A vendor jar whose identity was guessed from its file name (group `<vendor.defaultGroup>.<category>`) is not recognised as `org.lite:commons-lite`, so versions cannot be compared. When your runtime classpath has a library with the same artifact name, the check prints a warning instead, because both jars may end up on the classpath:

  ```
  Vendor jars named only by their file may be the same library as a dependency of project ':app' (source set 'main'):
    vendor lv.brain.vendor.core:commons-lite:3.8  and  org.lite:commons-lite:3.12 (asked for by project ':app')
  ```

  Give the jar its real coordinates with a `vendor.rule.<n>` on the distributor side and the clash check compares the versions, or list either coordinate in `vendor.clashIgnore`. This warning also runs for distributions without runtime-only jars, as long as `vendor.clashCheck` is `auto`.
* It is active when the distribution has runtime-only jars (`vendor.clashCheck=auto`, the default). Older distributions, and distributions built without `vendor.compilePackages`, are not checked for clashes; they only get the warning about jars named by their file.
