package io.github.bakeron.tapedo.core.report

/** What the phone is - nothing the user wrote or said. */
data class DeviceInfo(
    val appVersion: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    /** The speech recognition language, e.g. "pl-PL". */
    val language: String,
)

/** A problem the user can report to the app's author with one button. */
sealed interface Problem {
    /** The phone's recognizer refused Tapedo's own recording; the old way is used instead. */
    data class OwnAudioRefused(val code: Int) : Problem

    /** A recording ended with an error. [error] is the SpeechError name, [code] the recognizer's number. */
    data class RecordingFailed(val error: String, val code: Int?) : Problem

    /** Sent by hand from Settings - "About", with no error behind it: the user describes it in the mail. */
    data object UserReport : Problem
}

/**
 * The e-mail behind "Send the error to the app's author" (the author's decision 08.11.2026): the user sees it
 * in their mail app and sends it themselves - nothing goes out on its own. Only the phone model, versions and
 * the error number - never notes, transcripts, audio or keys. The body ends with a ready prompt for Claude Code,
 * so the author can start the fix at once. Written in Polish: it is read by the author, not shown in the app.
 */
object ErrorReport {

    fun subject(problem: Problem, device: DeviceInfo): String =
        "Tapedo ${device.appVersion} - błąd: ${title(problem)} (${device.manufacturer} ${device.model}, Android ${device.androidVersion})"

    fun body(problem: Problem, device: DeviceInfo, time: String): String {
        val facts = buildString {
            appendLine("Co się stało: ${whatHappened(problem)}")
            code(problem)?.let { appendLine("Kod błędu rozpoznawania mowy: $it (stała SpeechRecognizer.ERROR_*)") }
            if (problem is Problem.RecordingFailed) appendLine("Błąd w Tapedo: SpeechError.${problem.error}")
            appendLine("Wersja Tapedo: ${device.appVersion}")
            appendLine("Telefon: ${device.manufacturer} ${device.model}")
            appendLine("Android: ${device.androidVersion} (API ${device.sdk})")
            appendLine("Język rozpoznawania: ${device.language}")
            appendLine("Kiedy: $time")
        }
        return buildString {
            appendLine("(Tu możesz dopisać, co robiłeś, gdy pojawił się błąd.)")
            appendLine()
            append(facts)
            appendLine()
            appendLine("W wiadomości nie ma notatek ani nagrań - tylko dane telefonu i kod błędu.")
            appendLine()
            appendLine("----- Prompt dla Claude Code -----")
            appendLine("Projekt Tapedo (github.com/Bakeron/tapedo). Użytkownik wysłał z aplikacji zgłoszenie błędu:")
            append(facts)
            appendLine(task(problem))
            append("Najpierw opisz przyczynę, potem zaproponuj poprawkę z testem.")
        }
    }

    private fun title(problem: Problem): String = when (problem) {
        is Problem.OwnAudioRefused -> "telefon odrzucił nagrywanie bez przerw"
        is Problem.RecordingFailed -> "nagrywanie się nie udało"
        Problem.UserReport -> "zgłoszenie z „O aplikacji”"
    }

    private fun whatHappened(problem: Problem): String = when (problem) {
        is Problem.OwnAudioRefused ->
            "rozpoznawanie mowy w telefonie odrzuciło dźwięk nagrany przez Tapedo (EXTRA_AUDIO_SOURCE). " +
                "Tapedo przełączyło się na stary sposób - nagrywa, ale po pauzie może zgubić słowo."
        is Problem.RecordingFailed -> "nagranie skończyło się błędem i nic nie zostało zapisane."
        Problem.UserReport -> "użytkownik sam zgłasza błąd z Ustawień - „O aplikacji” (opis na górze maila)."
    }

    private fun code(problem: Problem): Int? = when (problem) {
        is Problem.OwnAudioRefused -> problem.code
        is Problem.RecordingFailed -> problem.code
        Problem.UserReport -> null
    }

    private fun task(problem: Problem): String = when (problem) {
        is Problem.OwnAudioRefused ->
            "Sprawdź w app/src/main/java/io/github/bakeron/tapedo/speech/AndroidSpeechInput.kt i ContinuousMic.kt, " +
                "dlaczego rozpoznawanie na tym telefonie nie przyjmuje dźwięku z EXTRA_AUDIO_SOURCE " +
                "(format: PCM 16 bit, mono, 16 kHz, sesja segmentowa) i czy da się to obejść."
        is Problem.RecordingFailed ->
            "Sprawdź w app/src/main/java/io/github/bakeron/tapedo/speech/AndroidSpeechInput.kt " +
                "i core/.../speech/RecognizerErrorPolicy.kt, skąd ten błąd i czy nagrywanie mogło go przetrwać."
        Problem.UserReport -> "Przeczytaj opis użytkownika na górze maila, odtwórz błąd i znajdź jego przyczynę w kodzie."
    }
}
