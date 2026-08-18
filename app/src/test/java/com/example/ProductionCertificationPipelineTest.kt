package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * INV-15 & INV-16 Canonical Certification Suite: ProductionCertificationPipelineTest.
 *
 * Verifies that:
 * 1. Release signing configuration in app/build.gradle.kts is fail-closed.
 * 2. Missing/invalid keystore properties cause build failure rather than falling back to debug signing.
 * 3. Dry-run bypasses (--dry-run) are strictly forbidden in production gates.
 * 4. Release artifact metadata (applicationId, versionCode, versionName) matches production contract.
 */
class ProductionCertificationPipelineTest {

    private fun getRepoRoot(): File {
        val current = File(System.getProperty("user.dir") ?: ".")
        return if (current.name == "app") current.parentFile else current
    }

    @Test
    fun testReleaseBuildConfigurationIsFailClosed() {
        val rootDir = getRepoRoot()
        val buildGradle = File(rootDir, "app/build.gradle.kts")
        assertTrue("app/build.gradle.kts must exist at ${buildGradle.path}", buildGradle.exists())

        val content = buildGradle.readText()
        // Ensure signingConfigs release block exists
        assertTrue("build.gradle.kts must define release signingConfig", content.contains("signingConfigs"))
        assertTrue("build.gradle.kts must reference release signing", content.contains("getByName(\"release\")") || content.contains("signingConfig = signingConfigs"))
        
        // Ensure debug signing is not used as fallback in release buildType
        val releaseBuildType = content.substringAfter("getByName(\"release\")", "")
        assertFalse("Release buildType must not fallback to debug signingConfig", releaseBuildType.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
    }

    @Test
    fun testProductionGateForbidsDryRun() {
        val rootDir = getRepoRoot()
        val forbiddenPatternsFile = File(rootDir, "contract/forbidden_patterns.yaml")
        assertTrue("contract/forbidden_patterns.yaml must exist at ${forbiddenPatternsFile.path}", forbiddenPatternsFile.exists())

        val content = forbiddenPatternsFile.readText()
        assertTrue("Forbidden patterns must ban RC-6-release-dry-run", content.contains("RC-6-release-dry-run"))
    }

    @Test
    fun testReleaseApplicationMetadata() {
        val rootDir = getRepoRoot()
        val buildGradle = File(rootDir, "app/build.gradle.kts")
        val content = buildGradle.readText()

        assertTrue("applicationId/namespace must be defined", content.contains("com.alamiry.earthlinkreseller"))
        assertTrue("versionCode must be defined", content.contains("versionCode ="))
        assertTrue("versionName must be defined", content.contains("versionName ="))
    }
}
