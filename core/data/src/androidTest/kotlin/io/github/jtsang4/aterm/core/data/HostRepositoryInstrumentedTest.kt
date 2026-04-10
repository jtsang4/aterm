package io.github.jtsang4.aterm.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jtsang4.aterm.core.data.local.AtermDatabase
import io.github.jtsang4.aterm.core.data.repository.RoomHostRepository
import io.github.jtsang4.aterm.core.data.repository.RoomIdentityRepository
import io.github.jtsang4.aterm.core.domain.fixtures.sampleHost
import io.github.jtsang4.aterm.core.domain.fixtures.sampleIdentity
import io.github.jtsang4.aterm.core.domain.model.IdentitySecretMaterial
import io.github.jtsang4.aterm.core.security.crypto.KeystoreAesGcmCipher
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: AtermDatabase
    private lateinit var hostRepository: RoomHostRepository
    private lateinit var identityRepository: RoomIdentityRepository
    private lateinit var cipher: KeystoreAesGcmCipher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AtermDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cipher = KeystoreAesGcmCipher("host.repo.test.${UUID.randomUUID()}")
        hostRepository = RoomHostRepository(database.hostDao())
        identityRepository = RoomIdentityRepository(database, database.identityDao(), cipher)
    }

    @After
    fun tearDown() {
        database.close()
        cipher.deleteKey()
    }

    @Test
    fun observeHosts_ordersFavoritesThenLabels() = runTest {
        val identity = identityRepository.upsert(
            sampleIdentity().copy(id = 0),
            IdentitySecretMaterial(primarySecret = "host-order-secret"),
        )
        hostRepository.upsert(
            sampleHost(id = 0, identityId = identity.id).copy(
                label = "Zulu",
                isFavorite = false,
            ),
        )
        hostRepository.upsert(
            sampleHost(id = 0, identityId = identity.id).copy(
                label = "Alpha",
                isFavorite = true,
            ),
        )
        hostRepository.upsert(
            sampleHost(id = 0, identityId = identity.id).copy(
                label = "Bravo",
                isFavorite = false,
            ),
        )

        val hosts = hostRepository.observeHosts().first()

        assertEquals(listOf("Alpha", "Bravo", "Zulu"), hosts.map { it.label })
    }

    @Test
    fun deletingIdentityPreservesHostRecordAndClearsOnlyIdentityLink() = runTest {
        val firstIdentity = identityRepository.upsert(
            sampleIdentity().copy(id = 0, name = "Primary"),
            IdentitySecretMaterial(primarySecret = "first-secret"),
        )
        val secondIdentity = identityRepository.upsert(
            sampleIdentity().copy(id = 0, name = "Secondary"),
            IdentitySecretMaterial(primarySecret = "second-secret"),
        )
        val firstHost = hostRepository.upsert(
            sampleHost(id = 0, identityId = firstIdentity.id).copy(label = "Primary host"),
        )
        val secondHost = hostRepository.upsert(
            sampleHost(id = 0, identityId = secondIdentity.id).copy(label = "Secondary host"),
        )

        identityRepository.deleteIdentity(firstIdentity.id)

        val persistedFirstHost = hostRepository.getHost(firstHost.id)
        val persistedSecondHost = hostRepository.getHost(secondHost.id)

        assertNull(persistedFirstHost?.identityId)
        assertEquals(secondIdentity.id, persistedSecondHost?.identityId)
        assertEquals("Primary host", persistedFirstHost?.label)
        assertEquals("Secondary host", persistedSecondHost?.label)
    }
}
