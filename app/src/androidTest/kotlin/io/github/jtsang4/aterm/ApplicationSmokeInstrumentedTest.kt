package io.github.jtsang4.aterm

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationSmokeInstrumentedTest {
    @Test
    fun uses_aterm_application_container() {
        val application = ApplicationProvider.getApplicationContext<AtermApplication>()

        assertTrue(application.appContainer.topLevelDestinations.isNotEmpty())
        assertEquals("io.github.jtsang4.aterm", application.packageName)
        assertNotNull(application.appContainer.foundationGraph.settingsRepository)
        assertNotNull(application.appContainer.foundationGraph.identityRepository)
    }
}
