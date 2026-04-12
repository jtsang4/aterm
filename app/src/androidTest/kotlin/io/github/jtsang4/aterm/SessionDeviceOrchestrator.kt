package io.github.jtsang4.aterm

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class SessionDeviceOrchestrator(
    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    private val context: Context,
    private val device: UiDevice,
) {
    private var originalShowImeWithHardKeyboard: String? = null

    fun waitForAppShell(
        appPackage: String,
        timeoutMillis: Long = 10_000L,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag("app_shell").assertIsDisplayed()
            }.isSuccess
        }
        device.wait(Until.hasObject(By.pkg(appPackage)), timeoutMillis)
    }

    fun openSessionScreen(
        appPackage: String,
        timeoutMillis: Long = 10_000L,
    ) {
        waitForAppShell(appPackage = appPackage, timeoutMillis = timeoutMillis)
        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onNodeWithTag("screen_session").assertIsDisplayed()
            }.isSuccess
        }
    }

    fun showImeForTaggedField(
        testTag: String,
        timeoutMillis: Long = 10_000L,
    ) {
        rememberImeHardwareKeyboardSetting()
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        composeRule.onNodeWithTag(testTag).performClick()
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity { activity ->
            val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val targetView = activity.currentFocus ?: activity.window.decorView
            targetView.requestFocus()
            inputMethodManager.showSoftInput(targetView, InputMethodManager.SHOW_IMPLICIT)
            activity.window.insetsController?.show(WindowInsets.Type.ime())
        }
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            isImeVisible()
        }
    }

    fun isImeVisible(): Boolean {
        var visible = false
        composeRule.activityRule.scenario.onActivity { activity ->
            visible = activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }

    fun rotateAndWaitForAppShell(
        orientation: Int,
        appPackage: String,
    ) {
        when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> device.setOrientationLeft()
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> device.setOrientationNatural()
            else -> error("Unsupported test orientation: $orientation")
        }
        composeRule.waitForIdle()
        waitForAppShell(appPackage)
    }

    fun backgroundAndResumeApp(appPackage: String) {
        device.pressHome()
        device.waitForIdle()
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        waitForAppShell(appPackage)
    }

    fun recreateActivityAndWaitForAppShell(appPackage: String) {
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        waitForAppShell(appPackage)
    }

    fun restoreDeviceState() {
        runCatching { device.unfreezeRotation() }
        originalShowImeWithHardKeyboard?.let { value ->
            val normalized = value.trim()
            if (normalized.isNotEmpty() && normalized != "null") {
                runCatching {
                    device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $normalized")
                }
            }
        }
    }

    private fun rememberImeHardwareKeyboardSetting() {
        if (originalShowImeWithHardKeyboard == null) {
            originalShowImeWithHardKeyboard = runCatching {
                device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
            }.getOrNull()
        }
    }
}
