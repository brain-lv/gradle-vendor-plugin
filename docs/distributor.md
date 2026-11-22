# Distributor plugin (`lv.brain.vendor-distributor`)

[← Back to the README](../README.md)

The distributor plugin turns a folder of vendor jars into a versioned ZIP that holds a ready-made repository. Apply it in `settings.gradle(.kts)` of the build that holds the vendor folder.

## Setup

Suppose you maintain a repository containing third-party or internal JARs organized like this:

```
vendor-sdk-repo/
├── vendor/
│   ├── client/lib/
│   │   ├── example-log-api-2.0.16.jar
│   │   └── example-json-2.11.0.jar
│   └── server/lib/
│       ├── example-util-33.3.1-jre.jar
│       └── custom-protocol-2.4.1.jar
├── vendor.properties
└── settings.gradle.kts

```

### Step 1: Configure `vendor.properties`

Create `vendor.properties` in the root of your distributor repository (on the first run without one, the plugin creates a sample for you, see [The first run](../README.md#the-first-run)):

```
# Required: version of the packaged ZIP and generated bundles
vendor.version=2026.1.0

# Optional: base group name for generated bundles (defaults to lv.brain.vendor)
vendor.defaultGroup=lv.brain.vendor

# Optional: custom folder path if your JARs aren't in ./vendor
# vendor.dir=third-party-jars

# Optional: generate smaller compile-only JARs (-slim.jar)
vendor.slimJars=true

# Optional: group for custom JARs that carry no Maven coordinates of their own
vendor.rule.1=^custom-(.+)-(\d+.*)\.jar$ -> lv.brain.vendor.custom

```

### Step 2: Configure `settings.gradle.kts`

Apply `lv.brain.vendor-distributor` and group your JARs into named categories:

```
plugins {
    id("lv.brain.vendor-distributor") version "<plugin-version>"
}

rootProject.name = "vendor-sdk-distributor"

vendorDistributor {
    slimJars.set(true)

    category("client") {
        include("client/lib/**")
    }

    category("server") {
        include("server/lib/**")
    }
}

```

#### Groovy DSL (`settings.gradle`)

The same configuration in Groovy; use `=` or `.set(...)` for properties:

```groovy
plugins {
    id 'lv.brain.vendor-distributor' version '<plugin-version>'
}

rootProject.name = 'vendor-sdk-distributor'

vendorDistributor {
    slimJars = true
    // vendorDir = new File(rootDir, 'vendor')      // default: <root>/vendor
    // defaultCategory = 'core'                     // keep jars that match no category instead of leaving them out

    category('client') {
        include 'client/lib/**'
    }

    category('server') {
        include 'server/lib/**', '**/shared/*.jar'  // several patterns in one call
    }
}
```

Full control with a closure (it runs first; return `null` to let the `include` patterns and `defaultCategory` decide; a closure disables up-to-date checks and the configuration cache for the vendor tasks):

```groovy
vendorDistributor {
    classifier { String relativePath, File jar ->
        relativePath.startsWith('client/') ? 'client' : null
    }
}
```

#### How `include` works

* Patterns are Ant-style (like Gradle's own `include(...)`) and relative to the vendor directory: `*` stays inside one folder, `**` matches any number of folders (including none), `{a,b}` and `[abc]` are supported.
* `client/lib/**`, `**/client/**`, `client/**/*.jar`, plain `client/lib` and `client/lib/` all select the jars below `client/lib`. A leading `./` or `<vendor dir name>/` (e.g. `vendor/client/**`) is ignored.
* Only jars matched by an `include` become modules and are put into a bundle. Jars that match nothing are not modules (the build log says how many) but are still packaged as plain files; set `vendor.defaultCategory` (or `defaultCategory.set("...")` in the DSL) if they should be kept in a catch-all bundle instead. When `vendor.defaultCategory` is set together with category includes, the build log warns how many jars landed in the default category.
* The generated `ivy/` folder, a top-level `build/` folder and hidden folders and files (`.gradle`, `.vendor`, `.git`, `.idea`, ...) are never scanned or packaged. Leave out more with `vendor.scanExcludes` (folder names or Ant-style patterns, comma separated) or `scanExcludes.add("tools/legacy")` in the DSL.
* A category that matches no jar prints a warning with its patterns, so typos are visible.
* If you declare no category at all, every jar goes into the `core` bundle.
* Categories declared in the `vendorDistributor` block are checked before `vendor.category.<name>` entries of `vendor.properties`; the first category whose include matches a jar wins.
* A jar that exists at several paths with the same coordinates is packaged once and belongs to every category it was found in.
* `include(...)` only means "select these jars" **inside** `category("…") { }`. Anywhere else in `settings.gradle.kts` (directly in `vendorDistributor { }`, or after it) it is Gradle's own project include and would create a bogus project such as `:client/lib/**`; the plugin stops with an explanatory message when that happens.

#### How a jar's `group:artifact:version` is chosen

The plugin uses, in this order: the Maven metadata a jar carries (`pom.properties`, `pom.xml`), then the first `vendor.rule.<n>` that matches the file name, then the jar's manifest, and finally the file name with the group `<vendor.defaultGroup>.<category>`.

Many real SDKs ship open-source jars without Maven metadata (the Spring jars, for example, have only a manifest that names no group) next to the vendor's own jars that carry nothing at all. Map them with rules on the file name, and say what each one is:

| Origin | Meaning | Looked up in Maven Central |
| ----- | ----- | ----- |
| `open-source` (or `oss`) | A public open-source library. | yes |
| `vendor-public` | Vendor code that the vendor also publishes in a public repository. | yes |
| `vendor-private` (or `vendor`) | Vendor code that is not published anywhere. | never |

```properties
# group 1 of the regex is the name, group 2 (optional) the version
vendor.rule.1=^(spring-boot[a-z-]*)-(\d.*)\.jar$ -> org.springframework.boot open-source
vendor.rule.2=^(spring-[a-z-]+)-(\d.*)\.jar$ -> org.springframework open-source
vendor.rule.3=^(acme-sdk-api)-(\d.*)\.jar$ -> com.acme.public vendor-public
vendor.rule.4=^(acme-[a-z-]+?)(?:-(\d[\w.]*))?\.jar$ -> com.acme.platform vendor-private
```

* A rule wins over the manifest, but not over a `pom.properties` or `pom.xml` inside the jar.
* Without a version in the file name, the version comes from the manifest, else `vendor.version`.
* The origin is recorded in `module-dependencies.json` and in the SBOM. Jars without a rule have the origin `unknown`.
* Only `open-source` and `vendor-public` jars, and jars without a rule that carry their own Maven metadata, are looked up in a Maven repository (see [Checking the vendor jars against Maven Central](delivery-checks.md#checking-the-vendor-jars-against-maven-central)); `vendor-private` jars never leave the build.
* Every jar that still ends up in the default group is named in a warning, so none is left unmapped by accident:

  ```
  Vendor: 1 jar(s) got the default group because no pom, manifest group or vendor.rule.<n> names them: ext/legacy-helper.jar. Give them their real coordinates with a rule such as vendor.rule.1=^(name-prefix.*?)-(\d.*)\.jar$ -> <group> open-source, vendor-public or vendor-private.
  ```

A jar that carries several Maven descriptors (a fat or shaded jar with the `pom.properties` and `pom.xml` of every library inside it) is identified by its manifest or file name instead, and its dependencies are found from its code.

If two *different* files end up with the same coordinates, only the first one is packaged and the build log warns about the others; identical copies are merged silently. Use a `vendor.rule.<n>` or rename one of them to keep both.

### Optional: a small compile classpath (`vendor.compilePackages`)

By default every jar of a bundle is on the compile classpath and the runtime classpath. Vendor SDKs usually ship one API of their own plus hundreds of third-party jars that nobody compiles against, and handing all of them to javac and the IDE makes compiling and indexing slow. List the packages of the vendor's own API and only those jars are compiled against; everything else is runtime only:

```properties
# vendor.properties of the distributor (package prefixes, comma separated)
vendor.compilePackages=com.vendor
```

```kotlin
// or in settings.gradle.kts            // or in settings.gradle (Groovy)
vendorDistributor {                     vendorDistributor {
    compilePackages.add("com.vendor")       compilePackages = ['com.vendor']
}                                       }
```

This is where the compile-time gain is: hundreds of jars leave the compile classpath of every consumer (in the [sample](../samples/README.md), 4 of 16 jars are compiled against).

* A jar with classes in `com.vendor` or a subpackage is a **compile jar**. `com.vend` does not match `com.vendor`.
* Jars that the public API of a compile jar needs are added to the compile classpath automatically, so compiling against a bundle does not fail with `class file for ... not found`.
* If javac still needs a jar that is not added automatically, add a package of that jar to `vendor.compilePackages`.
* Every other jar is **runtime only**: a bundle puts it on the runtime classpath but not on the compile classpath, and no slim jar is generated for it.
* `resolveVendorModules` reports how many jars ended up on each side (`--info` lists them), and warns if the packages match nothing.
* Without the setting nothing changes: every jar is on both classpaths.

The consumer plugin needs no change in the build scripts: `implementation vendor.bundle('client')` now simply resolves a smaller compile classpath. A runtime-only module can still be compiled against when it is named explicitly, which is what happens when a project declares the same `group:artifact:version` itself.

### Step 3: Build the Distribution ZIP

Run the packaging task:

```
./gradlew packageVendorDistribution

```

This creates:
`build/distributions/vendor-sdk-2026.1.0.zip`

Upload this ZIP file to your artifact server, S3 bucket, or release page.

Building the same input twice gives the same ZIP.

#### Nothing runs during Gradle sync

Applying `lv.brain.vendor-distributor` does not touch the jars, so an IDE import or sync stays fast no matter how many jars are in the vendor folder. The work happens only when you run one of these tasks:

| Task | Purpose |
| ----- | ----- |
| `resolveVendorModules` | Selects jars by the category includes and resolves the dependencies between them (`build/vendor/module-dependencies.json`). Cacheable. |
| `generateVendorIvyRepo` | Prepares the vendor folder for direct use (`<vendor.dir>/ivy`). Run it once if you point consumers at the folder with `vendor.localDir`. |
| `packageVendorDistribution` | Runs both and builds the distribution ZIP. Cacheable. |

All three are incremental: when no vendor jar or setting changed they are `UP-TO-DATE`. They are registered in the root project of the distributor build, in the task group `vendor`, together with the SBOM, signing, Maven export and diff tasks listed under [Tasks](reference.md#tasks).

The generated `ivy/` folder refers to the jars next to it, so keep `ivy/` inside the vendor folder and do not move jars around after generating it. The ZIP holds the whole vendor folder by default, see [What goes into the ZIP](#what-goes-into-the-zip).

A consumer only accepts distributions with the repository layout of its own plugin version. If a future version changes the layout, the consumer stops with a message asking you to rebuild the distribution with the matching distributor.

**In the distributor build itself** every project gets a `vendor<Category>` configuration (`vendorClient`, `vendorServer`, ...) that depends on the bundle of that category, so the SDK can be compiled and tested from the repository that builds it.

## API-only jars (`vendor.slimJars`)

With `vendor.slimJars=true` the distributor also builds a `-slim.jar` of every compile jar: the same classes and signatures without method bodies and private members. Consumers compile against these and run with the full jars.

The gain is small in most builds: `javac` only reads the classes your code uses, and Gradle reads a vendor jar once and remembers it. It helps most with very large jars on slow disks. The downside is in the IDE: IntelliJ IDEA shows the compile classpath, so navigating into vendor code shows signatures without bodies. Use `vendor.compilePackages` for a small compile classpath; switch on `vendor.slimJars` only when you measure a gain.

To see what Gradle compiles against:

```bash
./gradlew :app:dependencyInsight --configuration compileClasspath --dependency <module>
```

The selected variant is `apiElements` (`org.gradle.usage = java-api`); its file is the `-slim.jar` when slim jars are on. The task `printVendorClasspaths` of the [sample application](../samples/vendor-app/build.gradle.kts) prints both classpaths.

## What goes into the ZIP

`packageVendorDistribution` packages the whole vendor folder, with its folder structure, into `vendor-sdk-<version>.zip`: the jars of every category, the other jars as plain files, and every other file (documentation, configuration, native libraries). The generated `ivy/` folder, `module-dependencies.json` and the bill of materials `vendor-sbom.cdx.json` are always added, and a `<zip>.sha256` checksum file is written next to the ZIP; the folders and files that are never scanned (see [How `include` works](#how-include-works)) are never packaged.

```properties
# vendor.properties of the distributor (comma separated Ant-style patterns or folder names)
vendor.zip.include=client, server, docs
vendor.zip.exclude=docs/internal, **/*.log
```

```kotlin
// or in settings.gradle.kts
vendorDistributor {
    distributionZip { include("client", "server", "docs"); exclude("docs/internal", "**/*.log") }
}
```

* `vendor.zip.include` defaults to every file (`**`; a single `*` means the same). The jars of the categories are packaged even when the patterns leave them out, so the distribution always works.
* **Slim ZIP.** With `vendor.slimZip=true` (or `slimZip.set(true)`), `packageVendorSlimDistribution` builds `vendor-sdk-<version>-slim.zip` as well, and `packageVendorDistribution` runs it too. It holds only the jars of the categories, `ivy/`, `module-dependencies.json` and `vendor-sbom.cdx.json`, plus the files of `vendor.slimZip.include` / `vendor.slimZip.exclude` (DSL: `slimDistributionZip { include(...); exclude(...) }`). Not to be confused with `vendor.slimJars`, which makes the `-slim.jar` files used for compiling.
* Consumers use either ZIP the same way: point `vendor.url` or `vendor.localZip` at the `-slim.zip` to use it. The two are unpacked into separate folders (`<version>` and `<version>-slim`), so switching between them never mixes their contents.
* A changed file that is packaged runs `packageVendorDistribution` again; the other tasks stay up to date.

## Gradle plugins in the vendor folder

A jar in the vendor folder that is a Gradle plugin (it contains `META-INF/gradle-plugins/<id>.properties`) can be applied by its id in every consumer project, without a version, a `buildscript { classpath ... }` block or `apply plugin`:

```kotlin
// build.gradle.kts of a consumer project
plugins {
    java
    id("com.vendor.tool")
}
```

* The distributor records the plugin ids of such jars (`module-dependencies.json` lists them per module). The jar stays in place, and the jars it needs come with it from the module graph, as for any other module.
* Plugin jars are kept out of the category bundles, so they never land on your application's classpath.
* The consumer settings plugin adds the vendor repository to the plugin repositories of the build. If `settings.gradle(.kts)` declares no `pluginManagement { repositories { } }`, the Gradle Plugin Portal stays available as well.
* Only project plugins can be shipped this way. A settings plugin in the vendor folder cannot be used, because the distribution is unpacked only while the consumer settings plugin is applied.
* Two jars that define the same plugin id stop `generateVendorIvyRepo` with a message naming both.

## Build cache

`resolveVendorModules`, `generateVendorIvyRepo` and `generateVendorSbom` can be taken from the Gradle build cache, so a clean checkout, a CI agent or a teammate does not redo the work when the vendor jars and settings are the same. `packageVendorDistribution` is not cached on purpose: building the ZIP from the cached results takes a few seconds, while storing and loading a ZIP of every vendor jar in the cache takes longer. Turn the build cache on in the distributor's `gradle.properties`:

```properties
org.gradle.caching=true
```

* The results do not depend on where the project is checked out, so a shared remote build cache works across machines and CI agents.
* The distribution ZIP and the slim JARs are reproducible: building the same input again gives the same bytes.
* Any change to a vendor JAR's content, a property or a DSL setting runs the affected tasks again; changing a file's timestamp alone does not.
* Without the build cache the tasks are still skipped as `UP-TO-DATE` while nothing changed.
* When some jars change, `resolveVendorModules` reads only those again: what each jar references is kept in `build/vendor/scan-cache` and reused while the jar's content is the same. Deleting the folder is safe; it is filled again on the next run.
* A `classifier { ... }` closure cannot be cached; use category includes to keep the tasks cacheable.

How long it takes, measured on an SDK of 300 jars (282 MB: 20 renamed copies of 15 real open-source jars, so every class exists 20 times), on a laptop (Apple silicon, JDK 17, Gradle 9.9) with a warm Gradle daemon:

| Build | Time |
| ----- | ----- |
| Distributor, first run (scan every jar, generate, package) | 12.8 s |
| Distributor, nothing changed (`UP-TO-DATE`) | 2.0 s |
| Distributor, one jar changed | 13.0 s |
| Distributor, clean checkout with a build cache hit | 6.6 s |
| Consumer, first build (unpack, resolve both classpaths) | 4.9 s |
| Consumer, next build (configuration cache hit) | 1.6 s |

Most of the time of a distributor run goes into writing the ZIP; a consumer pays only for the first build of a version.

On the consumer side, a `vendor.localZip` that is rebuilt with the same version is unpacked again automatically, and a deleted unpacked copy is restored on the next build.

## Direct dependencies only

The plugin finds out which jars each jar needs, and declares only the dependencies that another dependency of the same jar does not already bring in. With jars 1, 2, 3 and 4 where 3 and 2 depend on 4, and 1 uses all of them:

```
before:  1 -> 2, 3, 4     2 -> 4     3 -> 4
after:   1 -> 2, 3        2 -> 4     3 -> 4
```

* Applies to every jar, whatever its group. Jar 1 still gets jar 4, through 2 and 3: the classpath of a project is the same either way; fewer dependencies are just declared.
* Bundles are not reduced: a bundle lists every module of its category.
* `resolveVendorModules` prints how many dependencies were left out (`--info` lists them).

## Leaving classes and jars out of the dependency check

Sometimes a class or a jar must not take part: a legacy package that references half of the SDK, a shaded copy, a test-only jar, or a helper class that closes a circular dependency. The distributor can exclude them:

```properties
# vendor.properties of the distributor (comma separated)
vendor.dependencyExcludeClasses=com.vendor.legacy, com.vendor.util.Internal*, org.shaded.**
vendor.dependencyExcludeJars=client/lib/legacy-, tools/, **/test-*.jar
```

```kotlin
// or in settings.gradle.kts
vendorDistributor {
    dependencyExcludeClasses.add("com.vendor.legacy")
    dependencyExcludeJars.add("client/lib/legacy-")
}
```

```groovy
// or in settings.gradle (Groovy)
vendorDistributor {
    dependencyExcludeClasses = ['com.vendor.legacy']
    dependencyExcludeJars = ['client/lib/legacy-']
}
```

An excluded class or jar is **invisible**: it is treated as if it did not exist in the vendor folder, in both directions. What it references creates no dependency, and what references it creates no dependency on the jar that holds it. The jars are still packaged and still belong to their categories; only the dependency check ignores them.

**Classes** (`vendor.dependencyExcludeClasses`) are named like in Java source, with dots (`com/vendor/Foo` and `com/vendor/Foo.class` work as well):

* `com.vendor.Foo` excludes that class and its inner classes (`Foo$Inner`), nothing else. `com.vendor.FooBar` stays.
* `com.vendor.shaded` excludes everything in that package and below it. `com.vendor.shadedother` stays.
* `*` stands for any characters inside one name segment, `**` for any characters including dots, `?` for one character: `com.*.internal`, `com.vendor.Old*`, `org.**.impl`.

**Jars** (`vendor.dependencyExcludeJars`) are matched against the path of the jar below the vendor directory, with forward slashes:

* A pattern without wildcards is a **prefix** of that path: `client/lib/legacy-` excludes `client/lib/legacy-1.0.jar` and `client/lib/legacy-tools-2.jar`; `tools/` excludes every jar below `tools`.
* A pattern with wildcards is an Ant style pattern, as in the category includes: `**/legacy-*.jar`, `server/lib/{old,ancient}-*.jar`. A comma inside `{ }` belongs to the pattern.
* A leading `<vendor folder name>/` is accepted, as in the category includes.

What it does and does not change:

* Dependencies and circular dependencies caused only by the excluded classes and jars disappear, and the compile classpath follows.
* Which jars are compile jars because they define a class in `vendor.compilePackages` is **not** changed by an exclusion. An excluded class or jar in a compile package still makes its jar a compile jar.
* `resolveVendorModules` names the excluded jars and warns about every pattern that matched nothing, which is nearly always a typo.
* Only the distributor decides this, in `vendor.properties` or in the `vendorDistributor` block (the block wins when both set a list). Consumers have nothing to configure.

## Fixing dependencies by hand

The dependencies between vendor jars come from each jar's `pom.xml` or, without one, from its code. When one is wrong or missing, fix it by name; the names are the `moduleId`s of `build/vendor/module-dependencies.json`:

```properties
# vendor.properties of the distributor (comma separated, from->to)
vendor.dependencyAdd=app-core->commons-lite
vendor.dependencyRemove=app-ui->legacy-tools
```

```kotlin
// or in settings.gradle.kts
vendorDistributor {
    dependencyAdd.add("app-core->commons-lite")
    dependencyRemove.add("app-ui->legacy-tools")
}
```

* An added dependency counts like one listed in a `pom.xml`, so it is never the one dropped to break a circle.
* An unknown name fails the build and lists the known ones; a removed dependency that was not found is a warning.

A `pom.xml` is trusted as it is: a jar with one is not read for its code. To find what a `pom.xml` leaves out, turn on the pom check (`vendor.pomCheck=true`, or `pomCheck.set(true)` in the settings block). It reads those jars too and prints the line to add, without changing anything (`vendor.pomCheck=add` adds them instead):

```
Vendor pom check: app-core uses commons-lite (4 reference(s), e.g. com.acme.Core -> org.lite.Lite), which its pom.xml does not list. If app-core needs it, add vendor.dependencyAdd=app-core->commons-lite
```

## Troubleshooting: circular vendor jar dependencies

When two or more jars depend on each other in a circle, the plugin drops one of those dependencies (all jars are still packaged). The warning names the cycle and why each dependency exists:

```
Breaking circular vendor JAR dependency: alpha -> beta
  cycle: alpha -> beta -> alpha
  alpha -> beta   (removed from the module metadata)
      com.alpha.A references com.beta.B (that class is defined in several jars: beta, beta-copy; the first one is used)
  beta -> alpha
      com.beta.B references com.alpha.A
```

* The dropped dependency is the weakest one of the circle: a dependency listed in a jar's own `pom.xml` is kept over one found in the code, and among those found in the code the one with the fewest references is dropped (on a tie, the first in alphabetical order). The result is the same on every machine and every run.

* The lines below a dependency show why the dependency exists. Three examples are shown; `--info` shows up to ten.
* `(that class is defined in several jars ...)` means a shaded or duplicated copy, which can create dependencies that are not real. Remove the copy or keep both jars in the same category. A jar that carries its own copy of a class always uses that copy, so the copy never makes it depend on another jar.
* Classes that are defined in more than one jar are summed up in one line, `Vendor: <n> class(es) are defined in more than one jar (...)`; `--info` lists the jars and examples, and `module-dependencies.json` has them under `sharedClasses`.
* `(matched by artifactId only, the groupId differs)` is a pom dependency that was linked by artifact name alone.
* If one class is the only reason for a dependency that should not exist, exclude it with `vendor.dependencyExcludeClasses` (see above).
* The warning is only printed when `resolveVendorModules` actually runs; `--rerun` forces the task to run again.
