package io.github.jtsang4.aterm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.di.AppContainer
import io.github.jtsang4.aterm.feature.identities.IdentitiesScreen
import io.github.jtsang4.aterm.core.domain.model.Identity
import io.github.jtsang4.aterm.core.domain.model.IdentityKind
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.domain.repository.IdentityRepository
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
        composeRule.onNodeWithTag("identity_import_stub").assertIsDisplayed()

        composeRule.onNodeWithTag("identity_stub_back").performClick()
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
}

private class FakeIdentityRepository : IdentityRepository {
    private val identities = MutableStateFlow<List<Identity>>(emptyList())
    private val secrets = linkedMapOf<Long, IdentitySecretMaterial>()
    private var nextId = 1L

    override fun observeIdentities(): Flow<List<Identity>> = identities

    override suspend fun getIdentity(id: Long): Identity? = identities.value.firstOrNull { it.id == id }

    override suspend fun upsert(identity: Identity, secrets: IdentitySecretMaterial?): Identity {
        val persistedId = identity.id.takeIf { it != 0L } ?: nextId++
        val existing = identities.value.firstOrNull { it.id == persistedId }
        val persisted = identity.copy(
            id = persistedId,
            hasSecret = identity.hasSecret || existing?.hasSecret == true || secrets?.primarySecret != null,
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
}
