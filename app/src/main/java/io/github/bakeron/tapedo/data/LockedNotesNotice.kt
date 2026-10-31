package io.github.bakeron.tapedo.data

import android.content.Context
import io.github.bakeron.tapedo.data.db.TapedoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Your old notes could not be decrypted" - shown once after Android lost the database key
 * and the old files were set aside (never deleted). An interface so the home screen can be tested without files.
 */
interface LockedNotesNotice {
    suspend fun pending(): Boolean

    suspend fun dismiss()
}

class FileLockedNotesNotice(private val context: Context) : LockedNotesNotice {
    override suspend fun pending() = withContext(Dispatchers.IO) { TapedoDatabase.lockedNoticePending(context) }

    override suspend fun dismiss() = withContext(Dispatchers.IO) { TapedoDatabase.dismissLockedNotice(context) }
}
