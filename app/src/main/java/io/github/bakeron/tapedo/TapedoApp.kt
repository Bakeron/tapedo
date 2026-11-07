package io.github.bakeron.tapedo

import android.app.Application
import io.github.bakeron.tapedo.core.ai.AiConnection
import io.github.bakeron.tapedo.core.ai.AiUsage
import io.github.bakeron.tapedo.core.ai.HttpAiGateway
import io.github.bakeron.tapedo.core.ai.NoteStructurer
import io.github.bakeron.tapedo.core.ai.SecretStore
import io.github.bakeron.tapedo.core.ai.aiHttpClient
import io.github.bakeron.tapedo.core.data.NoteRepository
import io.github.bakeron.tapedo.core.settings.SettingsStore
import io.github.bakeron.tapedo.core.speech.SpeechInput
import io.github.bakeron.tapedo.data.FileLockedNotesNotice
import io.github.bakeron.tapedo.data.LockedNotesNotice
import io.github.bakeron.tapedo.data.RoomNoteRepository
import io.github.bakeron.tapedo.data.db.TapedoDatabase
import io.github.bakeron.tapedo.data.settings.DataStoreAiUsage
import io.github.bakeron.tapedo.data.settings.DataStoreSettings
import io.github.bakeron.tapedo.security.KeystoreSecretStore
import io.github.bakeron.tapedo.speech.AndroidSpeechInput
import io.github.bakeron.tapedo.ui.theme.NightMode
import io.ktor.client.engine.okhttp.OkHttp

class TapedoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NightMode.restore(this)
        container = AppContainer(this)
    }
}

/**
 * Hand-written dependency wiring - one place that builds the app's long-lived objects.
 * Small enough that a DI framework (Hilt) would add more setup than it saves.
 */
class AppContainer(private val app: Application) {
    private val http by lazy { aiHttpClient(OkHttp.create()) }

    val notes: NoteRepository by lazy { RoomNoteRepository { TapedoDatabase.open(app).notes() } }

    val lockedNotice: LockedNotesNotice by lazy { FileLockedNotesNotice(app) }

    val settings: SettingsStore by lazy { DataStoreSettings(app) }

    val secrets: SecretStore by lazy { KeystoreSecretStore(app) }

    val speech: SpeechInput by lazy { AndroidSpeechInput(app) }

    val aiUsage: AiUsage by lazy { DataStoreAiUsage(app) }

    private val gateway by lazy { HttpAiGateway(http) }

    val structurer: NoteStructurer by lazy { NoteStructurer(secrets, aiUsage, gateway) }

    val aiConnection: AiConnection by lazy { AiConnection(secrets, aiUsage, gateway) }
}
