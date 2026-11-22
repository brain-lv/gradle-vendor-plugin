/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Edgars Grinvalds <edgars.grinvalds@brain.lv>
 */

package lv.brain.gradle

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.head
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VendorPluginEndToEndTest {
    companion object {
        @JvmStatic
        @TempDir
        lateinit var workspaceDir: Path
    }

    private lateinit var sharedTestKitDir: File
    private lateinit var builtDistributionZip: File

    @BeforeAll
    fun initWorkspace() {
        val testKitRoot = File(System.getProperty("user.dir"), "build/testkit")
        testKitRoot.listFiles { f -> f.name.startsWith("end-to-end") }?.forEach { runCatching { it.deleteRecursively() } }
        sharedTestKitDir = File(testKitRoot, "end-to-end-${System.nanoTime()}").apply { mkdirs() }
    }

    @Test
    @Order(1)
    fun `01 distributor downloads real jars, builds slim abi jars, relative ivy metadata and packages zip`() {
        val distRepoDir = workspaceDir.resolve("vendor-distributor-repo").toFile().apply { mkdirs() }
        File(distRepoDir, ".git").mkdirs()

        // 1. Download real popular libraries from Maven Central into vendor category directories
        val clientLib = File(distRepoDir, "vendor/client/lib").apply { mkdirs() }
        val serverX1Lib = File(distRepoDir, "vendor/server/x1/lib").apply { mkdirs() }
        val serverX2Lib = File(distRepoDir, "vendor/server/x2/lib").apply { mkdirs() }

        downloadMavenJar("org.slf4j", "slf4j-api", "2.0.16", clientLib)
        downloadMavenJar("com.google.code.gson", "gson", "2.11.0", clientLib)

        downloadMavenJar("com.google.guava", "guava", "33.3.1-jre", serverX1Lib)
        downloadMavenJar("com.google.guava", "failureaccess", "1.0.2", serverX1Lib)

        downloadMavenJar("org.apache.commons", "commons-lang3", "3.17.0", serverX2Lib)
        downloadMavenJar("org.mockito", "mockito-core", "5.14.2", serverX2Lib)
        downloadMavenJar("net.bytebuddy", "byte-buddy", "1.15.4", serverX2Lib)
        downloadMavenJar("net.bytebuddy", "byte-buddy-agent", "1.15.4", serverX2Lib)
        downloadMavenJar("org.objenesis", "objenesis", "3.3", serverX2Lib)

        // Custom vendor JAR with no POM/Manifest to verify regex group rule fallback + relative file path
        createCustomVendorJar(
            File(serverX2Lib, "custom-vendor-protocol-2.4.1.jar"),
            "lv/brain/vendor/custom/CustomProtocolHelper.class",
            "lv/brain/vendor/custom/CustomProtocolHelper"
        )

        // 2. Write distributor vendor.properties & settings.gradle.kts
        File(distRepoDir, "vendor.properties").writeText(
            """
            vendor.version=2026.1.0
            vendor.defaultGroup=lv.brain.vendor
            vendor.slimJars=true
            vendor.rule.1=^custom-vendor-(.+)-(\d+.*)\.jar$ -> lv.brain.vendor.custom
            vendor.category.client=client/lib/**
            vendor.category.server-x1=server/x1/lib/**
            vendor.category.server-x2=server/x2/lib/**
            """.trimIndent()
        )

        // Categories come from vendor.properties, so the settings script references no plugin types: Kotlin DSL
        // script compilation under TestKit can't always see plugin classes (the injected plugin classpath is not
        // reliably on the script compile classpath), and this test is about packaging, not the DSL.
        // The `vendorDistributor { category(...) }` DSL form is covered by VendorDistributorLazinessTest.
        File(distRepoDir, "settings.gradle.kts").writeText(
            """
            plugins {
                id("lv.brain.vendor-distributor")
            }
            rootProject.name = "vendor-sdk-distributor"
            """.trimIndent()
        )

        File(distRepoDir, "build.gradle.kts").writeText("")

        // 3. Execute packageVendorDistribution via Gradle TestKit
        val result = GradleRunner.create()
            .withProjectDir(distRepoDir)
            .withTestKitDir(sharedTestKitDir)
            .withPluginClasspath()
            .withArguments("packageVendorDistribution", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":packageVendorDistribution")?.outcome)

        // 4. Verify the local files are excluded from git without a .gitignore
        val gitExclude = File(distRepoDir, ".git/info/exclude")
        assertTrue(gitExclude.exists(), ".git/info/exclude should be created automatically")
        val gitExcludeLines = gitExclude.readLines()
        assertTrue("/vendor.local.properties" in gitExcludeLines, gitExcludeLines.toString())
        assertTrue("/.vendor/" in gitExcludeLines, gitExcludeLines.toString())
        assertTrue(!File(distRepoDir, ".gitignore").exists(), ".gitignore must not be created")

        // 5. Verify generated distribution ZIP uses relative paths in .module (no symlinks, no duplicated full JARs)
        builtDistributionZip = File(distRepoDir, "build/distributions/vendor-sdk-2026.1.0.zip")
        assertTrue(builtDistributionZip.exists(), "Distribution ZIP must exist")

        val extractVerifyDir = workspaceDir.resolve("verify-zip-inspect").toFile().apply { mkdirs() }

        ZipFile(builtDistributionZip).use { zf ->
            val gsonFullDuplicate = zf.getEntry("ivy/com.google.code.gson/gson-2.11.0/gson-2.11.0.jar")
            val gsonSlimEntry = zf.getEntry("ivy/com.google.code.gson/gson-2.11.0/gson-2.11.0-slim.jar")
            val gsonModuleEntry = zf.getEntry("ivy/com.google.code.gson/gson-2.11.0/gson-2.11.0.module")
            val customModuleEntry = zf.getEntry("ivy/lv.brain.vendor.custom/protocol-2.4.1/protocol-2.4.1.module")
            val bundleServerX1Entry = zf.getEntry("ivy/lv.brain.vendor.bundle/server-x1-2026.1.0/server-x1-2026.1.0.module")

            assertNull(gsonFullDuplicate, "Full JAR must NOT be duplicated inside ivy/")
            assertNotNull(gsonSlimEntry, "Slim Gson JAR entry must exist in ivy/")
            assertNotNull(gsonModuleEntry, "Gradle Module Metadata (.module) must exist in ivy/")
            assertNotNull(customModuleEntry, "Custom regex JAR metadata must exist")
            assertNotNull(bundleServerX1Entry, "Category bundle server-x1 metadata must exist")

            val gsonModuleJson = zf.getInputStream(gsonModuleEntry!!).bufferedReader().readText()
            assertTrue(
                gsonModuleJson.contains("\"url\": \"../../../client/lib/gson-2.11.0.jar\""),
                "Expected runtimeElements to point via relative file path to client/lib/gson-2.11.0.jar, got:\n$gsonModuleJson"
            )
            assertTrue(
                gsonModuleJson.contains("\"url\": \"gson-2.11.0-slim.jar\""),
                "Expected apiElements to point to gson-2.11.0-slim.jar"
            )

            val customModuleJson = zf.getInputStream(customModuleEntry!!).bufferedReader().readText()
            assertTrue(
                customModuleJson.contains("\"url\": \"../../../server/x2/lib/custom-vendor-protocol-2.4.1.jar\""),
                "Expected custom JAR relative path in .module, got:\n$customModuleJson"
            )

            val slimOut = File(extractVerifyDir, "gson-slim.jar")
            zf.getInputStream(gsonSlimEntry!!).use { input -> slimOut.outputStream().use { input.copyTo(it) } }
        }

        // 6. Inspect bytecode inside gson-slim.jar via ASM to verify stripping
        val slimGson = File(extractVerifyDir, "gson-slim.jar")
        val fullGson = File(clientLib, "gson-2.11.0.jar")
        assertTrue(
            slimGson.length() < fullGson.length() * 0.65,
            "Slim JAR (${slimGson.length()} B) should be substantially smaller than full JAR (${fullGson.length()} B)"
        )

        JarFile(slimGson).use { jf ->
            val gsonClassEntry = jf.getJarEntry("com/google/gson/Gson.class")
            assertNotNull(gsonClassEntry)
            val bytes = jf.getInputStream(gsonClassEntry).use { it.readBytes() }
            val reader = ClassReader(bytes)

            var privateMembersFound = 0
            var methodBodiesFound = 0
            var publicMethodsFound = 0

            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? {
                    if ((access and Opcodes.ACC_PRIVATE) != 0) privateMembersFound++
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    sig: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor {
                    if ((access and Opcodes.ACC_PRIVATE) != 0) privateMembersFound++
                    if ((access and Opcodes.ACC_PUBLIC) != 0) publicMethodsFound++
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitCode() {
                            methodBodiesFound++
                        }
                    }
                }
            }, 0)

            assertEquals(0, privateMembersFound, "All private fields and methods must be stripped from slim JAR")
            assertEquals(0, methodBodiesFound, "All method bodies (Code attributes) must be stripped from slim JAR")
            assertTrue(publicMethodsFound > 10, "Public API methods must remain intact in slim JAR")
        }
    }

    @Test
    @Order(2)
    fun `02 consumer downloads zip slowly via wiremock with progress, compiles with slim jars and runs tests with full jars`() {
        assertTrue(::builtDistributionZip.isInitialized && builtDistributionZip.exists())

        val slowDownloadMs = System.getProperty("slowDownloadMs")?.toIntOrNull() ?: 20_000
        val zipBytes = builtDistributionZip.readBytes()

        val wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        try {
            val chunks = 20
            wireMock.stubFor(
                head(urlEqualTo("/dist/vendor-sdk-2026.1.0.zip"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/zip")
                            .withHeader("Content-Length", zipBytes.size.toString())
                    )
            )
            wireMock.stubFor(
                get(urlEqualTo("/dist/vendor-sdk-2026.1.0.zip"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/zip")
                            .withHeader("Content-Length", zipBytes.size.toString())
                            .withBody(zipBytes)
                            .withChunkedDribbleDelay(chunks, slowDownloadMs)
                    )
            )

            val consumerDir = workspaceDir.resolve("vendor-consumer-repo").toFile().apply { mkdirs() }
            File(consumerDir, ".git").mkdirs()

            File(consumerDir, "vendor.properties").writeText(
                """
                vendor.version=2026.1.0
                vendor.defaultGroup=lv.brain.vendor
                vendor.url=http://localhost:${wireMock.port()}/dist/vendor-sdk-{version}.zip
                """.trimIndent()
            )

            File(consumerDir, "settings.gradle.kts").writeText(
                """
                plugins {
                    id("lv.brain.vendor-consumer")
                }
                rootProject.name = "enterprise-consumer-app"
                include(":app-server")
                """.trimIndent()
            )

            val appServerDir = File(consumerDir, "app-server").apply { mkdirs() }
            File(appServerDir, "build.gradle.kts").writeText(
                """
                plugins {
                    id("lv.brain.vendor-consumer-project")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(vendor.bundle("client"))
                    implementation(vendor.bundle("server-x1"))
                    implementation(vendor.bundle("server-x2"))

                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                    testLogging {
                        showStandardStreams = true
                        events("passed", "failed")
                    }
                }

                tasks.register("verifySlimAndRuntimeClasspaths") {
                    doLast {
                        val compileFiles = configurations.compileClasspath.get().files.map { it.name }
                        val runtimeFiles = configurations.runtimeClasspath.get().files.map { it.name }
                        val testRuntimeFiles = configurations.testRuntimeClasspath.get().files.map { it.name }

                        println("COMPILE_CLASSPATH_JARS=" + compileFiles.sorted().joinToString(","))
                        println("RUNTIME_CLASSPATH_JARS=" + runtimeFiles.sorted().joinToString(","))

                        val vendorCompileJars = compileFiles.filter {
                            it.contains("gson") || it.contains("guava") || it.contains("commons-lang3") || it.contains("mockito") || it.contains("custom-vendor")
                        }
                        require(vendorCompileJars.isNotEmpty()) { "Expected vendor jars on compileClasspath" }
                        require(vendorCompileJars.all { it.endsWith("-slim.jar") }) {
                            "Expected ALL vendor compileClasspath jars to be -slim.jar, but got: " + vendorCompileJars
                        }

                        val vendorRuntimeJars = runtimeFiles.filter {
                            it.contains("gson") || it.contains("guava") || it.contains("commons-lang3") || it.contains("mockito") || it.contains("custom-vendor")
                        }
                        require(vendorRuntimeJars.isNotEmpty()) { "Expected vendor jars on runtimeClasspath" }
                        require(vendorRuntimeJars.none { it.endsWith("-slim.jar") }) {
                            "Expected NO -slim.jar on runtimeClasspath, but got: " + vendorRuntimeJars
                        }

                        val vendorTestRuntimeJars = testRuntimeFiles.filter {
                            it.contains("gson") || it.contains("guava") || it.contains("commons-lang3") || it.contains("mockito")
                        }
                        require(vendorTestRuntimeJars.none { it.endsWith("-slim.jar") }) {
                            "Expected NO -slim.jar on testRuntimeClasspath, but got: " + vendorTestRuntimeJars
                        }
                    }
                }
                """.trimIndent()
            )

            val mainJavaDir = File(appServerDir, "src/main/java/lv/brain/app").apply { mkdirs() }
            File(mainJavaDir, "VendorBackedService.java").writeText(
                """
                package lv.brain.app;

                import com.google.common.collect.ImmutableList;
                import com.google.gson.Gson;
                import lv.brain.vendor.custom.CustomProtocolHelper;
                import org.apache.commons.lang3.StringUtils;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                import java.util.Map;

                public class VendorBackedService {
                    private static final Logger LOG = LoggerFactory.getLogger(VendorBackedService.class);
                    private final Gson gson = new Gson();

                    public String buildPayload(String rawInput) {
                        String normalized = StringUtils.capitalize(StringUtils.trimToEmpty(rawInput));
                        ImmutableList<String> tags = ImmutableList.of("vendor", normalized, CustomProtocolHelper.tag());
                        LOG.info("Serialized tags: {}", tags);
                        return gson.toJson(Map.of("tags", tags));
                    }
                }
                """.trimIndent()
            )

            val testJavaDir = File(appServerDir, "src/test/java/lv/brain/app").apply { mkdirs() }
            File(testJavaDir, "VendorBackedServiceTest.java").writeText(
                """
                package lv.brain.app;

                import org.junit.jupiter.api.Test;
                import org.mockito.Mockito;

                import java.util.List;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class VendorBackedServiceTest {

                    @Test
                    void executesFullRuntimeVendorLibrariesAndMockito() {
                        VendorBackedService service = new VendorBackedService();
                        String json = service.buildPayload("  riga  ");
                        assertTrue(json.contains("\"Riga\""), "Expected Commons Lang + Guava + Gson runtime output, got: " + json);
                        assertTrue(json.contains("\"custom-v2\""), "Expected custom vendor runtime method output, got: " + json);

                        @SuppressWarnings("unchecked")
                        List<String> mockedList = Mockito.mock(List.class);
                        Mockito.when(mockedList.get(0)).thenReturn("mocked-ok");
                        assertEquals("mocked-ok", mockedList.get(0));
                    }
                }
                """.trimIndent()
            )

            val startMillis = System.currentTimeMillis()
            val result = GradleRunner.create()
                .withProjectDir(consumerDir)
                .withTestKitDir(sharedTestKitDir)
                .withPluginClasspath()
                .withArguments(
                    ":app-server:verifySlimAndRuntimeClasspaths",
                    ":app-server:test",
                    "--info",
                    "--console=rich",
                    "--stacktrace"
                )
                .forwardOutput()
                .build()
            val elapsedMillis = System.currentTimeMillis() - startMillis

            assertEquals(TaskOutcome.SUCCESS, result.task(":app-server:verifySlimAndRuntimeClasspaths")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":app-server:test")?.outcome)
            assertTrue(
                elapsedMillis >= (slowDownloadMs * 0.8).toLong(),
                "Expected slow download to take at least ${(slowDownloadMs * 0.8).toLong()}ms so progress is visible, took ${elapsedMillis}ms"
            )
            assertTrue(
                result.output.contains("vendor-sdk-2026.1.0.zip"),
                "Expected Gradle download output to show vendor-sdk-2026.1.0.zip"
            )

            wireMock.verify(1, getRequestedFor(urlEqualTo("/dist/vendor-sdk-2026.1.0.zip")))
        } finally {
            wireMock.stop()
        }
    }

    @Test
    @Order(3)
    fun `03 switching git branch version back and forth reuses versioned gradle cache with zero redownload`() {
        val consumerDir = workspaceDir.resolve("vendor-consumer-repo").toFile()
        val zipBytes = builtDistributionZip.readBytes()
        val v2ZipBytes = repackageZipForVersion(builtDistributionZip, "2026.1.0", "2026.2.0")

        val wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        try {
            wireMock.stubFor(
                head(urlEqualTo("/dist/vendor-sdk-2026.1.0.zip"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/zip")
                            .withHeader("Content-Length", zipBytes.size.toString())
                    )
            )
            wireMock.stubFor(
                get(urlEqualTo("/dist/vendor-sdk-2026.1.0.zip"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/zip")
                            .withHeader("Content-Length", zipBytes.size.toString())
                            .withBody(zipBytes)
                    )
            )
            wireMock.stubFor(
                head(urlEqualTo("/dist/vendor-sdk-2026.2.0.zip"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/zip")
                            .withHeader("Content-Length", v2ZipBytes.size.toString())
                    )
            )
            wireMock.stubFor(
                get(urlEqualTo("/dist/vendor-sdk-2026.2.0.zip"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/zip")
                            .withHeader("Content-Length", v2ZipBytes.size.toString())
                            .withBody(v2ZipBytes)
                    )
            )


            File(consumerDir, "vendor.properties").writeText(
                """
                vendor.version=2026.2.0
                vendor.defaultGroup=lv.brain.vendor
                vendor.url=http://localhost:${wireMock.port()}/dist/vendor-sdk-{version}.zip
                """.trimIndent()
            )

            val switchToV2 = GradleRunner.create()
                .withProjectDir(consumerDir)
                .withTestKitDir(sharedTestKitDir)
                .withPluginClasspath()
                .withArguments(":app-server:compileJava", "--stacktrace")
                .build()
            assertEquals(TaskOutcome.UP_TO_DATE, switchToV2.task(":app-server:compileJava")?.outcome)

            File(consumerDir, "vendor.properties").writeText(
                """
                vendor.version=2026.1.0
                vendor.defaultGroup=lv.brain.vendor
                vendor.url=http://localhost:${wireMock.port()}/dist/vendor-sdk-{version}.zip
                """.trimIndent()
            )

            val switchBackToV1 = GradleRunner.create()
                .withProjectDir(consumerDir)
                .withTestKitDir(sharedTestKitDir)
                .withPluginClasspath()
                .withArguments(":app-server:test", "--stacktrace")
                .build()
            assertEquals(TaskOutcome.UP_TO_DATE, switchBackToV1.task(":app-server:test")?.outcome)

            wireMock.verify(0, getRequestedFor(urlEqualTo("/dist/vendor-sdk-2026.1.0.zip")))
            wireMock.verify(1, getRequestedFor(urlEqualTo("/dist/vendor-sdk-2026.2.0.zip")))
        } finally {
            wireMock.stop()
        }
    }

    private fun repackageZipForVersion(sourceZip: File, oldVersion: String, newVersion: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipFile(sourceZip).use { zf ->
            ZipOutputStream(baos).use { zos ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val newName = entry.name.replace(oldVersion, newVersion)
                    zos.putNextEntry(ZipEntry(newName))
                    if (!entry.isDirectory) {
                        val bytes = zf.getInputStream(entry).use { it.readBytes() }
                        if (newName.endsWith(".module") || newName.endsWith(".xml")) {
                            zos.write(bytes.toString(Charsets.UTF_8).replace(oldVersion, newVersion).toByteArray(Charsets.UTF_8))
                        } else {
                            zos.write(bytes)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    private fun downloadMavenJar(group: String, artifact: String, version: String, targetDir: File) {
        val targetFile = File(targetDir, "$artifact-$version.jar")
        if (targetFile.exists()) return

        val groupPath = group.replace('.', '/')
        val url = "https://repo1.maven.org/maven2/$groupPath/$artifact/$version/$artifact-$version.jar"
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(targetFile.toPath()))
        require(response.statusCode() == 200) {
            "Failed to download $url (HTTP ${response.statusCode()})"
        }
    }

    private fun createCustomVendorJar(targetJar: File, entryPath: String, internalClassName: String) {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalClassName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "tag", "()Ljava/lang/String;", null, null)
        mv.visitCode()
        mv.visitLdcInsn("custom-v2")
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
        cw.visitEnd()

        JarOutputStream(targetJar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry(entryPath))
            jos.write(cw.toByteArray())
            jos.closeEntry()
        }
    }
}
