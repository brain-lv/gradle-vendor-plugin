/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 brain-lv (https://github.com/brain-lv)
 */

package lv.brain.gradle.distributor

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * The `generateVendorIvyRepo` task: writes the Ivy repository of the distribution to `<vendorDir>/ivy`.
 *
 * Describes every selected jar and every category bundle so that Gradle can resolve them, and, with [slimJars],
 * adds the `-slim.jar` files used for compilation.
 */
@CacheableTask
abstract class GenerateVendorIvyRepoTask : DefaultTask() {
    /** The directory that holds the vendor jars. */
    @get:Internal
    abstract val vendorDir: DirectoryProperty

    /** The jars below [vendorDir]; the generated `ivy/` folder is not included. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val vendorJars: FileTree
        get() = VendorJarScanner.jarTree(vendorDir)

    /** Where [ivyDir] lies inside [vendorDir]; the generated metadata refers to the jars relative to it. */
    @get:Input
    val ivyDirInVendorDir: String
        get() = ivyDir.get().asFile.relativeTo(vendorDir.get().asFile).invariantSeparatorsPath

    /** The module graph written by `resolveVendorModules`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyGraphFile: RegularFileProperty

    /** The version of the distribution and its bundles. */
    @get:Input
    abstract val versionString: Property<String>

    /** The group of the category bundles, `<defaultGroup>.bundle`. */
    @get:Input
    abstract val bundleGroup: Property<String>

    /** Whether `-slim.jar` files are generated. Defaults to `false`. */
    @get:Input
    abstract val slimJars: Property<Boolean>

    /** The generated repository, `<vendorDir>/ivy`. */
    @get:OutputDirectory
    abstract val ivyDir: DirectoryProperty

    init {
        slimJars.convention(false)
    }

    /** Generates the repository into [ivyDir]. */
    @TaskAction
    fun generate() {
        val modules = VendorIvyDependencyMapper.loadGraph(dependencyGraphFile.get().asFile)
        IvyRepositoryGenerator.generate(
            modules = modules,
            vendorRoot = vendorDir.get().asFile,
            ivyOutputDir = ivyDir.get().asFile,
            version = versionString.get(),
            bundleGroup = bundleGroup.get(),
            slimJars = slimJars.get()
        )
        logger.lifecycle("Vendor Ivy repository generated for {} module(s) in {}", modules.size, ivyDir.get().asFile)
    }
}
