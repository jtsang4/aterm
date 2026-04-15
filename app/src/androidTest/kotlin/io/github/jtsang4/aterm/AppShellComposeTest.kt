package io.github.jtsang4.aterm

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.Snippet
import io.github.jtsang4.aterm.core.domain.model.ThemePreference
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.core.domain.model.SessionConnectionState
import io.github.jtsang4.aterm.core.ssh.SshSessionCoordinator
import io.github.jtsang4.aterm.core.terminal.TerminalColorPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

class AppShellComposeTest {
    private lateinit var context: Context
    private lateinit var application: AtermApplication

    private val resetAppStateRule = object : ExternalResource() {
        override fun before() {
            context = ApplicationProvider.getApplicationContext()
            application = context as AtermApplication
            resetTestPersistenceState(context)
        }

        override fun after() {
            resetTestPersistenceState(context)
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(resetAppStateRule)
        .around(composeRule)

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

    @Test
    fun theme_preference_updates_main_and_transient_surfaces_and_persists_after_relaunch() {
        seedPreferenceFixture(application.appContainer)

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("theme_mode_marker").assertTextContains("light", substring = true)

        composeRule.onNodeWithTag("settings_theme_option_dark").performClick()
        waitForThemeMarker("dark")

        composeRule.onNodeWithTag("nav_hosts").performClick()
        waitForThemeMarker("dark")

        composeRule.onNodeWithTag("nav_session").performClick()
        waitForThemeMarker("dark")

        composeRule.onNodeWithTag("nav_snippets").performClick()
        waitForThemeMarker("dark")

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        waitForThemeMarker("dark")
        runBlocking {
            val persisted = application.appContainer.foundationGraph.settingsRepository.observePreferences().first()
            assertEquals(ThemePreference.DARK, persisted.themePreference)
        }
    }

    @Test
    fun terminal_font_size_updates_open_and_new_sessions_and_persists_after_relaunch() {
        val container = application.appContainer
        val fixture = seedPreferenceFixture(container)
        val coordinator = container.sshSessionCoordinator

        composeRule.onNodeWithTag("nav_session").performClick()
        connectFixtureSession(
            coordinator = coordinator,
            hostId = fixture.hostId,
            expectTrustPrompt = true,
        )
        val beforeMetrics = terminalMetricsText()

        composeRule.onNodeWithTag("nav_settings").performClick()
        val increasedScale = 1.75f
        setSliderValue("settings_terminal_font_slider", increasedScale)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("settings_terminal_font_value")
                    .assertTextContains("1.75", substring = true)
            }.isSuccess
        }

        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalMetricsText() != beforeMetrics
        }
        val updatedLiveMetrics = terminalMetricsText()
        assertNotEquals(beforeMetrics, updatedLiveMetrics)

        disconnectSession(coordinator)
        connectFixtureSession(
            coordinator = coordinator,
            hostId = fixture.hostId,
            expectTrustPrompt = false,
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalMetricsText() == updatedLiveMetrics
        }
        assertEquals(updatedLiveMetrics, terminalMetricsText())

        disconnectSession(coordinator)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_session").performClick()
        connectFixtureSession(
            coordinator = coordinator,
            hostId = fixture.hostId,
            expectTrustPrompt = false,
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalMetricsText() == updatedLiveMetrics
        }
        assertEquals(updatedLiveMetrics, terminalMetricsText())
        runBlocking {
            val persisted = application.appContainer.foundationGraph.settingsRepository.observePreferences().first()
            assertEquals(increasedScale, persisted.terminalFontScale)
        }
    }

    @Test
    fun terminal_theme_updates_open_and_restored_sessions_without_regressing_font_scale() {
        val firstContainer = AppContainer.create(context)
        val fixture = seedPreferenceFixture(firstContainer)
        application.replaceAppContainerForTesting(firstContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        val coordinator = firstContainer.sshSessionCoordinator
        composeRule.onNodeWithTag("nav_session").performClick()
        connectFixtureSession(
            coordinator = coordinator,
            hostId = fixture.hostId,
            expectTrustPrompt = true,
        )
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_theme_option_dark").performClick()
        waitForThemeMarker("dark")
        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalPaletteText() != TerminalColorPalette.Default.let {
                "fg=${it.foregroundArgb.toUInt().toString(16)} bg=${it.backgroundArgb.toUInt().toString(16)}"
            }
        }
        val darkPalette = terminalPaletteText()
        val darkMetrics = terminalMetricsText()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_theme_option_light").performClick()
        waitForThemeMarker("light")

        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalPaletteText() != darkPalette
        }
        val lightPalette = terminalPaletteText()
        assertNotEquals(darkPalette, lightPalette)
        assertEquals(darkMetrics, terminalMetricsText())

        val restoredContainer = AppContainer.create(context)
        application.replaceAppContainerForTesting(restoredContainer)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("session_state_label")
                    .assertTextContains("reconnect required", substring = true)
            }.isSuccess
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalPaletteText() == lightPalette
        }
        assertEquals(lightPalette, terminalPaletteText())
        assertEquals(darkMetrics, terminalMetricsText())

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("settings_theme_option_dark").performClick()
        waitForThemeMarker("dark")
        composeRule.onNodeWithTag("nav_session").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            terminalPaletteText() == darkPalette
        }
        assertEquals(darkPalette, terminalPaletteText())
        assertEquals(darkMetrics, terminalMetricsText())
    }

    private fun seedPreferenceFixture(container: AppContainer): PreferenceFixture = runBlocking {
        container.foundationGraph.settingsRepository.updateTheme(ThemePreference.LIGHT)
        container.foundationGraph.settingsRepository.updateTerminalFontScale(1f)

        val identityId = container.foundationGraph.identityRepository.upsert(
            identity = Identity(
                name = "Prefs identity",
                kind = IdentityKind.PASSWORD,
            ),
            secrets = IdentitySecretMaterial(primarySecret = FIXTURE_PASSWORD),
        ).id

        val host = container.foundationGraph.hostRepository.upsert(
            Host(
                label = "Prefs host",
                address = FIXTURE_HOST,
                port = FIXTURE_PORT,
                username = FIXTURE_USERNAME,
                identityId = identityId,
                authKind = HostAuthKind.PASSWORD,
            ),
        )

        val snippet = container.foundationGraph.snippetRepository.upsert(
            snippet = Snippet(
                title = "Prefs snippet",
                description = "Used for preference coverage",
                hostId = host.id,
            ),
            body = "echo prefs",
        )

        PreferenceFixture(hostId = host.id, snippetId = snippet.id)
    }

    private fun terminalMetricsText(): String = composeRule.onNodeWithTag("session_terminal_metrics")
        .fetchSemanticsNode()
        .config
        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        .joinToString(separator = "") { it.text }

    private fun terminalPaletteText(): String = composeRule.onNodeWithTag("session_terminal_palette")
        .fetchSemanticsNode()
        .config
        .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
        .joinToString(separator = "") { it.text }

    private fun setSliderValue(tag: String, value: Float) {
        composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { action ->
            action(value)
        }
    }

    private fun waitForThemeMarker(expected: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("theme_mode_marker").assertTextContains(expected, substring = true)
            }.isSuccess
        }
    }

    private fun connectFixtureSession(
        coordinator: SshSessionCoordinator,
        hostId: Long,
        expectTrustPrompt: Boolean,
    ) {
        coordinator.connect(hostId)
        if (expectTrustPrompt) {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                coordinator.observeUiState().value.pendingTrustDecision != null
            }
            coordinator.submitHostTrustDecision(true)
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            coordinator.observeUiState().value.connectionState == SessionConnectionState.CONNECTED
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("session_status_message")
            .assertTextContains("Connected to $FIXTURE_HOST:$FIXTURE_PORT.", substring = true)
        composeRule.onNodeWithTag("session_terminal_metrics").assertTextContains("×", substring = true)
    }

    private fun disconnectSession(coordinator: SshSessionCoordinator) {
        coordinator.disconnect()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            coordinator.observeUiState().value.connectionState == SessionConnectionState.DISCONNECTED
        }
        composeRule.onNodeWithTag("session_input_field").assertIsNotEnabled()
    }

    private data class PreferenceFixture(
        val hostId: Long,
        val snippetId: Long,
    )

    private companion object {
        const val FIXTURE_HOST = "10.0.2.2"
        const val FIXTURE_PORT = 3122
        const val FIXTURE_USERNAME = "atermtester"
        const val FIXTURE_PASSWORD = "aterm-password-fixture"
    }
}
