package io.github.jtsang4.aterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.hosts.HostsScreen
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostLibraryFlowsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("aterm.db")
        File(context.filesDir.parentFile, "datastore").deleteRecursively()
    }

    @Test
    fun empty_state_create_action_and_validation_errors_are_visible() {
        composeRule.setContent {
            HostsScreen(
                hostRepository = HostTestFakeHostRepository(),
                identityRepository = HostTestFakeIdentityRepository(),
            )
        }

        composeRule.onNodeWithTag("screen_hosts").assertIsDisplayed()
        composeRule.onAllNodesWithTag("host_empty_state").assertCountEquals(1)
        composeRule.onNodeWithTag("host_create_action").assertIsDisplayed()

        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_label_field").assertIsDisplayed()
        composeRule.onNodeWithTag("host_address_field").assertIsDisplayed()
        composeRule.onNodeWithTag("host_port_field").assertIsDisplayed()
        composeRule.onNodeWithTag("host_username_field").assertIsDisplayed()
        composeRule.onNodeWithTag("host_no_password_identities").assertIsDisplayed()
    }

    @Test
    fun password_host_can_be_saved_and_reopened_after_relaunch() {
        val firstContainer = AppContainer.create(context)
        val seededIdentity: Identity
        val seededHost: Host
        var appContainer by mutableStateOf(firstContainer)
        runBlocking {
            seededIdentity = firstContainer.foundationGraph.identityRepository.upsert(
                identity = readyIdentity(
                    id = 0,
                    name = "Saved password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = "kept-secret"),
            )
            seededHost = firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    id = 0,
                    label = "Prod host",
                    address = "10.0.2.2",
                    port = 22,
                    username = "root",
                    identityId = seededIdentity.id,
                    authKind = HostAuthKind.PASSWORD,
                ),
            )
        }

        composeRule.setContent {
            AtermApp(appContainer = appContainer)
        }

        composeRule.onAllNodesWithTag("host_row_${seededHost.id}").assertCountEquals(1)
        composeRule.onNodeWithTag("host_identity_label_${seededHost.id}")
            .assertTextContains("Password identity: Saved password")

        val relaunchedContainer = AppContainer.create(context)
        composeRule.runOnIdle {
            appContainer = relaunchedContainer
        }

        composeRule.onAllNodesWithTag("host_row_${seededHost.id}").assertCountEquals(1)
        composeRule.onNodeWithTag("host_selection_detail_${seededHost.id}")
            .assertTextContains("root@10.0.2.2:22", substring = true)
        val relaunchedHost = runBlocking {
            relaunchedContainer.foundationGraph.hostRepository.getHost(seededHost.id)
        }
        assertEquals("Prod host", relaunchedHost?.label)
        assertEquals("10.0.2.2", relaunchedHost?.address)
        assertEquals("root", relaunchedHost?.username)
        assertEquals(seededIdentity.id, relaunchedHost?.identityId)
    }

    @Test
    fun key_auth_host_can_be_saved_and_reopened_after_relaunch() {
        val firstContainer = AppContainer.create(context)
        val seededIdentity: Identity
        var hostRepository: HostRepository by mutableStateOf(firstContainer.foundationGraph.hostRepository)
        var identityRepository: IdentityRepository by mutableStateOf(firstContainer.foundationGraph.identityRepository)
        runBlocking {
            seededIdentity = firstContainer.foundationGraph.identityRepository.upsert(
                identity = readyIdentity(
                    id = 0,
                    name = "Deploy key",
                    kind = IdentityKind.GENERATED_KEY,
                    publicKey = "ssh-rsa AAAAValidKey key@test",
                ),
                secrets = IdentitySecretMaterial(primarySecret = "generated-key-material"),
            )
        }

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Key host")
        composeRule.onNodeWithTag("host_address_field").performTextInput("10.0.2.2")
        composeRule.onNodeWithTag("host_username_field").performTextInput("ubuntu")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_auth_mode_key").performClick()
        composeRule.onNodeWithTag("host_identity_option_${seededIdentity.id}").performClick()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) } != null
        }

        val savedHost = runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) }
        assertEquals(HostAuthKind.KEY, savedHost?.authKind)
        assertEquals(seededIdentity.id, savedHost?.identityId)
        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_identity_label_1")
            .assertTextContains("Generated key identity: Deploy key")

        composeRule.onNodeWithTag("host_edit_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_label_field").assertTextContains("Key host")
        composeRule.onNodeWithTag("host_address_field").assertTextContains("10.0.2.2")
        composeRule.onNodeWithTag("host_username_field").assertTextContains("ubuntu")
        composeRule.onNodeWithTag("host_identity_option_${seededIdentity.id}").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Deploy key").assertIsDisplayed()
        composeRule.onNodeWithTag("host_editor_cancel").performScrollTo().performClick()

        val relaunchedContainer = AppContainer.create(context)
        composeRule.runOnIdle {
            hostRepository = relaunchedContainer.foundationGraph.hostRepository
            identityRepository = relaunchedContainer.foundationGraph.identityRepository
        }

        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_identity_label_1")
            .assertTextContains("Generated key identity: Deploy key")
        composeRule.onNodeWithTag("host_edit_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_label_field").assertTextContains("Key host")
        composeRule.onNodeWithTag("host_address_field").assertTextContains("10.0.2.2")
        composeRule.onNodeWithTag("host_username_field").assertTextContains("ubuntu")
        composeRule.onNodeWithTag("host_identity_option_${seededIdentity.id}").performScrollTo().assertIsDisplayed()

        val relaunchedHost = runBlocking { relaunchedContainer.foundationGraph.hostRepository.getHost(1) }
        assertEquals(HostAuthKind.KEY, relaunchedHost?.authKind)
        assertEquals(seededIdentity.id, relaunchedHost?.identityId)
    }

    @Test
    fun blank_address_and_blank_username_block_save_until_corrected_end_to_end() {
        val firstContainer = AppContainer.create(context)
        runBlocking {
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = readyIdentity(
                    id = 0,
                    name = "Reusable password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = "validation-secret"),
            )
        }

        composeRule.setContent {
            AtermApp(appContainer = firstContainer)
        }

        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Needs endpoint")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()

        composeRule.onNodeWithTag("host_address_error").assertTextContains("Address is required.")
        composeRule.onNodeWithTag("host_username_error").assertTextContains("Username is required.")
        assertEquals(null, runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) })

        composeRule.onNodeWithTag("host_address_field").performTextInput("10.0.2.2")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()

        composeRule.onAllNodesWithTag("host_address_error").assertCountEquals(0)
        composeRule.onNodeWithTag("host_username_error").assertTextContains("Username is required.")
        assertEquals(null, runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) })

        composeRule.onNodeWithTag("host_username_field").performTextInput("root")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) } != null
        }
        composeRule.onAllNodesWithTag("host_address_error").assertCountEquals(0)
        composeRule.onAllNodesWithTag("host_username_error").assertCountEquals(0)
        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_selection_detail_1")
            .assertTextContains("root@10.0.2.2:22", substring = true)
    }

    @Test
    fun host_validation_blocks_invalid_port_and_missing_identity_permutations() {
        val hostRepository = HostTestFakeHostRepository()

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = HostTestFakeIdentityRepository(),
            )
        }

        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Validation host")
        composeRule.onNodeWithTag("host_address_field").performTextInput("validation.example")
        composeRule.onNodeWithTag("host_username_field").performTextInput("tester")
        composeRule.onNodeWithTag("host_port_field").performTextClearance()
        composeRule.onNodeWithTag("host_port_field").performTextInput("0")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_port_error").assertTextContains("Enter a valid port.")
        composeRule.onNodeWithTag("host_identity_error")
            .assertTextContains("Select a reusable password identity before saving.")

        composeRule.onNodeWithTag("host_port_field").performTextClearance()
        composeRule.onNodeWithTag("host_port_field").performTextInput("70000")
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_port_error").assertTextContains("Enter a valid port.")
        assertEquals(emptyList<Host>(), hostRepository.currentHosts())
    }

    @Test
    fun key_auth_host_validation_requires_key_identity_before_save() {
        val hostRepository = HostTestFakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Validation host",
                    address = "validation.example",
                    port = 22,
                    username = "tester",
                    identityId = null,
                    authKind = HostAuthKind.KEY,
                ),
            ),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = HostTestFakeIdentityRepository(),
            )
        }

        composeRule.onNodeWithTag("host_edit_1").performClick()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_identity_error")
            .assertTextContains("Select a reusable key identity before saving.")
        assertEquals(1, hostRepository.currentHosts().size)
        assertEquals(HostAuthKind.KEY, hostRepository.currentHosts().first().authKind)
    }

    @Test
    fun edit_cancel_keeps_original_values_and_save_persists_changes_after_explicit_save() {
        val firstContainer = AppContainer.create(context)
        val seededIdentity: Identity
        var hostRepository: HostRepository by mutableStateOf(firstContainer.foundationGraph.hostRepository)
        var identityRepository: IdentityRepository by mutableStateOf(firstContainer.foundationGraph.identityRepository)
        runBlocking {
            seededIdentity = firstContainer.foundationGraph.identityRepository.upsert(
                identity = readyIdentity(
                    id = 0,
                    name = "Prod password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = "shared-secret"),
            )
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    id = 0,
                    label = "Prod",
                    address = "old.example",
                    port = 22,
                    username = "root",
                    identityId = seededIdentity.id,
                    authKind = HostAuthKind.PASSWORD,
                ),
            )
        }

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_edit_1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_edit_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_label_field").performTextClearance()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Prod updated")
        composeRule.onNodeWithTag("host_address_field").performTextClearance()
        composeRule.onNodeWithTag("host_address_field").performTextInput("new.example")
        closeKeyboardIfShown()

        val unchangedHost = runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) }
        assertEquals("Prod", unchangedHost?.label)
        assertEquals("old.example", unchangedHost?.address)

        composeRule.onNodeWithTag("host_editor_cancel").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_label_1").assertTextContains("Prod", substring = true)
        composeRule.onNodeWithTag("host_selection_detail_1")
            .assertTextContains("root@old.example:22", substring = true)

        composeRule.onNodeWithTag("host_edit_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_label_field").performTextClearance()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Prod updated")
        composeRule.onNodeWithTag("host_address_field").performTextClearance()
        composeRule.onNodeWithTag("host_address_field").performTextInput("new.example")
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) }?.label == "Prod updated"
        }
        composeRule.onNodeWithTag("host_label_1").assertTextContains("Prod updated", substring = true)
        composeRule.onNodeWithTag("host_selection_detail_1")
            .assertTextContains("root@new.example:22", substring = true)

        val relaunchedContainer = AppContainer.create(context)
        composeRule.runOnIdle {
            hostRepository = relaunchedContainer.foundationGraph.hostRepository
            identityRepository = relaunchedContainer.foundationGraph.identityRepository
        }
        composeRule.onNodeWithTag("host_label_1").assertTextContains("Prod updated", substring = true)
        composeRule.onNodeWithTag("host_selection_detail_1")
            .assertTextContains("root@new.example:22", substring = true)
    }

    @Test
    fun edit_delete_cancel_preserves_draft_state() {
        val identity = readyIdentity(id = 5, name = "Prod password", kind = IdentityKind.PASSWORD)
        val identityRepository = HostTestFakeIdentityRepository(
            initialIdentities = listOf(identity),
            initialSecrets = mapOf(5L to IdentitySecretMaterial(primarySecret = "shared-secret")),
        )
        val hostRepository = HostTestFakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Prod",
                    address = "old.example",
                    port = 22,
                    username = "root",
                    identityId = 5,
                ),
                Host(
                    id = 2,
                    label = "Backup",
                    address = "backup.example",
                    port = 22,
                    username = "admin",
                    identityId = 5,
                ),
            ),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_edit_1").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextClearance()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Prod updated")
        composeRule.onNodeWithTag("host_address_field").performTextClearance()
        composeRule.onNodeWithTag("host_address_field").performTextInput("new.example")
        composeRule.onNodeWithTag("host_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_delete_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("host_delete_cancel").performClick()
        composeRule.onNodeWithTag("host_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("host_label_field").assertTextContains("Prod updated")
        composeRule.onNodeWithTag("host_address_field").assertTextContains("new.example")
        composeRule.onNodeWithTag("host_editor_cancel").performScrollTo().performClick()
        composeRule.onAllNodesWithTag("host_row_1").assertCountEquals(1)
        composeRule.onNodeWithTag("host_row_2").assertIsDisplayed()
    }

    @Test
    fun delete_confirmation_removes_only_the_selected_host() {
        val firstContainer = AppContainer.create(context)
        val seededIdentity: Identity
        var hostRepository: HostRepository by mutableStateOf(firstContainer.foundationGraph.hostRepository)
        var identityRepository: IdentityRepository by mutableStateOf(firstContainer.foundationGraph.identityRepository)
        runBlocking {
            seededIdentity = firstContainer.foundationGraph.identityRepository.upsert(
                identity = readyIdentity(
                    id = 0,
                    name = "Shared password",
                    kind = IdentityKind.PASSWORD,
                ),
                secrets = IdentitySecretMaterial(primarySecret = "delete-secret"),
            )
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    id = 0,
                    label = "Prod",
                    address = "prod.example",
                    port = 22,
                    username = "root",
                    identityId = seededIdentity.id,
                    authKind = HostAuthKind.PASSWORD,
                ),
            )
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    id = 0,
                    label = "Backup",
                    address = "backup.example",
                    port = 2222,
                    username = "admin",
                    identityId = seededIdentity.id,
                    authKind = HostAuthKind.PASSWORD,
                ),
            )
        }

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_row_1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_edit_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_delete_confirmation").assertIsDisplayed()
        composeRule.onNodeWithTag("host_delete_cancel").performClick()
        composeRule.onNodeWithTag("host_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("host_editor_cancel").performScrollTo().performClick()
        composeRule.onAllNodesWithTag("host_row_1").assertCountEquals(1)
        composeRule.onAllNodesWithTag("host_row_2").assertCountEquals(1)

        composeRule.onNodeWithTag("host_edit_1").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_delete_confirmation").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("host_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("host_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_delete_detail")
            .assertTextContains("root@prod.example:22", substring = true)
        composeRule.onNodeWithTag("host_delete_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { firstContainer.foundationGraph.hostRepository.getHost(1) } == null
        }

        composeRule.onAllNodesWithTag("host_row_1").assertCountEquals(0)
        composeRule.onAllNodesWithTag("host_row_2").assertCountEquals(1)
        composeRule.onNodeWithTag("host_row_2").performScrollTo()
        composeRule.onNodeWithTag("host_selection_detail_2")
            .assertTextContains("admin@backup.example:2222", substring = true)
        assertNotNull(runBlocking { firstContainer.foundationGraph.hostRepository.getHost(2) })

        val relaunchedContainer = AppContainer.create(context)
        composeRule.runOnIdle {
            hostRepository = relaunchedContainer.foundationGraph.hostRepository
            identityRepository = relaunchedContainer.foundationGraph.identityRepository
        }
        composeRule.onAllNodesWithTag("host_row_1").assertCountEquals(0)
        composeRule.onAllNodesWithTag("host_row_2").assertCountEquals(1)
    }

    @Test
    fun search_filters_by_visible_fields_and_clears_cleanly() {
        val identity = readyIdentity(id = 9, name = "Shared password", kind = IdentityKind.PASSWORD)
        val identityRepository = HostTestFakeIdentityRepository(
            initialIdentities = listOf(identity),
            initialSecrets = mapOf(9L to IdentitySecretMaterial(primarySecret = "search-secret")),
        )
        val hostRepository = HostTestFakeHostRepository(
            initialHosts = listOf(
                Host(id = 1, label = "Production", address = "prod.example", port = 22, username = "root", identityId = 9, authKind = HostAuthKind.PASSWORD),
                Host(id = 2, label = "Staging", address = "qa.example", port = 22, username = "deploy", identityId = 9, authKind = HostAuthKind.PASSWORD),
                Host(id = 3, label = "Analytics", address = "metrics.example", port = 22, username = "reporter", identityId = 9, authKind = HostAuthKind.PASSWORD),
            ),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_search_field").assert(hasSetTextAction())
        composeRule.onNodeWithTag("host_search_field").performTextInput("prod")
        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()
        composeRule.onAllNodesWithTag("host_row_2").assertCountEquals(0)
        composeRule.onAllNodesWithTag("host_row_3").assertCountEquals(0)

        composeRule.onNodeWithTag("host_search_clear").performClick()
        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_row_2").assertIsDisplayed()
        composeRule.onNodeWithTag("host_row_3").assertIsDisplayed()

        composeRule.onNodeWithTag("host_search_field").performTextInput("deploy")
        composeRule.onNodeWithTag("host_row_2").assertIsDisplayed()
        composeRule.onAllNodesWithTag("host_row_1").assertCountEquals(0)
    }

    @Test
    fun duplicate_labels_show_distinguishing_selection_details() {
        val sharedIdentity = readyIdentity(id = 12, name = "Shared password", kind = IdentityKind.PASSWORD)
        val identityRepository = HostTestFakeIdentityRepository(
            initialIdentities = listOf(sharedIdentity),
            initialSecrets = mapOf(12L to IdentitySecretMaterial(primarySecret = "duplicate-secret")),
        )
        val hostRepository = HostTestFakeHostRepository(
            initialHosts = listOf(
                Host(id = 1, label = "Prod", address = "one.example", port = 22, username = "root", identityId = 12, authKind = HostAuthKind.PASSWORD),
                Host(id = 2, label = "Prod", address = "two.example", port = 2222, username = "deploy", identityId = 12, authKind = HostAuthKind.PASSWORD),
            ),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onAllNodesWithText("Prod").assertCountEquals(2)
        composeRule.onNodeWithTag("host_selection_detail_1").assertTextContains("root@one.example:22", substring = true)
        composeRule.onNodeWithTag("host_selection_detail_2").assertTextContains("deploy@two.example:2222", substring = true)

        composeRule.onNodeWithTag("host_edit_2").performClick()
        composeRule.onNodeWithTag("host_address_field").assertTextContains("two.example")
        composeRule.onNodeWithTag("host_username_field").assertTextContains("deploy")
    }

    @Test
    fun auth_recovery_can_generate_or_create_identity_and_return_to_host_form() {
        val blockedKeyIdentity = Identity(
            id = 77,
            name = "Blocked key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAABlockedKey blocked@test",
            hasSecret = true,
            hasPassphrase = false,
            secretStorageState = SecretStorageState.BLOCKED,
            passphraseStorageState = SecretStorageState.MISSING,
        )
        val hostRepository = HostTestFakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Recovered host",
                    address = "recovered.example",
                    port = 22,
                    username = "ubuntu",
                    identityId = blockedKeyIdentity.id,
                    authKind = HostAuthKind.KEY,
                ),
            ),
        )
        val identityRepository = HostTestFakeIdentityRepository(
            initialIdentities = listOf(blockedKeyIdentity),
            initialSecrets = mapOf(blockedKeyIdentity.id to IdentitySecretMaterial(primarySecret = "blocked-secret")),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_repair_1").performClick()
        composeRule.onAllNodesWithTag("host_recover_generate_key_identity").assertCountEquals(1)
        composeRule.onAllNodesWithTag("host_recover_import_key_identity").assertCountEquals(1)
        composeRule.onNodeWithTag("host_recover_generate_key_identity").performClick()
        composeRule.onNodeWithTag("identity_key_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_import_cancel").performClick()
        composeRule.onNodeWithTag("host_editor").assertIsDisplayed()

        composeRule.onNodeWithTag("host_editor_cancel").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_label_field").performTextInput("Password recovered")
        composeRule.onNodeWithTag("host_address_field").performTextInput("password.example")
        composeRule.onNodeWithTag("host_username_field").performTextInput("admin")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_auth_mode_password").performClick()
        composeRule.onNodeWithTag("host_recover_create_password_identity").performClick()
        composeRule.onNodeWithTag("identity_password_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_editor_cancel").performClick()
        composeRule.onNodeWithTag("host_editor").assertIsDisplayed()
    }

    @Test
    fun deleting_host_does_not_delete_reusable_identity() {
        val identity = readyIdentity(
            id = 20,
            name = "Reusable key",
            kind = IdentityKind.GENERATED_KEY,
            publicKey = "ssh-rsa AAAAReusableKey reuse@test",
        )
        val identityRepository = HostTestFakeIdentityRepository(
            initialIdentities = listOf(identity),
            initialSecrets = mapOf(20L to IdentitySecretMaterial(primarySecret = "key-secret")),
        )
        val hostRepository = HostTestFakeHostRepository(
            initialHosts = listOf(
                Host(id = 1, label = "First host", address = "one.example", port = 22, username = "root", identityId = 20, authKind = HostAuthKind.KEY),
                Host(id = 2, label = "Second host", address = "two.example", port = 22, username = "deploy", identityId = 20, authKind = HostAuthKind.KEY),
            ),
        )
        var showIdentities by mutableStateOf(false)

        composeRule.setContent {
            if (showIdentities) {
                io.github.jtsang4.aterm.feature.identities.IdentitiesScreen(identityRepository = identityRepository)
            } else {
                HostsScreen(
                    hostRepository = hostRepository,
                    identityRepository = identityRepository,
                )
            }
        }

        composeRule.onNodeWithTag("host_edit_1").performClick()
        composeRule.onNodeWithTag("host_editor_delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_delete_confirmation").assertIsDisplayed()
        runBlocking { hostRepository.deleteHost(1) }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            hostRepository.currentHosts().none { it.id == 1L }
        }
        assertNotNull(runBlocking { identityRepository.getIdentity(20) })

        composeRule.runOnIdle {
            showIdentities = true
        }
        composeRule.onNodeWithTag("identity_row_20").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_edit_20").assertIsDisplayed()
    }
}

private class HostTestFakeIdentityRepository(
    initialIdentities: List<Identity> = emptyList(),
    initialSecrets: Map<Long, IdentitySecretMaterial> = emptyMap(),
) : IdentityRepository {
    private val identities = MutableStateFlow(initialIdentities)
    private val secrets = linkedMapOf<Long, IdentitySecretMaterial>().apply { putAll(initialSecrets) }
    private var nextId = ((initialIdentities.maxOfOrNull(Identity::id) ?: 0L) + 1L)

    override fun observeIdentities(): Flow<List<Identity>> = identities

    override suspend fun getIdentity(id: Long): Identity? = identities.value.firstOrNull { it.id == id }

    override suspend fun upsert(identity: Identity, secrets: IdentitySecretMaterial?): Identity {
        val persistedId = identity.id.takeIf { it != 0L } ?: nextId++
        val existing = identities.value.firstOrNull { it.id == persistedId }
        val persisted = identity.copy(
            id = persistedId,
            hasSecret = identity.hasSecret || existing?.hasSecret == true || secrets?.primarySecret != null,
            hasPassphrase = identity.hasPassphrase,
            secretStorageState = when {
                secrets?.primarySecret != null -> SecretStorageState.AVAILABLE
                identity.hasSecret -> identity.secretStorageState
                existing != null -> existing.secretStorageState
                else -> SecretStorageState.MISSING
            },
            passphraseStorageState = when {
                !identity.hasPassphrase -> SecretStorageState.MISSING
                secrets?.passphrase != null -> SecretStorageState.AVAILABLE
                identity.hasPassphrase -> identity.passphraseStorageState
                existing != null -> existing.passphraseStorageState
                else -> SecretStorageState.MISSING
            },
            updatedAt = Instant.now(),
        )
        val sanitizedSecrets = when {
            secrets == null -> this.secrets[persistedId] ?: IdentitySecretMaterial()
            else -> IdentitySecretMaterial(
                primarySecret = secrets.primarySecret ?: this.secrets[persistedId]?.primarySecret,
                passphrase = secrets.passphrase?.takeIf { identity.hasPassphrase },
            )
        }
        this.secrets[persistedId] = sanitizedSecrets
        identities.value = identities.value
            .filterNot { it.id == persistedId }
            .plus(persisted)
            .sortedBy { it.id }
        return persisted
    }

    override suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial? = secrets[id]

    override suspend fun deleteIdentity(id: Long) {
        identities.value = identities.value.filterNot { it.id == id }
        secrets.remove(id)
    }
}

private class HostTestFakeHostRepository(
    initialHosts: List<Host> = emptyList(),
) : HostRepository {
    private val hosts = MutableStateFlow(initialHosts.sortedBy(Host::id))

    override fun observeHosts(): Flow<List<Host>> = hosts

    override suspend fun getHost(id: Long): Host? = hosts.value.firstOrNull { it.id == id }

    override suspend fun upsert(host: Host): Host {
        val persisted = host.copy(id = host.id.takeIf { it != 0L } ?: ((hosts.value.maxOfOrNull(Host::id) ?: 0L) + 1L))
        hosts.value = hosts.value
            .filterNot { it.id == persisted.id }
            .plus(persisted)
            .sortedBy(Host::id)
        return persisted
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) = Unit

    override suspend fun deleteHost(id: Long) {
        hosts.value = hosts.value.filterNot { it.id == id }
    }

    fun currentHosts(): List<Host> = hosts.value
}

private fun readyIdentity(
    id: Long,
    name: String,
    kind: IdentityKind,
    publicKey: String? = null,
): Identity = Identity(
    id = id,
    name = name,
    kind = kind,
    publicKey = publicKey,
    hasSecret = true,
    hasPassphrase = false,
    secretStorageState = SecretStorageState.AVAILABLE,
    passphraseStorageState = SecretStorageState.MISSING,
)

private fun closeKeyboardIfShown() {
    runCatching { closeSoftKeyboard() }
}
