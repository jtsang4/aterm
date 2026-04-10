package io.github.jtsang4.aterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.hosts.HostsScreen
import io.github.jtsang4.aterm.feature.identities.IdentitiesScreen
import io.github.jtsang4.aterm.feature.identities.ImportedKeyImportService
import io.github.jtsang4.aterm.feature.identities.ImportedKeyParseResult
import io.github.jtsang4.aterm.core.domain.model.Host
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.repository.HostRepository
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
import java.io.ByteArrayOutputStream
import java.io.File
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityPasswordFlowsInstrumentedTest {
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
        composeRule.onNodeWithTag("identity_import_editor").assertIsDisplayed()

        composeRule.onNodeWithTag("identity_import_cancel").performClick()
        composeRule.onNodeWithTag("identity_generate_key_action").performClick()
        composeRule.onNodeWithTag("identity_generate_stub").assertIsDisplayed()
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
        composeRule.onNodeWithTag("identity_import_save").performClick()

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
        assertEquals(importedIdentity.id, relaunchedIdentity.id)
        assertEquals(true, relaunchedIdentity.hasSecret)
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
        composeRule.onNodeWithText("Imported deploy key").assertIsDisplayed()
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
}

private class FakeIdentityRepository(
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
            hasPassphrase = identity.hasPassphrase || existing?.hasPassphrase == true || secrets?.passphrase != null,
            updatedAt = Instant.now(),
        )
        this.secrets[persistedId] = secrets ?: this.secrets[persistedId] ?: IdentitySecretMaterial()
        identities.value = identities.value
            .filterNot { it.id == persistedId }
            .plus(persisted)
            .sortedBy { it.name }
        return persisted
    }

    override suspend fun getSecretMaterial(id: Long): IdentitySecretMaterial? = secrets[id]

    override suspend fun deleteIdentity(id: Long) {
        identities.value = identities.value.filterNot { it.id == id }
        secrets.remove(id)
    }

    fun currentIdentities(): List<Identity> = identities.value
}

private class FakeHostRepository : HostRepository {
    private val hosts = MutableStateFlow<List<Host>>(emptyList())

    override fun observeHosts() = hosts

    override suspend fun getHost(id: Long): Host? = hosts.value.firstOrNull { it.id == id }

    override suspend fun upsert(host: Host): Host {
        val persisted = host.copy(id = host.id.takeIf { it != 0L } ?: (hosts.value.maxOfOrNull(Host::id) ?: 0L) + 1L)
        hosts.value = hosts.value.filterNot { it.id == persisted.id } + persisted
        return persisted
    }

    override suspend fun markUsed(id: Long, usedAt: Instant) = Unit

    override suspend fun deleteHost(id: Long) {
        hosts.value = hosts.value.filterNot { it.id == id }
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
