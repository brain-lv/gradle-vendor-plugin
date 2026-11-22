# Patcher plugin (`lv.brain.vendor-patcher`)

[← Back to the README](../README.md)

Sometimes a vendor JAR needs a fix you cannot wait for: a wrong default in a `.properties` file, a broken XML descriptor, a script with a typo, a class with a bug. Put numbered steps into `internal-patches/` and switch the patcher on in the distributor's `vendor.properties`:

```properties
vendor.patcher=true
# optional, default: internal-patches
# vendor.patchesDir=third-party-patches
```

or in `settings.gradle.kts`:

```kotlin
vendorDistributor {
    patcher.set(true)
    patchesDir.set(File(rootDir, "internal-patches"))
}
```

```
internal-patches/
├── 001-longer-timeout.patch
├── 002-fix-login-descriptor.patch
├── 003-fix-login/                 (a folder step: files and classes to add or replace)
│   └── client/lib/app-1.0.jar/com/acme/Login.class
├── 004-cleanup.delete             (paths to remove)
└── README.md                      (ignored: no number)
```

There are three kinds of steps. Paths are always relative to the vendor folder.

| Step | Changes | Example |
| ----- | ----- | ----- |
| `NNN-name.patch` or `.diff` | Text files, as a unified diff written by `diff -u`, `git diff` or [`createVendorPatch`](#making-and-updating-patches). | `--- a/conf/app.properties` |
| `NNN-name/` (a folder) | Adds or replaces any file, binary files and compiled classes too. A folder named like a JAR of the vendor folder stands for the inside of that JAR. | `003-fix-login/client/lib/app-1.0.jar/com/acme/Login.class` |
| `NNN-name.delete` | Removes the files and folders it lists, one per line (`#` starts a comment). `jar!/entry` removes an entry of a JAR, `jar!/folder/` everything below it. | `client/lib/app-1.0.jar!/META-INF/old.xml` |

In a `.patch`, a path with `!/` changes a text file inside a JAR:

```diff
--- a/client/lib/app-1.0.jar!/META-INF/app.properties
+++ b/client/lib/app-1.0.jar!/META-INF/app.properties
@@ -1,3 +1,3 @@
 name=demo
-timeout=30
+timeout=60
 retries=3
```

What happens:

* `applyVendorPatches` copies the vendor folder to `build/vendor-patched/<folder name>` and applies the steps to the copy, in number order. The vendor folder itself is never changed, so a vendor upgrade is just replacing its files.
* The distributor then scans and packages the patched copy; the distribution carries the patched JARs. Point `vendor.localDir` of a consumer at `build/vendor-patched/<folder name>` to use the patched folder directly.
* The step runs only when a vendor file, a patch file or an option changed, and it can be taken from the build cache.
* `.patch` steps change, create and delete text files, also inside JARs. A hunk that moved a few lines is still applied (the report notes the offset). Line endings of the file are kept.
* A class in a folder step must be at the path of its package and name, otherwise the step fails (the JVM would not load it). A class compiled for a newer Java than the other classes of its JAR only warns; compile it with `--release <version>` to keep the JAR running on the older Java.
* A step changes nothing unless all of it applies.

When a step does not apply, the build stops before writing anything and prints which step, file and hunk failed, the lines the patch expected next to the closest lines in the file, the likely cause and how to fix it, for example:

```
Vendor patch step 2 of 3 failed: 002-fix-login-descriptor.patch
File: client/lib/app-1.0.jar!/META-INF/login.xml

hunk 1 of 1: @@ -10,4 +10,4 @@
Expected (from the patch), at line 10:
       10 |   <timeout>30</timeout>
Closest match in the file, at line 12 (3 of 4 lines equal, '!' marks a difference):
  !    12 |   <timeout>45</timeout>

Likely cause: The file changed since the patch was made, most likely in a vendor upgrade.
How to fix:
  1. Open the file in the unpatched vendor folder, make the change again by hand and recreate the patch ...
```

The causes it recognises: the change is already there (the vendor fixed it, delete the step), only whitespace or line endings differ, the file changed, the file or JAR was moved or renamed (with suggestions where it is now, including a JAR with a new version number), the paths have a leading folder too many, a binary file, a signed JAR, a class at a path that does not match its name, an empty folder or `.delete` step, and a patch file that is not a unified diff. `checkVendorPatches` runs the same steps on a temporary copy without writing anything, which is handy in CI right after a vendor upgrade. The last report is in `build/reports/vendor-patches/`.

Options in `vendorPatcher { }` (root `build.gradle(.kts)`):

| Option | Default | Description |
| ----- | ----- | ----- |
| `ignoreWhitespace` | `false` | Also apply where the file differs only in spaces, tabs or line endings. |
| `stripJarSignatures` | `false` | Remove the signature files from JARs that a step changes. Without it, patching a signed JAR fails, because the JVM would refuse the changed JAR. |
| `sourceDir`, `patchesDir`, `outputDir` | set by the distributor | For the plugin on its own: `vendor`, `internal-patches` and `build/vendor-patched/<name>` of the project. |

On its own, in any project:

```kotlin
plugins {
    id("lv.brain.vendor-patcher") version "<plugin-version>"
}
```

Numbers must be unique (`001` twice stops the build), a gap in the numbers only warns, and files whose name does not start with a number are ignored. Gradle scripts (`.gradle`, `.gradle.kts`) are not accepted as steps: a script could change anything, so its result could neither be checked nor taken from the build cache. Folder, `.delete` and `.patch` steps cover what a script would do to the files.

### Making and updating patches

You don't have to write diffs by hand:

```bash
./gradlew prepareVendorPatchWorkspace            # the vendor folder with every step applied, in build/vendor-patch-workspace/<folder name>
# change files there: edit text, replace a jar, update entries with `jar uf app-1.0.jar com/acme/Login.class`, delete files
./gradlew createVendorPatch --name=fix-login     # saves the changes as the next numbered steps
```

* `createVendorPatch` writes text changes as a `.patch` (inside JARs too), binary files and classes as a folder step and removed files as a `.delete` step, numbered after the last step. Before keeping them it applies all steps to a fresh copy and checks that the result is exactly the workspace.
* The workspace keeps going: change more and run `createVendorPatch` again for the next steps. `prepareVendorPatchWorkspace` refuses to overwrite changes that are not saved yet; `--reset` discards them.
* `vendorPatchStatus` shows for every step whether it applies, applies at moved lines, is already in the vendor files (the vendor fixed it, delete the step) or fails, and goes on after a failure. It never fails the build, which makes it the first thing to run after a vendor upgrade.
* `refreshVendorPatches` rewrites the `.patch` steps that still apply, but only at moved lines or with other whitespace, so they apply exactly again. Text above the first `---` line of a step, such as a description, is kept. It stops at the first step that fails, with the same explanation as `applyVendorPatches`.
