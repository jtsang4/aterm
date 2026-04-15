package io.github.jtsang4.aterm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

internal class TestPersistenceResetRule : ExternalResource() {
    lateinit var context: Context
        private set

    override fun before() {
        context = ApplicationProvider.getApplicationContext()
        resetTestPersistenceState(context)
    }

    override fun after() {
        resetTestPersistenceState(context)
    }
}
