package io.github.bakeron.tapedo.report

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import io.github.bakeron.tapedo.BuildConfig
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.report.DeviceInfo
import io.github.bakeron.tapedo.core.report.ErrorReport
import io.github.bakeron.tapedo.core.report.Problem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Send the error to the app's author": opens the user's mail app with the report filled in - the user
 * sees it and sends it (or not). Nothing is sent by Tapedo itself. No mail app: the report goes to the
 * clipboard with the address shown.
 *
 * The address comes from `tapedo.reportEmail` in local.properties (outside Git), so it is not public on
 * GitHub; a build without it has no report button.
 */
object ReportMail {

    private const val ADDRESS = BuildConfig.REPORT_EMAIL

    val available: Boolean get() = ADDRESS.isNotEmpty()

    /** The author's address, for "About" (empty in a build without it). */
    val address: String get() = ADDRESS

    /** An empty mail to the author - a question, an idea, anything. */
    fun write(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS")))
        } catch (e: ActivityNotFoundException) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(ADDRESS, ADDRESS))
            Toast.makeText(context, context.getString(R.string.report_no_mail_address, ADDRESS), Toast.LENGTH_LONG).show()
        }
    }

    fun send(context: Context, problem: Problem, language: String) {
        val device = DeviceInfo(
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?",
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            language = language,
        )
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val subject = ErrorReport.subject(problem, device)
        val body = ErrorReport.body(problem, device, time)
        // Subject and body in the mailto: link as well - some mail apps read only that, others only the extras.
        val uri = Uri.parse("mailto:${ADDRESS}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(subject, "$subject\n\n$body"))
            Toast.makeText(context, context.getString(R.string.report_no_mail, ADDRESS), Toast.LENGTH_LONG).show()
        }
    }
}
