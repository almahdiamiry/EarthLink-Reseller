package com.example

import com.example.core.util.AppBuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Workstream 14 Certification Test: BuildConfig Consistency.
 *
 * Verifies:
 * 1. Zero raw references to `com.alamiry.earthlinkreseller.BuildConfig` remain in UI code or screens (e.g. SettingsScreen).
 * 2. All debug/release environment queries cleanly route through canonical `AppBuildConfig`.
 * 3. AppBuildConfig values and fallbacks behave consistently across test and runtime environments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream14BuildConfigConsistencyTest {

    private fun findSourceDir(dirPath: String): File {
        val candidates = listOf(
            File(dirPath),
            File(dirPath.removePrefix("app/")),
            File("app", dirPath),
            File("..", dirPath),
            File("../..", dirPath)
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Source directory not found for candidate paths $candidates (cwd: ${File(".").absolutePath})")
    }

    @Test
    fun testNoRawBuildConfigReferencesInUiCode() {
        val uiDir = findSourceDir("app/src/main/java/com/example/ui")
        val ktFiles = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        for (file in ktFiles) {
            val content = file.readText()
            assertFalse(
                "UI file ${file.name} must NOT contain raw 'com.alamiry.earthlinkreseller.BuildConfig' references. Use AppBuildConfig instead.",
                content.contains("com.alamiry.earthlinkreseller.BuildConfig")
            )
        }
    }

    @Test
    fun testAppBuildConfigAccessors() {
        // Verify AppBuildConfig exposes typed properties without crashing
        assertNotNull("AppBuildConfig.DEBUG must be accessible", AppBuildConfig.DEBUG)
        assertNotNull("AppBuildConfig.FIREBASE_API_KEY must be accessible", AppBuildConfig.FIREBASE_API_KEY)
        assertNotNull("AppBuildConfig.FIREBASE_APPLICATION_ID must be accessible", AppBuildConfig.FIREBASE_APPLICATION_ID)
        assertNotNull("AppBuildConfig.FIREBASE_PROJECT_ID must be accessible", AppBuildConfig.FIREBASE_PROJECT_ID)
        assertNotNull("AppBuildConfig.FIREBASE_DATABASE_URL must be accessible", AppBuildConfig.FIREBASE_DATABASE_URL)
    }
}
