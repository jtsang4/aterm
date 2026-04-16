package io.github.jtsang4.aterm

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.hosts.HostsScreen
import io.github.jtsang4.aterm.feature.identities.GeneratedKeyIdentityService
import io.github.jtsang4.aterm.feature.identities.GeneratedKeyMaterial
import io.github.jtsang4.aterm.feature.identities.IdentitiesScreen
import io.github.jtsang4.aterm.feature.identities.ImportedKeyImportService
import io.github.jtsang4.aterm.feature.identities.ImportedKeyParseResult
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.HostAuthKind
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.model.KnownHostTrust
import io.github.jtsang4.aterm.core.domain.model.SecretMaterialUnavailableException
import io.github.jtsang4.aterm.core.domain.model.SecretStorageState
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import io.github.jtsang4.aterm.core.domain.repository.KnownHostTrustRepository
import io.github.jtsang4.aterm.feature.session.SessionsScreen
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityPasswordFlowsInstrumentedTest {
    private val resetRule = TestPersistenceResetRule()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(resetRule)
        .around(composeRule)

    private val context
        get() = resetRule.context

    @Test
    fun empty_identity_library_exposes_password_import_and_generate_actions() {
        val repository = FakeIdentityRepository()

        composeRule.setContent {
            IdentitiesScreen(identityRepository = repository)
        }

        composeRule.onNodeWithTag("screen_identities").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_empty_state").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_create_password_action").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_import_key_action").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_generate_key_action").assertIsDisplayed()

        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_key_editor").assertIsDisplayed()

        composeRule.onNodeWithTag("identity_import_cancel").performClick()
        composeRule.onNodeWithTag("identity_generate_key_action").performClick()
        composeRule.onNodeWithTag("identity_key_editor").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_generate_execute").assertIsDisplayed()
    }

    @Test
    fun password_identity_validation_requires_name_and_masks_secret_by_default() {
        val repository = FakeIdentityRepository()

        composeRule.setContent {
            IdentitiesScreen(identityRepository = repository)
        }

        composeRule.onNodeWithTag("identity_create_password_action").performClick()
        composeRule.onNodeWithTag("identity_password_field")
            .assert(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Password,
                Unit,
            ))

        composeRule.onNodeWithTag("identity_editor_save").performClick()
        composeRule.onNodeWithText("Identity name is required.").assertIsDisplayed()
        composeRule.onNodeWithText("Password is required.").assertIsDisplayed()
    }

    @Test
    fun saved_password_identity_remains_selectable_from_host_flows_after_relaunch() {
        val firstContainer = AppContainer.create(context)
        var appContainer by mutableStateOf(firstContainer)

        composeRule.setContent {
            AtermApp(appContainer = appContainer)
        }

        composeRule.onNodeWithTag("nav_identities").performClick()
        composeRule.onNodeWithTag("identity_create_password_action").performClick()
        composeRule.onNodeWithTag("identity_name_field").performTextInput("Main password")
        composeRule.onNodeWithTag("identity_password_field").performTextInput("local-only-secret")
        composeRule.onNodeWithTag("identity_editor_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                firstContainer.foundationGraph.identityRepository.getIdentity(1)
            } != null
        }

        val persistedIdentity = runBlocking {
            firstContainer.foundationGraph.identityRepository.getIdentity(1)
        }
        assertNotNull(persistedIdentity)
        assertEquals(IdentityKind.PASSWORD, persistedIdentity?.kind)

        val relaunchedContainer = AppContainer.create(context)
        composeRule.runOnIdle {
            appContainer = relaunchedContainer
        }

        val relaunchedSecret = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getSecretMaterial(1)
        }
        assertEquals("local-only-secret", relaunchedSecret?.primarySecret)

        composeRule.onNodeWithTag("nav_hosts").performClick()
        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_identity_option_1")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun supported_private_key_imports_and_becomes_selectable_from_host_flows() {
        val firstContainer = AppContainer.create(context)
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.Success(
                    publicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQImportedUiKey",
                    hasPassphrase = false,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = firstContainer.foundationGraph.identityRepository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Imported deploy key")
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nui-test-placeholder\n-----END OPENSSH PRIVATE KEY-----",
        )
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                firstContainer.foundationGraph.identityRepository.observeIdentities().first().any { it.kind == IdentityKind.IMPORTED_KEY }
            }
        }
        composeRule.onNodeWithTag("identity_row_1").assertIsDisplayed()

        val importedIdentity = runBlocking {
            firstContainer.foundationGraph.identityRepository.observeIdentities().first().first { it.kind == IdentityKind.IMPORTED_KEY }
        }
        assertEquals(true, importedIdentity.hasSecret)
        assertFalse(importedIdentity.hasPassphrase)
        assertNotNull(runBlocking { firstContainer.foundationGraph.identityRepository.getSecretMaterial(importedIdentity.id) }?.primarySecret)

        val relaunchedContainer = AppContainer.create(context)
        val relaunchedIdentity = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.observeIdentities().first().first { it.kind == IdentityKind.IMPORTED_KEY }
        }
        val relaunchedSecrets = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getSecretMaterial(importedIdentity.id)
        }
        assertEquals(importedIdentity.id, relaunchedIdentity.id)
        assertEquals(true, relaunchedIdentity.hasSecret)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nui-test-placeholder\n-----END OPENSSH PRIVATE KEY-----",
            relaunchedSecrets?.primarySecret,
        )
    }

    @Test
    fun generated_key_identity_can_be_saved_and_public_key_copied() {
        val firstContainer = AppContainer.create(context)
        val generatedService = ScriptedGeneratedKeyIdentityService(
            GeneratedKeyMaterial(
                privateKeyMaterial = "-----BEGIN OPENSSH PRIVATE KEY-----\nGENERATED_TEST_KEY\n-----END OPENSSH PRIVATE KEY-----",
                publicKey = "ssh-rsa AAAAB3NzaGeneratedUiKey generated@test",
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = firstContainer.foundationGraph.identityRepository,
                generatedKeyIdentityService = generatedService,
            )
        }

        composeRule.onNodeWithTag("identity_generate_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Generated deploy key")
        composeRule.onNodeWithTag("identity_generate_execute").performClick()
        composeRule.onNodeWithTag("identity_public_key_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_public_key_copy").performClick()
        composeRule.onNodeWithTag("identity_public_key_copy_status").assertTextContains("Public key copied")
        composeRule.onNodeWithTag("identity_import_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                firstContainer.foundationGraph.identityRepository.observeIdentities().first().any { it.kind == IdentityKind.GENERATED_KEY }
            }
        }

        val generatedIdentity = runBlocking {
            firstContainer.foundationGraph.identityRepository.observeIdentities().first().first { it.kind == IdentityKind.GENERATED_KEY }
        }
        assertEquals("ssh-rsa AAAAB3NzaGeneratedUiKey generated@test", generatedIdentity.publicKey)

        val relaunchedContainer = AppContainer.create(context)
        val relaunchedIdentity = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.observeIdentities().first().first { it.kind == IdentityKind.GENERATED_KEY }
        }
        assertEquals(generatedIdentity.id, relaunchedIdentity.id)
    }

    @Test
    fun imported_key_identity_is_visible_in_host_selector() {
        val identity = Identity(
            id = 42,
            name = "Imported deploy key",
            kind = IdentityKind.IMPORTED_KEY,
            hasSecret = true,
            hasPassphrase = false,
        )
        val identityRepository = FakeIdentityRepository(
            initialIdentities = listOf(identity),
            initialSecrets = mapOf(identity.id to IdentitySecretMaterial(primarySecret = "placeholder")),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = FakeHostRepository(),
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_create_action").performClick()
        composeRule.onNodeWithTag("host_editor").assertIsDisplayed()
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("host_auth_mode_key").performClick()
        composeRule.onAllNodesWithText("Imported deploy key").assertCountEquals(1)
        composeRule.onNodeWithText("Reusable imported key identity").assertIsDisplayed()
    }

    @Test
    fun encrypted_private_key_retries_after_wrong_passphrase_without_losing_draft() {
        val repository = FakeIdentityRepository()
        val encryptedKey = "-----BEGIN OPENSSH PRIVATE KEY-----\npretend-key\n-----END OPENSSH PRIVATE KEY-----"
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.PassphraseRequired,
                ImportedKeyParseResult.IncorrectPassphrase,
                ImportedKeyParseResult.Success(
                    publicKey = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCyUiTestKey",
                    hasPassphrase = true,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Encrypted key")
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(encryptedKey)
        composeRule.onNodeWithTag("identity_import_save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("identity_import_passphrase_field").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("identity_import_name_field").assertTextContains("Encrypted key")
        composeRule.onNodeWithTag("identity_import_passphrase_field").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_import_save_passphrase_toggle").assertIsDisplayed()
        composeRule.onNodeWithText("Leave unchecked to import without saving the passphrase", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("wrong-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("identity_import_passphrase_field").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, repository.currentIdentities().size)

        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextClearance()
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("correct-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().any { it.kind == IdentityKind.IMPORTED_KEY }
        }

        val savedIdentity = repository.currentIdentities().first()
        assertEquals(IdentityKind.IMPORTED_KEY, savedIdentity.kind)
        assertEquals(true, savedIdentity.hasPassphrase)
        assertEquals(SecretStorageState.MISSING, savedIdentity.passphraseStorageState)
    }

    @Test
    fun legacy_encrypted_pem_import_with_saved_passphrase_shows_ready_saved_state() {
        val repository = FakeIdentityRepository()
        val encryptedKey = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF

            legacy-ui-test-placeholder
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.PassphraseRequired,
                ImportedKeyParseResult.Success(
                    publicKey = "ssh-rsa AAAAB3NzaLegacySavedKey saved@test",
                    hasPassphrase = true,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Saved legacy key")
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(encryptedKey)
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("identity_import_passphrase_field").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("identity_import_save_passphrase_toggle").performClick()
        composeRule.onNodeWithText("stay ready to connect", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("legacy-passphrase")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().any { it.name == "Saved legacy key" }
        }

        val savedIdentity = repository.currentIdentities().first { it.name == "Saved legacy key" }
        assertEquals(true, savedIdentity.hasPassphrase)
        assertEquals(SecretStorageState.AVAILABLE, savedIdentity.passphraseStorageState)
        composeRule.onNodeWithTag("identity_row_${savedIdentity.id}").assertIsDisplayed()
        composeRule.onNodeWithText("Saved key passphrase available").assertIsDisplayed()
    }

    @Test
    fun legacy_encrypted_pem_import_without_saving_passphrase_stays_truthfully_non_ready() {
        val repository = FakeIdentityRepository()
        val encryptedKey = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF

            legacy-ui-test-placeholder
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.PassphraseRequired,
                ImportedKeyParseResult.Success(
                    publicKey = "ssh-rsa AAAAB3NzaLegacyUnsavedKey unsaved@test",
                    hasPassphrase = true,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Unsaved legacy key")
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(encryptedKey)
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("identity_import_passphrase_field").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("identity_import_save_passphrase_toggle").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("legacy-passphrase")
        closeKeyboardIfShown()
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().any { it.name == "Unsaved legacy key" }
        }

        val savedIdentity = repository.currentIdentities().first { it.name == "Unsaved legacy key" }
        assertEquals(true, savedIdentity.hasPassphrase)
        assertEquals(SecretStorageState.MISSING, savedIdentity.passphraseStorageState)
        composeRule.onAllNodesWithTag("identity_repair_hint_${savedIdentity.id}").assertCountEquals(0)
        composeRule.onNodeWithText("Passphrase required before this key can connect").assertIsDisplayed()
    }

    @Test
    fun malformed_private_key_is_rejected_without_creating_partial_identity() {
        val repository = FakeIdentityRepository()

        composeRule.setContent {
            IdentitiesScreen(identityRepository = repository)
        }

        composeRule.onNodeWithTag("identity_import_key_action").performClick()
        composeRule.onNodeWithTag("identity_import_name_field").performTextInput("Broken key")
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput("not really a key")
        composeRule.onNodeWithTag("identity_import_save").performClick()

        composeRule.onNodeWithText(
            "Unsupported or malformed private key. Import a supported OpenSSH or PEM private key.",
        ).assertIsDisplayed()
        assertEquals(0, repository.currentIdentities().size)
    }

    @Test
    fun metadata_edit_keeps_existing_password_secret() {
        val repository = FakeIdentityRepository(
            initialIdentities = listOf(
                Identity(
                    id = 1,
                    name = "Original password",
                    kind = IdentityKind.PASSWORD,
                    hasSecret = true,
                ),
            ),
            initialSecrets = mapOf(1L to IdentitySecretMaterial(primarySecret = "still-secret")),
        )

        composeRule.setContent {
            IdentitiesScreen(identityRepository = repository)
        }

        composeRule.onNodeWithTag("identity_edit_1").performClick()
        composeRule.onNodeWithTag("identity_name_field").performTextClearance()
        composeRule.onNodeWithTag("identity_name_field").performTextInput("Renamed password")
        composeRule.onNodeWithTag("identity_editor_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().name == "Renamed password"
        }

        assertEquals("still-secret", runBlocking { repository.getSecretMaterial(1) }?.primarySecret)
    }

    @Test
    fun replacing_key_secret_updates_saved_public_key_and_secret_material() {
        val originalIdentity = Identity(
            id = 7,
            name = "Shared key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAOriginalKey shared@test",
            hasSecret = true,
            hasPassphrase = false,
        )
        val repository = FakeIdentityRepository(
            initialIdentities = listOf(originalIdentity),
            initialSecrets = mapOf(7L to IdentitySecretMaterial(primarySecret = "old-key")),
        )
        val generatedService = ScriptedGeneratedKeyIdentityService(
            GeneratedKeyMaterial(
                privateKeyMaterial = "-----BEGIN OPENSSH PRIVATE KEY-----\nREPLACED_KEY\n-----END OPENSSH PRIVATE KEY-----",
                publicKey = "ssh-rsa AAAAReplacementKey replacement@test",
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                generatedKeyIdentityService = generatedService,
            )
        }

        composeRule.onNodeWithTag("identity_edit_7").performClick()
        composeRule.onNodeWithTag("identity_replace_secret").performClick()
        composeRule.onNodeWithTag("identity_generate_key_action_inline").performClick()
        composeRule.onNodeWithTag("identity_generate_execute").performClick()
        composeRule.onNodeWithTag("identity_import_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().publicKey == "ssh-rsa AAAAReplacementKey replacement@test"
        }

        val updatedIdentity = repository.currentIdentities().first()
        assertEquals(IdentityKind.GENERATED_KEY, updatedIdentity.kind)
        assertEquals("ssh-rsa AAAAReplacementKey replacement@test", updatedIdentity.publicKey)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nREPLACED_KEY\n-----END OPENSSH PRIVATE KEY-----", runBlocking { repository.getSecretMaterial(7) }?.primarySecret)
    }

    @Test
    fun replacing_encrypted_key_with_unencrypted_import_ignores_stray_passphrase_text() {
        val originalIdentity = Identity(
            id = 8,
            name = "Encrypted key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAEncryptedKey encrypted@test",
            hasSecret = true,
            hasPassphrase = true,
            passphraseStorageState = SecretStorageState.AVAILABLE,
        )
        val repository = FakeIdentityRepository(
            initialIdentities = listOf(originalIdentity),
            initialSecrets = mapOf(
                8L to IdentitySecretMaterial(
                    primarySecret = "old-encrypted-key",
                    passphrase = "old-passphrase",
                ),
            ),
        )
        val replacementKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nREPLACED_UNENCRYPTED_KEY\n-----END OPENSSH PRIVATE KEY-----"
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.Success(
                    publicKey = "ssh-rsa AAAAReplacementUnencryptedKey replacement@test",
                    hasPassphrase = false,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_edit_8").performClick()
        composeRule.onNodeWithTag("identity_replace_secret").performClick()
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(replacementKey)
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("stray-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().publicKey == "ssh-rsa AAAAReplacementUnencryptedKey replacement@test"
        }

        val updatedIdentity = repository.currentIdentities().first()
        val updatedSecrets = runBlocking { repository.getSecretMaterial(8) }

        assertFalse(updatedIdentity.hasPassphrase)
        assertEquals(SecretStorageState.MISSING, updatedIdentity.passphraseStorageState)
        assertEquals(replacementKey, updatedSecrets?.primarySecret)
        assertEquals(null, updatedSecrets?.passphrase)
        composeRule.onNodeWithTag("identity_row_8").assertIsDisplayed()
        composeRule.onNodeWithText("No key passphrase required").assertIsDisplayed()
    }

    @Test
    fun encrypted_key_passphrase_can_be_added_updated_and_cleared_without_replacing_key_material() {
        val originalIdentity = Identity(
            id = 10,
            name = "Encrypted maintenance key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAMaintenanceKey maintenance@test",
            hasSecret = true,
            hasPassphrase = true,
            passphraseStorageState = SecretStorageState.BLOCKED,
        )
        val repository = FakeIdentityRepository(
            initialIdentities = listOf(originalIdentity),
            initialSecrets = mapOf(10L to IdentitySecretMaterial(primarySecret = "existing-encrypted-key")),
        )
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.IncorrectPassphrase,
                ImportedKeyParseResult.Success(
                    publicKey = originalIdentity.publicKey.orEmpty(),
                    hasPassphrase = true,
                ),
                ImportedKeyParseResult.Success(
                    publicKey = originalIdentity.publicKey.orEmpty(),
                    hasPassphrase = true,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_edit_10").performClick()
        composeRule.onNodeWithTag("identity_keep_existing_secret").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_existing_passphrase_status")
            .assertTextContains("Passphrase unavailable until repaired", substring = true)
        composeRule.onNodeWithTag("identity_import_save_passphrase_toggle").performClick()
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("wrong-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()
        composeRule.onNodeWithText("Passphrase was incorrect. Try again.").assertIsDisplayed()

        val unchangedAfterWrongPassphrase = repository.currentIdentities().first()
        assertEquals(10L, unchangedAfterWrongPassphrase.id)
        assertEquals(originalIdentity.publicKey, unchangedAfterWrongPassphrase.publicKey)
        assertEquals(SecretStorageState.BLOCKED, unchangedAfterWrongPassphrase.passphraseStorageState)
        assertEquals("existing-encrypted-key", runBlocking { repository.getSecretMaterial(10) }?.primarySecret)
        assertNull(runBlocking { repository.getSecretMaterial(10) }?.passphrase)

        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextClearance()
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("correct-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().passphraseStorageState == SecretStorageState.AVAILABLE
        }

        val afterAdd = repository.currentIdentities().first()
        assertEquals(10L, afterAdd.id)
        assertEquals(originalIdentity.publicKey, afterAdd.publicKey)
        assertEquals(SecretStorageState.AVAILABLE, afterAdd.passphraseStorageState)
        assertEquals("existing-encrypted-key", runBlocking { repository.getSecretMaterial(10) }?.primarySecret)
        assertEquals("correct-passphrase", runBlocking { repository.getSecretMaterial(10) }?.passphrase)

        composeRule.onNodeWithTag("identity_edit_10").performClick()
        composeRule.onNodeWithTag("identity_existing_passphrase_status")
            .assertTextContains("Saved key passphrase available", substring = true)
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("updated-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.getSecretMaterial(10) }?.passphrase == "updated-passphrase"
        }

        val afterUpdate = repository.currentIdentities().first()
        assertEquals(10L, afterUpdate.id)
        assertEquals(originalIdentity.publicKey, afterUpdate.publicKey)
        assertEquals(SecretStorageState.AVAILABLE, afterUpdate.passphraseStorageState)
        assertEquals("existing-encrypted-key", runBlocking { repository.getSecretMaterial(10) }?.primarySecret)
        assertEquals("updated-passphrase", runBlocking { repository.getSecretMaterial(10) }?.passphrase)

        composeRule.onNodeWithTag("identity_edit_10").performClick()
        composeRule.onNodeWithTag("identity_import_save_passphrase_toggle").performClick()
        composeRule.onNodeWithText("returns this identity to a non-ready state", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().passphraseStorageState == SecretStorageState.MISSING
        }

        val afterClear = repository.currentIdentities().first()
        val afterClearSecrets = runBlocking { repository.getSecretMaterial(10) }
        assertEquals(10L, afterClear.id)
        assertEquals(originalIdentity.publicKey, afterClear.publicKey)
        assertEquals(SecretStorageState.MISSING, afterClear.passphraseStorageState)
        assertFalse(afterClear.isAuthenticationReady)
        assertEquals("existing-encrypted-key", afterClearSecrets?.primarySecret)
        assertNull(afterClearSecrets?.passphrase)
        composeRule.onNodeWithTag("identity_row_10").assertIsDisplayed()
        composeRule.onNodeWithText("Passphrase required before this key can connect").assertIsDisplayed()
    }

    @Test
    fun replacing_encrypted_key_requires_new_valid_passphrase_and_failed_attempt_leaves_prior_identity_unchanged() {
        val originalIdentity = Identity(
            id = 11,
            name = "Encrypted replacement key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAOldEncryptedKey replacement@test",
            hasSecret = true,
            hasPassphrase = true,
            passphraseStorageState = SecretStorageState.AVAILABLE,
        )
        val repository = FakeIdentityRepository(
            initialIdentities = listOf(originalIdentity),
            initialSecrets = mapOf(
                11L to IdentitySecretMaterial(
                    primarySecret = "old-encrypted-key",
                    passphrase = "old-passphrase",
                ),
            ),
        )
        val replacementKey = """
            -----BEGIN RSA PRIVATE KEY-----
            Proc-Type: 4,ENCRYPTED
            DEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF

            replacement-ui-test-placeholder
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val scriptedImportService = ScriptedImportedKeyImportService(
            listOf(
                ImportedKeyParseResult.PassphraseRequired,
                ImportedKeyParseResult.IncorrectPassphrase,
                ImportedKeyParseResult.Success(
                    publicKey = "ssh-rsa AAAANewEncryptedKey replacement@test",
                    hasPassphrase = true,
                ),
            ),
        )

        composeRule.setContent {
            IdentitiesScreen(
                identityRepository = repository,
                importedKeyImportService = scriptedImportService,
            )
        }

        composeRule.onNodeWithTag("identity_edit_11").performClick()
        composeRule.onNodeWithTag("identity_replace_secret").performClick()
        composeRule.onNodeWithTag("identity_import_key_field").performTextInput(replacementKey)
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("identity_import_passphrase_field").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("wrong-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()
        composeRule.onNodeWithText("Passphrase was incorrect. Try again.").assertIsDisplayed()

        val afterWrongReplacement = repository.currentIdentities().first()
        val afterWrongSecrets = runBlocking { repository.getSecretMaterial(11) }
        assertEquals(originalIdentity.publicKey, afterWrongReplacement.publicKey)
        assertEquals(SecretStorageState.AVAILABLE, afterWrongReplacement.passphraseStorageState)
        assertEquals("old-encrypted-key", afterWrongSecrets?.primarySecret)
        assertEquals("old-passphrase", afterWrongSecrets?.passphrase)

        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextClearance()
        composeRule.onNodeWithTag("identity_import_passphrase_field").performTextInput("new-passphrase")
        composeRule.onNodeWithTag("identity_import_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().publicKey == "ssh-rsa AAAANewEncryptedKey replacement@test"
        }

        val updatedIdentity = repository.currentIdentities().first()
        val updatedSecrets = runBlocking { repository.getSecretMaterial(11) }
        assertEquals(11L, updatedIdentity.id)
        assertEquals("ssh-rsa AAAANewEncryptedKey replacement@test", updatedIdentity.publicKey)
        assertEquals(SecretStorageState.AVAILABLE, updatedIdentity.passphraseStorageState)
        assertEquals(replacementKey, updatedSecrets?.primarySecret)
        assertEquals("new-passphrase", updatedSecrets?.passphrase)
    }

    @Test
    fun cleared_saved_passphrase_survives_relaunch_and_stays_blocked_in_host_flows() {
        val firstContainer = AppContainer.create(context)
        val identityId = runBlocking {
            val created = firstContainer.foundationGraph.identityRepository.upsert(
                identity = Identity(
                    name = "Relaunch encrypted key",
                    kind = IdentityKind.IMPORTED_KEY,
                    publicKey = "ssh-rsa AAAARelaunchKey relaunch@test",
                    hasSecret = true,
                    hasPassphrase = true,
                    passphraseStorageState = SecretStorageState.AVAILABLE,
                ),
                secrets = IdentitySecretMaterial(
                    primarySecret = "existing-relaunch-encrypted-key",
                    passphrase = "saved-passphrase",
                ),
            )
            firstContainer.foundationGraph.identityRepository.upsert(
                identity = created.copy(passphraseStorageState = SecretStorageState.MISSING),
                secrets = null,
            ).id
        }
        val hostId = runBlocking {
            firstContainer.foundationGraph.hostRepository.upsert(
                Host(
                    label = "Relaunch encrypted host",
                    address = "10.0.2.2",
                    port = 3122,
                    username = "fixture",
                    identityId = identityId,
                    authKind = HostAuthKind.KEY,
                ),
            ).id
        }
        val clearedIdentity = runBlocking {
            firstContainer.foundationGraph.identityRepository.getIdentity(identityId)
        }
        val clearedSecrets = runBlocking {
            firstContainer.foundationGraph.identityRepository.getSecretMaterial(identityId)
        }
        assertEquals(SecretStorageState.MISSING, clearedIdentity?.passphraseStorageState)
        assertFalse(clearedIdentity?.isAuthenticationReady == true)
        assertEquals("existing-relaunch-encrypted-key", clearedSecrets?.primarySecret)
        assertNull(clearedSecrets?.passphrase)

        val relaunchedContainer = AppContainer.create(context)
        composeRule.setContent {
            HostsScreen(
                hostRepository = relaunchedContainer.foundationGraph.hostRepository,
                identityRepository = relaunchedContainer.foundationGraph.identityRepository,
            )
        }
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("host_identity_label_$hostId")
                    .assertTextContains("Passphrase required before connecting: Relaunch encrypted key", substring = true)
            }.isSuccess
        }
        composeRule.onNodeWithTag("host_identity_label_$hostId")
            .assertTextContains("Passphrase required before connecting: Relaunch encrypted key")
        composeRule.onNodeWithTag("host_repair_$hostId").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("host_no_key_identities").assertIsDisplayed()
            }.isSuccess
        }

        val relaunchedIdentity = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getIdentity(identityId)
        }
        val relaunchedSecrets = runBlocking {
            relaunchedContainer.foundationGraph.identityRepository.getSecretMaterial(identityId)
        }
        assertEquals(SecretStorageState.MISSING, relaunchedIdentity?.passphraseStorageState)
        assertFalse(relaunchedIdentity?.isAuthenticationReady == true)
        assertEquals("existing-relaunch-encrypted-key", relaunchedSecrets?.primarySecret)
        assertNull(relaunchedSecrets?.passphrase)
    }

    @Test
    fun deleting_identity_warns_and_leaves_host_repair_path_with_duplicate_details() {
        val identityOne = Identity(
            id = 1,
            name = "Shared key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAFirstKey duplicate@test",
            hasSecret = true,
        )
        val identityTwo = Identity(
            id = 2,
            name = "Shared key",
            kind = IdentityKind.GENERATED_KEY,
            publicKey = "ssh-rsa AAAASecondKey duplicate@test",
            hasSecret = true,
        )
        val identityRepository = FakeIdentityRepository(
            initialIdentities = listOf(identityOne, identityTwo),
            initialSecrets = mapOf(
                1L to IdentitySecretMaterial(primarySecret = "first-key"),
                2L to IdentitySecretMaterial(primarySecret = "second-key"),
            ),
        )
        val hostRepository = FakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Prod",
                    address = "10.0.2.2",
                    port = 22,
                    username = "root",
                    identityId = 1,
                    authKind = HostAuthKind.KEY,
                ),
            ),
        )
        var showHosts by mutableStateOf(false)

        composeRule.setContent {
            if (showHosts) {
                HostsScreen(
                    hostRepository = hostRepository,
                    identityRepository = identityRepository,
                )
            } else {
                IdentitiesScreen(identityRepository = identityRepository)
            }
        }
        composeRule.onNodeWithTag("identity_detail_1").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_detail_2").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_delete_1").performClick()
        composeRule.onNodeWithTag("identity_delete_warning").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_delete_detail").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_delete_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            identityRepository.currentIdentities().none { it.id == 1L }
        }

        composeRule.runOnIdle {
            showHosts = true
        }

        composeRule.onNodeWithTag("host_identity_label_1").assertTextContains("Identity needs repair")
        composeRule.onNodeWithTag("host_repair_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_repair_1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("host_identity_option_2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("host_identity_option_1").assertCountEquals(0)
        composeRule.onNodeWithTag("host_identity_option_2").assertIsDisplayed()
        composeRule.onNodeWithText("Reusable generated key identity").assertIsDisplayed()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_identity_error")
            .assertTextContains("Select a reusable key identity before saving.", substring = true)
    }

    @Test
    fun blocked_password_identity_surfaces_recovery_and_repair_flow() {
        val identity = Identity(
            id = 5,
            name = "Restart-safe password",
            kind = IdentityKind.PASSWORD,
            hasSecret = true,
            secretStorageState = SecretStorageState.BLOCKED,
        )
        val repository = FakeIdentityRepository(
            initialIdentities = listOf(identity),
            secretFailureIds = setOf(5L),
        )

        composeRule.setContent {
            IdentitiesScreen(identityRepository = repository)
        }

        composeRule.onNodeWithTag("identity_repair_hint_5").assertIsDisplayed()
        composeRule.onNodeWithTag("identity_edit_5").performClick()
        composeRule.onNodeWithTag("identity_editor_save").performClick()
        composeRule.onNodeWithText("Re-enter the password to repair this identity.").assertIsDisplayed()

        composeRule.onNodeWithTag("identity_password_field").performTextInput("repaired-secret")
        composeRule.onNodeWithTag("identity_editor_save").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            repository.currentIdentities().first().secretStorageState == SecretStorageState.AVAILABLE
        }

        val repaired = repository.currentIdentities().first()
        assertEquals(SecretStorageState.AVAILABLE, repaired.secretStorageState)
        assertEquals("repaired-secret", runBlocking { repository.getSecretMaterial(5) }?.primarySecret)
    }

    @Test
    fun blocked_identity_is_excluded_from_host_selection_and_shows_repair_cta() {
        val blockedIdentity = Identity(
            id = 9,
            name = "Broken password",
            kind = IdentityKind.PASSWORD,
            hasSecret = true,
            secretStorageState = SecretStorageState.BLOCKED,
        )
        val host = Host(
            id = 1,
            label = "Prod",
            address = "10.0.2.2",
            port = 22,
            username = "root",
            identityId = 9,
            authKind = HostAuthKind.PASSWORD,
        )
        val identityRepository = FakeIdentityRepository(
            initialIdentities = listOf(blockedIdentity),
            secretFailureIds = setOf(9L),
        )
        val hostRepository = FakeHostRepository(initialHosts = listOf(host))

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_identity_label_1")
            .assertTextContains("Identity needs repair: Broken password", substring = true)
        composeRule.onNodeWithTag("host_repair_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_repair_1").performClick()
        composeRule.onNodeWithTag("host_no_password_identities").assertIsDisplayed()
        composeRule.onAllNodesWithTag("host_identity_option_9").assertCountEquals(0)
    }

    @Test
    fun unsaved_imported_key_session_message_distinguishes_missing_passphrase_from_repair() {
        val identity = Identity(
            id = 12,
            name = "Unsaved session key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAUnsavedSessionKey session@test",
            hasSecret = true,
            hasPassphrase = true,
            secretStorageState = SecretStorageState.AVAILABLE,
            passphraseStorageState = SecretStorageState.MISSING,
        )
        val host = Host(
            id = 4,
            label = "Fixture unsaved host",
            address = "10.0.2.2",
            port = 3122,
            username = "fixture",
            identityId = identity.id,
            authKind = HostAuthKind.KEY,
        )
        val identityRepository = FakeIdentityRepository(
            initialIdentities = listOf(identity),
            initialSecrets = mapOf(identity.id to IdentitySecretMaterial(primarySecret = "fixture-private-key")),
        )
        val hostRepository = FakeHostRepository(initialHosts = listOf(host))

        composeRule.setContent {
            SessionsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
                knownHostTrustRepository = FakeKnownHostTrustRepository(),
            )
        }

        composeRule.onNodeWithTag("session_host_identity_${host.id}")
            .assertTextContains("Passphrase required before connecting: Unsaved session key", substring = true)
        composeRule.onNodeWithTag("session_host_identity_${host.id}")
            .assertTextContains("Passphrase required before this key can connect", substring = true)
        composeRule.onNodeWithTag("session_connect_${host.id}").assertExists()
        composeRule.onNodeWithTag("session_connect_${host.id}").assertIsNotEnabled()
        composeRule.onAllNodesWithTag("session_trust_prompt").assertCountEquals(0)
    }

    @Test
    fun deleting_key_identity_preserves_host_auth_family_for_repair_without_auto_selection() {
        val deletedKeyIdentity = Identity(
            id = 1,
            name = "Primary key",
            kind = IdentityKind.IMPORTED_KEY,
            publicKey = "ssh-rsa AAAAPrimaryKey delete@test",
            hasSecret = true,
        )
        val replacementPassword = Identity(
            id = 2,
            name = "Fallback password",
            kind = IdentityKind.PASSWORD,
            hasSecret = true,
        )
        val replacementKey = Identity(
            id = 3,
            name = "Secondary key",
            kind = IdentityKind.GENERATED_KEY,
            publicKey = "ssh-rsa AAAASecondaryKey delete@test",
            hasSecret = true,
        )
        val identityRepository = FakeIdentityRepository(
            initialIdentities = listOf(deletedKeyIdentity, replacementPassword, replacementKey),
            initialSecrets = mapOf(
                1L to IdentitySecretMaterial(primarySecret = "deleted-key"),
                2L to IdentitySecretMaterial(primarySecret = "fallback-password"),
                3L to IdentitySecretMaterial(primarySecret = "replacement-key"),
            ),
        )
        val hostRepository = FakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Prod key host",
                    address = "10.0.2.2",
                    port = 22,
                    username = "root",
                    identityId = 1,
                    authKind = HostAuthKind.KEY,
                ),
            ),
        )

        runBlocking {
            identityRepository.deleteIdentity(1)
        }

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_repair_1").performClick()
        composeRule.onAllNodesWithTag("host_identity_option_2").assertCountEquals(0)
        composeRule.onAllNodesWithTag("host_identity_option_3").assertCountEquals(1)
        composeRule.onNodeWithTag("host_identity_option_3").assertIsDisplayed()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_identity_error")
            .assertTextContains("Select a reusable key identity before saving.", substring = true)
        composeRule.onNodeWithTag("host_identity_option_3").performClick()
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_row_1").assertIsDisplayed()
        composeRule.onNodeWithTag("host_identity_label_1")
            .assertTextContains("Secondary key", substring = true)
    }

    @Test
    fun legacy_orphaned_host_requires_explicit_auth_family_before_repairing() {
        val replacementPassword = Identity(
            id = 2,
            name = "Fallback password",
            kind = IdentityKind.PASSWORD,
            hasSecret = true,
        )
        val replacementKey = Identity(
            id = 3,
            name = "Secondary key",
            kind = IdentityKind.GENERATED_KEY,
            publicKey = "ssh-rsa AAAASecondaryKey delete@test",
            hasSecret = true,
        )
        val identityRepository = FakeIdentityRepository(
            initialIdentities = listOf(replacementPassword, replacementKey),
            initialSecrets = mapOf(
                2L to IdentitySecretMaterial(primarySecret = "fallback-password"),
                3L to IdentitySecretMaterial(primarySecret = "replacement-key"),
            ),
        )
        val hostRepository = FakeHostRepository(
            initialHosts = listOf(
                Host(
                    id = 1,
                    label = "Legacy orphan",
                    address = "10.0.2.2",
                    port = 22,
                    username = "root",
                    identityId = null,
                    authKind = HostAuthKind.UNKNOWN,
                ),
            ),
        )

        composeRule.setContent {
            HostsScreen(
                hostRepository = hostRepository,
                identityRepository = identityRepository,
            )
        }

        composeRule.onNodeWithTag("host_repair_1").performClick()
        composeRule.onNodeWithTag("host_auth_mode_required").assertExists()
        composeRule.onNodeWithTag("host_identity_waiting_for_auth_mode").assertExists()
        composeRule.onAllNodesWithTag("host_identity_option_2").assertCountEquals(0)
        composeRule.onAllNodesWithTag("host_identity_option_3").assertCountEquals(0)
        composeRule.onNodeWithTag("host_editor_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("host_auth_mode_error")
            .assertTextContains("Choose whether this host repairs", substring = true)
    }
}

private class FakeIdentityRepository(
    initialIdentities: List<Identity> = emptyList(),
    initialSecrets: Map<Long, IdentitySecretMaterial> = emptyMap(),
    secretFailureIds: Set<Long> = emptySet(),
) : IdentityRepository {
    private val identities = MutableStateFlow(initialIdentities)
    private val secrets = linkedMapOf<Long, IdentitySecretMaterial>().apply { putAll(initialSecrets) }
    private val blockedSecrets = secretFailureIds.toMutableSet()
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
        if (secrets?.primarySecret != null || secrets?.passphrase != null) {
            blockedSecrets.remove(persistedId)
        }
        val sanitizedSecrets = when {
            secrets == null -> {
                val previous = this.secrets[persistedId]
                IdentitySecretMaterial(
                    primarySecret = previous?.primarySecret,
                    passphrase = when {
                        !identity.hasPassphrase -> null
                        identity.passphraseStorageState == SecretStorageState.MISSING -> null
                        else -> previous?.passphrase
                    },
                )
            }

            else -> IdentitySecretMaterial(
                primarySecret = secrets.primarySecret ?: this.secrets[persistedId]?.primarySecret,
                passphrase = when {
                    !identity.hasPassphrase -> null
                    identity.passphraseStorageState == SecretStorageState.MISSING -> null
                    secrets.passphrase != null -> secrets.passphrase
                    else -> this.secrets[persistedId]?.passphrase
                },
            )
        }
        this.secrets[persistedId] = sanitizedSecrets
        identities.value = identities.value
            .filterNot { it.id == persistedId }
            .plus(persisted)
            .sortedBy { it.name }
        return persisted
    }

    override suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial? {
        if (blockedSecrets.contains(id)) {
            identities.value = identities.value.map { identity ->
                if (identity.id == id) {
                    identity.copy(secretStorageState = SecretStorageState.BLOCKED)
                } else {
                    identity
                }
            }
            throw SecretMaterialUnavailableException()
        }
        return secrets[id]
    }

    override suspend fun deleteIdentity(id: Long) {
        identities.value = identities.value.filterNot { it.id == id }
        secrets.remove(id)
    }

    fun currentIdentities(): List<Identity> = identities.value
}

private class FakeHostRepository : HostRepository {
    private val hosts = MutableStateFlow<List<Host>>(emptyList())

    constructor(initialHosts: List<Host> = emptyList()) {
        hosts.value = initialHosts
    }

    override fun observeHosts() = hosts

    override suspend fun getHost(id: Long): Host? = hosts.value.firstOrNull { it.id == id }

    override suspend fun upsert(host: Host): Host {
        val persisted = host.copy(id = host.id.takeIf { it != 0L } ?: (hosts.value.maxOfOrNull(Host::id) ?: 0L) + 1L)
        hosts.value = hosts.value.filterNot { it.id == persisted.id } + persisted
        return persisted
    }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) {
        hosts.value = hosts.value.map { host ->
            if (host.id == id) host.copy(isFavorite = isFavorite) else host
        }
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) = Unit

    override suspend fun deleteHost(id: Long) {
        hosts.value = hosts.value.filterNot { it.id == id }
    }
}

private class FakeKnownHostTrustRepository : KnownHostTrustRepository {
    private val trusts = MutableStateFlow<List<KnownHostTrust>>(emptyList())

    override fun observeTrustedHosts(): Flow<List<KnownHostTrust>> = trusts

    override suspend fun findTrustedHost(host: String, port: Int): KnownHostTrust? =
        trusts.value.firstOrNull { it.host == host && it.port == port }

    override suspend fun upsert(trust: KnownHostTrust): KnownHostTrust {
        trusts.value = trusts.value.filterNot { it.host == trust.host && it.port == trust.port } + trust
        return trust
    }

    override suspend fun deleteByEndpoint(host: String, port: Int) {
        trusts.value = trusts.value.filterNot { it.host == host && it.port == port }
    }
}

private fun generateRsaKeyPair(): KeyPair =
    SecurityUtils.getKeyPairGenerator("RSA").apply { initialize(2048) }.generateKeyPair()

private fun writePrivateKey(keyPair: KeyPair, passphrase: String?): String {
    val output = ByteArrayOutputStream()
    val encryption = passphrase?.let {
        OpenSSHKeyEncryptionContext().apply {
            setCipherType("256")
            setPassword(it)
        }
    }
    OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "ui-test-key", encryption, output)
    return output.toString(Charsets.UTF_8.name())
}

private class ScriptedImportedKeyImportService(
    private val scriptedResults: List<ImportedKeyParseResult>,
) : ImportedKeyImportService() {
    private var index = 0

    override fun parse(privateKeyMaterial: String, passphrase: String?): ImportedKeyParseResult {
        val result = scriptedResults.getOrElse(index) { scriptedResults.last() }
        index += 1
        return result
    }
}

private class ScriptedGeneratedKeyIdentityService(
    private val generatedMaterial: GeneratedKeyMaterial,
) : GeneratedKeyIdentityService() {
    override fun generate(): GeneratedKeyMaterial = generatedMaterial
}

private fun closeKeyboardIfShown() {
    runCatching { closeSoftKeyboard() }
}
