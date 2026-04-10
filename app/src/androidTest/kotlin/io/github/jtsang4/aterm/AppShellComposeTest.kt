package io.github.jtsang4.aterm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppShellComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun app_shell_shows_all_top_level_navigation_entries() {
        composeRule.onNodeWithTag("app_shell").assertIsDisplayed()

        listOf("hosts", "identities", "session", "snippets", "settings").forEach { route ->
            composeRule.onNodeWithTag("nav_$route").assertIsDisplayed()
        }
    }

    @Test
    fun tapping_navigation_item_shows_matching_placeholder_screen() {
        composeRule.onNodeWithTag("nav_snippets").performClick()
        composeRule.onNodeWithTag("screen_snippets").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
    }
}
