# Delivery checks

[← Back to the README](../README.md)

What the distributor checks before a vendor delivery reaches your customers, and how to review it.

## Reviewing the module graph

```bash
./gradlew vendorReport
```

writes `build/reports/vendor/index.html`, a page of everything `resolveVendorModules` found. It opens in any browser without a network connection, so it can be attached to a ticket or sent to the vendor.

* **Needs attention** collects what you should look at: circular dependencies that were broken (with the reasons for each edge), classes vendor jars use but no jar has, jars that only got the default group, module path problems, dependencies a `pom.xml` does not list, and jars without a licence.
* **For information** lists the packages that only open-source jars miss (usually optional dependencies) and the jars that define the same classes.
* **Jars** lists every jar with its coordinates, categories and whether it is on the compile classpath. Open one to see where its identity, origin, licence and Java version come from, why it is on the compile classpath, each dependency with the classes that cause it, the dependencies that were left out because another one brings them, and the jars that use it. Filter by name, group, licence or category, or show only compile jars or jars that need attention.

The page is written from `module-dependencies.json`, so it always matches the distribution.

## Delivering installations

The plugins check a vendor delivery before your customer runs it, and hand you what an installation needs.

**On the distributor side**

* **Missing classes.** `resolveVendorModules` warns when vendor code uses classes that are in no vendor jar and not in the JDK, so a jar the vendor forgot to ship is found at build time, not as a `NoClassDefFoundError` at the customer:

  ```
  Vendor: 3 class(es) in 1 package(s) are used by vendor jars but are in no vendor jar and not in the JDK (Java 17): org.slf4j (3 class(es), used by commons-lite). Add the missing jars to the vendor folder, leave them out with vendor.dependencyExcludeClasses if they are optional, or set vendor.missingClasses=off. --info lists the classes; module-dependencies.json has them under missingClasses.
  ```

  `vendor.missingClasses=fail` turns it into an error. Classes used only as annotations are not reported.

  Open-source libraries often refer to optional dependencies (Spring Boot, for example, has code for Logback, Tomcat and many more that only runs when they are there). Classes that only open-source jars use (jars marked `open-source`, and jars without a rule that carry their own Maven metadata) are therefore only counted, never failed on:

  ```
  Vendor: 1977 class(es) in 682 package(s) that only open-source jars use are not in the distribution, usually optional dependencies of those libraries (--info lists them; vendor.missingClasses.scope=all reports them as missing).
  ```

  `vendor.missingClasses.scope=all` reports them like the others.
* **Java version.** Each module declares the Java version its classes need. A project that targets an older Java gets Gradle's own error at once, naming the jar, instead of an `UnsupportedClassVersionError` at runtime.
* **Bill of materials.** `generateVendorSbom` writes a CycloneDX SBOM (`vendor-sbom.cdx.json`) with every vendor jar, its SHA-256, its licenses (from the `pom.xml` or `Bundle-License`), a `pkg:maven` URL when the jar has real coordinates, and the dependencies. It is packaged into every distribution ZIP.
* **Checksum.** `packageVendorDistribution` writes `vendor-sdk-<version>.zip.sha256` next to the ZIP. Publish it with the ZIP. To prove who built it as well, sign it: see [Signed distributions](#signed-distributions).
* **Java module path.** Jars that work on the class path can fail on the module path. `resolveVendorModules` warns when one module name (from `module-info` or `Automatic-Module-Name`) is used by several jars, or when a package is split between named modules; `module-dependencies.json` lists every module name and every split package under `jpms`. `vendor.jpmsCheck=fail` makes it an error, `off` turns it off.
* **What changed.** `./gradlew diffVendorDistribution --baseline=<earlier vendor-sdk zip>` (or `vendor.diff.baseline`) lists jars added, removed and changed, new and dropped dependencies, Java version changes, and the public classes, methods and fields that are gone, in `build/reports/vendor-diff/diff.txt` and `diff.json`.
* **A Maven repository.** `exportVendorMavenRepository` writes every module and bundle as a normal Maven repository (`build/vendor-maven-repo`: jars, slim jars, `pom.xml`, Gradle module metadata, checksums), usable without the consumer plugin. `publishVendorMavenRepository` uploads it with HTTP `PUT` to `vendor.publish.url` (Nexus, Artifactory, or a `file:` folder), with `vendor.publish.username` and the password from the Gradle property `vendor.publish.password` or the environment variable `VENDOR_PUBLISH_PASSWORD`.

**On the consumer side**

* **Checksum check.** With `vendor.sha256=<64 hex digits>` the ZIP is checked before it is unpacked; a damaged or swapped file fails the build and is not unpacked.
* **Vendor files in the installation.** `vendor.files("conf")` is a file tree of a folder of the distribution (configuration, native libraries, licenses), and `vendor.file("conf/app.properties")` a single file:

  ```kotlin
  distributions {
      main {
          contents {
              from(vendor.files("conf")) { into("conf") }
              from(vendor.files("native/linux-x64")) { into("lib/native") }
          }
      }
  }
  ```

  The files must be in the ZIP (`vendor.zip.include`; a slim ZIP holds only `vendor.slimZip.include`).
* **Duplicate classes.** `verifyVendorClasspath` warns when a vendor jar shares classes with another jar of the runtime classpath, for example the vendor's `commons-lite-3.8.jar` and your own copy of the same library under another name:

  ```
  Vendor jars share classes with other jars of the runtime classpath of project ':app' (source set 'main'):
    commons-lite-3.8.jar (vendor) and lite-copy-1.0.jar: 1 class(es), e.g. org.lite.Lite
  ```

  `vendor.duplicateClasses=fail` makes it an error, `off` turns it off. Only jars of external libraries are compared; your own projects are not built for it.

## Checking the vendor jars against Maven Central

The plugin works out the coordinates and dependencies of the vendor jars itself. `verifyVendorAgainstMaven` checks that work against a Maven repository:

```bash
./gradlew verifyVendorAgainstMaven
```

It looks up only jars that can be public: those whose rule marks them `open-source` or `vendor-public`, and jars without a rule that carry their own Maven metadata. Jars marked `vendor-private`, and jars that have no real coordinates (the default group), are skipped and never sent anywhere. For each looked-up jar it compares `<group>:<name>:<version>` and the jar's hash with the published one:

* `match`: the vendor ships the published jar. Its published `pom.xml` is then compared with the dependencies the plugin found: a published dependency that the graph does not reach is a finding (add it with `vendor.dependencyAdd`); dependencies found in the code but missing from the published `pom.xml` are listed for information.
* `differs`: the vendor ships a changed copy (patched, repackaged or rebuilt). A warning names the jar.
* `not found`: the coordinates are wrong, or the jar is not public after all. Fix the rule, or mark the jar `vendor-private`.

The summary is printed, and the full report is written to `build/reports/vendor-maven-check/report.txt` and `report.json`:

```
Vendor Maven check against https://repo.maven.apache.org/maven2: 15 jar(s) checked: 14 match, 1 differ, 0 not found, 0 error(s); 3 skipped (vendor-private, or not known to be public); 0 published dependency(ies) the graph does not reach. Report: .../build/reports/vendor-maven-check/report.txt
```

* `vendor.mavenCheck.url` sets another repository (a Nexus or Artifactory mirror, or a `file:` folder); Maven Central is the default.
* `vendor.mavenCheck.failOn=differs, notfound, dependencies, licenses` (any of them) turns findings into a build failure, for example in CI.
* With a [licence policy](#licence-policy), the licences of the published `pom.xml` are checked as well; the report lists them for every jar.
* The task needs network access to the repository; it is never part of `packageVendorDistribution`.

## Signed distributions

A checksum proves the ZIP is intact; a signature also proves who built it. The keys are plain Java keys (Ed25519, RSA or EC), so nothing else has to be installed.

1. Create a key pair once on the distributor side:

   ```bash
   ./gradlew generateVendorSigningKey
   ```

   It writes `build/vendor-signing/vendor-signing.key` (secret) and `vendor-signing.pub`, and prints the line for consumers. Move the private key to a safe place; the task never overwrites an existing key.
2. Sign every distribution ZIP: set `vendor.signing.key=<path to the key file>` in the distributor's `vendor.properties`, or put the key itself into the environment variable `VENDOR_SIGNING_KEY` (for CI). `packageVendorDistribution` then writes `vendor-sdk-<version>.zip.sig` next to the ZIP; publish it at the same URL with `.sig` appended.
3. Consumers set the public key, as a path to the `.pub` file or as the one-line value the task printed:

   ```properties
   vendor.signing.publicKey=MCowBQYDK2VwAyEA...
   ```

   The ZIP is checked before it is unpacked; a missing signature or one that does not match fails the build and nothing is unpacked. A downloaded distribution brings its `.sig` from the same place; for `vendor.localZip` it must lie next to the ZIP.

## Licence policy

The licences of the vendor jars come from their `pom.xml` or their manifest (`Bundle-License`). A policy turns them into a check:

```properties
# vendor.properties of the distributor (comma separated, * is a wildcard)
vendor.licenses.allow=Apache-2.0, MIT, BSD-*, EPL-2.0
vendor.licenses.deny=GPL-*, AGPL-*
vendor.licenses.unknown=warn
```

* Licence names are matched both as written and as their SPDX id, so `The Apache Software License, Version 2.0` and `https://www.apache.org/licenses/LICENSE-2.0` both count as `Apache-2.0`.
* A jar passes when one of its licences is allowed and not denied (a dual-licensed jar needs one acceptable licence). Without `allow`, every licence that `deny` does not name is fine.
* A jar that fails stops `resolveVendorModules` with the list of jars and licences. Jars marked `vendor-private` are not checked.
* `vendor.licenses.unknown` (`warn`, `fail`, `off`) decides about jars that declare no licence. Many jars name their licence only in the `pom.xml` published to Maven Central: `verifyVendorAgainstMaven` reads it there, checks it against the same policy, and `vendor.mavenCheck.failOn=licenses` makes that an error.
* The SBOM carries the SPDX id where one is known.
