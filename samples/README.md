# Samples

A vendor delivery as it looks in practice, and an application that uses it. Both builds load the plugins from the
source of this repository.

* `vendor-sdk/`: the distributor. `acme-platform/lib` gets 15 open-source jars from Maven Central (the Spring jars
  carry no pom), `acme-platform/modules` the vendor's own `acme-core.jar`, built from source.
* `vendor-app/`: the consumer. It compiles against the vendor API only and runs with every jar.

```bash
./gradlew -p samples/vendor-sdk packageVendorDistribution
./gradlew -p samples/vendor-app run printVendorClasspaths
./gradlew -p samples/vendor-sdk verifyVendorAgainstMaven   # compares the jars with Maven Central
```

In IntelliJ IDEA, open `samples/vendor-sdk`, run `packageVendorDistribution`, then add `samples/vendor-app` as a second
Gradle project (Gradle tool window, `+`).
