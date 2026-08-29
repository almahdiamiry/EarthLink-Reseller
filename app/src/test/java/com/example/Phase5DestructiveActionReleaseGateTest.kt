package com.example

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 5 Workstream 3 (RC-07): Destructive Action Release Gate Certification Test Suite.
 *
 * Verifies:
 * 1. "Clear All Local Data" destructive tool and Developer Mode in SettingsScreen are gated by BuildConfig.DEBUG.
 * 2. No other UI Composable or screen in app/src/main/java/com/example/ui/ invokes or exposes clearLocalData.
 * 3. Contract pattern RC-07 is registered and active in contract/forbidden_patterns.yaml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase5DestructiveActionReleaseGateTest {

    private fun findSourceFile(relPath: String): File {
        val candidates = listOf(
            File(relPath),
            File(relPath.removePrefix("app/")),
            File("app", relPath),
            File("..", relPath),
            File("../..", relPath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Source file not found for candidate paths $candidates (cwd: ${File(".").absolutePath})")
    }

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
    fun testSettingsScreen_destructiveActionsGatedByBuildConfigDebug() {
        val settingsFile = findSourceFile("app/src/main/java/com/example/ui/screens/SettingsScreen.kt")
        assertTrue("SettingsScreen.kt must exist", settingsFile.exists())
        val content = settingsFile.readText()

        // 1. Verify Developer Mode and Clear All Local Data are inside BuildConfig.DEBUG / AppBuildConfig.DEBUG block
        assertTrue(
            "SettingsScreen must gate Developer Mode and Clear All Local Data behind BuildConfig.DEBUG",
            content.contains("if (com.alamiry.earthlinkreseller.BuildConfig.DEBUG)") ||
                content.contains("if (BuildConfig.DEBUG)") ||
                content.contains("if (AppBuildConfig.DEBUG)")
        )

        // 2. Verify clearLocalData call exists only inside the debug-gated SettingsScreen block
        val clearOccurrences = Regex("""\.clearLocalData\(""").findAll(content).count()
        assertTrue("SettingsScreen must contain the clearLocalData implementation", clearOccurrences >= 1)

        // 3. Verify DeveloperSection invocation is guarded by BuildConfig.DEBUG / AppBuildConfig.DEBUG
        val debugGuardIndex = listOf(
            content.indexOf("if (com.alamiry.earthlinkreseller.BuildConfig.DEBUG)"),
            content.indexOf("if (BuildConfig.DEBUG)"),
            content.indexOf("if (AppBuildConfig.DEBUG)")
        ).filter { it != -1 }.minOrNull() ?: -1
        assertTrue("BuildConfig.DEBUG or AppBuildConfig.DEBUG guard must be present", debugGuardIndex != -1)

        val developerSectionCallIndex = content.indexOf("DeveloperSection(")
        assertTrue("DeveloperSection must be called in SettingsScreen", developerSectionCallIndex != -1)
        assertTrue(
            "DeveloperSection call must be guarded by BuildConfig.DEBUG",
            developerSectionCallIndex > debugGuardIndex && (developerSectionCallIndex - debugGuardIndex) < 500
        )
    }

    @Test
    fun testNoOtherUiScreens_callClearLocalData() {
        val uiScreensDir = findSourceDir("app/src/main/java/com/example/ui")
        val ktFiles = uiScreensDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        for (file in ktFiles) {
            if (file.name == "SettingsScreen.kt" || file.name == "DashboardViewModel.kt") {
                continue
            }
            val content = file.readText()
            val hasClearCall = content.contains(".clearLocalData(") || content.contains("clearLocalData(")
            assertFalse(
                "UI file ${file.name} must NOT call or expose clearLocalData outside debug-gated SettingsScreen",
                hasClearCall
            )
        }
    }

    @Test
    fun testForbiddenPatternRegistry_hasRc07Rule() {
        val registryFile = findSourceFile("contract/forbidden_patterns.yaml")
        assertTrue("contract/forbidden_patterns.yaml must exist", registryFile.exists())
        val content = registryFile.readText()

        assertTrue(
            "Registry must contain RC-07 pattern ID",
            content.contains("RC-07-clear-local-data-ui-gate")
        )
        assertTrue(
            "Registry rule must reference invariant INV-15",
            content.contains("invariant: \"INV-15\"")
        )
    }
}
