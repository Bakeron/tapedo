package io.github.bakeron.tapedo.ui.home

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.ui.cards.IndexCard
import io.github.bakeron.tapedo.ui.cards.appLocale
import io.github.bakeron.tapedo.ui.cards.rememberToday
import io.github.bakeron.tapedo.ui.cards.shareNote
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The card as it looks on the stack, whole and read-only (author 21.11.2026: a nicer view without the editing
 * fields), over the card view. "Share as image" sends exactly this picture - e.g. to WhatsApp.
 */
@Composable
fun CardPreview(visible: Boolean, note: Note, number: Int?, onClose: () -> Unit) {
    val still = TapedoTheme.reduceMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (still) EnterTransition.None else fadeIn(tween(200)),
        exit = if (still) ExitTransition.None else fadeOut(tween(200)),
    ) {
        BackHandler(onBack = onClose)
        val context = LocalContext.current
        val scheme = MaterialTheme.colorScheme
        val locale = appLocale()
        val today = rememberToday()
        val scope = rememberCoroutineScope()
        val layer = rememberGraphicsLayer()
        var sharing by remember { mutableStateOf(false) }
        // The picture is the card's text unencrypted - it goes once the preview closes (the messenger has its
        // own copy by then), or with the next share at the latest.
        DisposableEffect(Unit) {
            onDispose { scope.launch(NonCancellable + Dispatchers.IO) { clearShared(context) } }
        }
        val shown = note.tidy()
        Column(
            Modifier
                .fillMaxSize()
                .background(scheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.card_preview_close))
                }
                Text(
                    stringResource(R.string.card_preview),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // The picture is the card on the screen's own paper, with a margin: the priority tape sticks out
                // over the corner, and a messenger shows a transparent edge as black.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                        .background(scheme.background)
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    IndexCard(shown, compact = false, preview = true, number = number)
                }
            }
            PaperButton(
                stringResource(R.string.card_share_image),
                onClick = {
                    if (sharing) return@PaperButton
                    sharing = true
                    scope.launch {
                        try {
                            shareImage(context, layer.toImageBitmap())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // No picture (e.g. no room for the file) - the card still goes out, as text.
                            shareNote(context, shown, today, locale)
                        } finally {
                            sharing = false
                        }
                    }
                },
                style = PaperButtonStyle.FILLED,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 52.dp),
            )
        }
    }
}

/**
 * The picture goes through the app's own cache and a content link, readable only by the app the user picks.
 * The last picture is removed before a new one is written, so cards do not pile up on the phone.
 */
private suspend fun shareImage(context: Context, image: ImageBitmap) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        clearShared(context)
        // A new name each time: a messenger may keep the old picture under an old link.
        val file = File(dir, "Tapedo-${System.currentTimeMillis()}.png")
        // A copy in plain memory: the drawn layer may be a hardware bitmap, which cannot be compressed.
        val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        FileProvider.getUriForFile(context, context.packageName + ".share", file)
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The read grant reaches the app picked in the chooser only through the clip.
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.card_share)))
}

private fun clearShared(context: Context) {
    File(context.cacheDir, SHARED_DIR).listFiles()?.forEach { it.delete() }
}

/** Folder in the app's cache for the picture being shared - named in res/xml/share_paths.xml too. */
private const val SHARED_DIR = "shared"
