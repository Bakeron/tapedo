# Tapedo

**Speak on the go - Tapedo turns it into index cards.** An Android voice recorder that cuts quick spoken notes into separate cards: notes, checklists, tasks with due dates, events, ideas and shopping lists grouped by shop section. It works offline with simple rules, or better with a free AI model using your own key.

[Polski opis niżej](#tapedo---po-polsku)

<p>
  <img src="docs/screenshots/home-paper.png" width="230" alt="Home screen: a shopping list on squared paper and a task card, the type tapes and the red record key">
  <img src="docs/screenshots/review-paper.png" width="230" alt="Review window after a recording: the transcript on a typed sheet and the cards it was cut into">
  <img src="docs/screenshots/stack-paper.png" width="230" alt="The card stack: a task, an idea, a packing list and an event">
  <img src="docs/screenshots/editor-panel.png" width="230" alt="Card editor in the dark Panel theme">
</p>

## What it does

- **One big record key.** It sinks like a tape recorder key, the phone buzzes, the screen stays on, and a seismograph pen draws your voice level. Recording ends on silence or with "stop", with a length limit you set.
- **Cards, not a transcript.** "Tomorrow hand in the report, it's urgent. Shopping: eggs, tomatoes, milk and coffee" becomes a task (due tomorrow, high priority) and a shopping list grouped into produce, dairy and drinks.
- **Review before saving.** The text comes out of a typewriter letter by letter, then drops in as cards you can edit or throw away. Back saves - notes are kept by default.
- **A stack you can work with.** Tick list items and tasks right on the card (a hand-drawn strike-through), edit, share as plain text, delete with undo.
- **AI is optional.** Without AI, keyword rules decide the type, priority ("urgent"), due date ("tomorrow", "on Friday") and event time ("at 10"). With Gemini or Groq (free plans, your own key) the cards are better. Any AI error falls back to the rules - a note is never lost.
- **Light "Paper" and dark "Panel" themes**, Polish and English, screen reader labels, a "reduce motion" switch, a landscape layout.

## Privacy

Designed for notes that may contain sensitive data:

- Notes are stored only on the phone, in a database encrypted with SQLCipher (AES-256). The database key is protected by the Android Keystore.
- Tapedo never saves sound - only the recognised text is kept.
- Speech recognition runs on the phone first. If the phone has no offline language pack, Tapedo asks before using online recognition (which sends your voice to Google): "this time" / "always", or never in Settings. On older Android versions the system may go online by itself - the app says so.
- With an AI provider switched on, only the note text goes to that provider (Google Gemini or Groq) - no audio, no other data. Each provider's data policy is explained in the app before you paste a key.
- API keys are encrypted with the Keystore and never written to logs. App backups are turned off, so the database does not leave the phone in a cloud backup.
- If Android ever loses the encryption key, Tapedo does not delete anything: it sets the old files aside, starts an empty stack and tells you why.

## Build and run

Requirements: JDK 17 or newer and the Android SDK (platform 37). No Android Studio needed.

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test :app:testDebugUnitTest
```

The emulator cannot recognise speech reliably, so debug builds accept a ready transcript:

```bash
adb shell am start -n io.github.bakeron.tapedo/.MainActivity --es debug_transcript "'Shopping: milk, bread and coffee'"
```

Release builds ignore it.

## How it is built

| Part | Choice | Why |
|---|---|---|
| Language and UI | Kotlin, Jetpack Compose, Material 3 (heavily restyled) | Native access to speech recognition, Keystore and the screen; Compose keeps every custom control in one language |
| Logic | `core` module in plain Kotlin (no Android) | Note model, keyword rules and AI clients are testable on the JVM in seconds and ready for Kotlin Multiplatform later |
| Database | Room + SQLCipher | Typed queries and a fully encrypted file |
| Settings | DataStore | Asynchronous, no main-thread disk access |
| AI | Ktor client, Gemini and Groq behind one interface | Strategy pattern - providers are swappable; JSON answers are validated |
| Wiring | A small hand-written container | Fewer moving parts than Hilt for an app this size |

```
core/   model, rules (no-AI mode), AI clients, interfaces for phone features   - JVM unit tests
app/    Compose screens, speech recognition, Keystore, encrypted Room, DataStore
```

The look follows one idea - **a pocket recorder and paper index cards**: label-maker tapes for the type picker, a seismograph for the voice, squared paper for lists, tape on the corner for priority (colour and pattern, readable without colour vision), a blue rubber stamp instead of AI sparkles.

## Built with AI

I am a WordPress developer without Android experience. Tapedo is written with [Claude Code](https://claude.com/claude-code): I decide what the app should do, how it should behave and what is acceptable; Claude proposes the architecture, writes the code and explains it. The process is part of the project:

- decisions and open questions are written down in `zalozenia.md` (Polish) before they are built, and the build plan lives in `plan-1a.md`;
- every step is checked on an emulator with screenshots, and with unit tests (55 at the moment);
- the project has its own audit skills in `.claude/skills` - code correctness, simplification, performance, security, plus shared audits of accessibility, look, texts, release readiness, dependencies, tests and portfolio. Their findings are fixed only after I decide.

## Status

Stage 1a (the core) - almost ready:

- done: recording, offline / online speech recognition, no-AI rules, Gemini and Groq, review window, card stack with editing, ticking, sharing and undo, encrypted database, themes, PL / EN, accessibility pass, licences screen;
- still to do: speech test on a real phone, a signed release APK on GitHub Releases, a demo GIF.

Next (1b): Whisper transcription, more AI providers, private mode, app lock, a home-screen widget.

## Licence

[MIT](LICENSE). Third-party licences (SQLCipher, AndroidX, Kotlin, Ktor, OkHttp, the fonts) are listed in the app: Settings → Open source licences.

---

# Tapedo - po polsku

**Mów w biegu - Tapedo rozpisze to na karteczki.** Dyktafon na Androida, który zamienia szybkie notatki głosowe w osobne karteczki: notatki, listy do odhaczania, zadania z terminem, spotkania, pomysły i listy zakupów pogrupowane na działy sklepu. Działa bez internetu na prostych regułach, a lepiej - z darmowym AI na własnym kluczu.

## Co umie

- **Jeden duży przycisk nagrywania** - zapada się jak klawisz magnetofonu, telefon drga, ekran nie gaśnie, a pisak sejsmografu rysuje poziom głosu. Koniec po ciszy albo przyciskiem „stop”, z limitem długości.
- **Karteczki zamiast transkrypcji** - „Jutro oddać raport szefowi, to pilne. Zakupy: jajka, pomidory, mleko i kawa” to zadanie (na jutro, wysoki priorytet) i lista zakupów z działami.
- **Okno po nagraniu** - tekst wychodzi z maszyny do pisania, potem wpada na stos jako karteczki do poprawienia albo wyrzucenia. „Wstecz” zapisuje.
- **Stos karteczek** - odhaczanie na karcie (odręczne przekreślenie), edycja, „Udostępnij”, usuwanie z „Cofnij”.
- **Podgląd i „Dopisz głosem”** - jedna karteczka na cały ekran (np. lista zakupów w sklepie); głosem dopisuje się nowe punkty albo tekst.
- **AI opcjonalnie** - bez AI typ, priorytet, termin i godzinę wybierają reguły. Z Gemini albo Groq (darmowe plany, własny klucz) karteczki są lepsze, a przy błędzie AI notatkę i tak rozpisują reguły.
- Motyw jasny „Papier” i ciemny „Panel”, język polski i angielski, wielkość tekstu, opisy dla czytnika ekranu, „Ogranicz animacje”, układ w poziomie.

## Prywatność

- Notatki tylko na telefonie, w bazie zaszyfrowanej SQLCipher (AES-256), klucz chroniony przez Android Keystore.
- Tapedo nie zapisuje dźwięku - zostaje sam tekst.
- Rozpoznawanie mowy najpierw na telefonie. Przez internet (głos trafia do Google) dopiero po zgodzie: „tym razem” / „zawsze”, albo nigdy w Ustawieniach. Na starszych Androidach system może sam użyć internetu - aplikacja o tym mówi.
- Przy włączonym AI do dostawcy trafia tylko tekst notatki. Zasady każdego dostawcy są opisane w aplikacji przed wklejeniem klucza.
- Klucze API zaszyfrowane, nigdy w logach. Kopie zapasowe aplikacji wyłączone.
- Gdy Android zgubi klucz szyfrowania, Tapedo nic nie kasuje - odkłada stare pliki, zaczyna od pustego stosu i wyjaśnia, co się stało.

## Uruchomienie

Jak wyżej w „Build and run”: `./gradlew :app:assembleDebug`, testy `./gradlew :core:test :app:testDebugUnitTest`.

## Jak powstało

Jestem programistą WordPress bez doświadczenia z Androidem. Tapedo powstaje z [Claude Code](https://claude.com/claude-code): ja decyduję, co aplikacja ma robić i co jest do przyjęcia, a Claude proponuje architekturę, pisze kod i go tłumaczy. Decyzje są spisane w `zalozenia.md`, plan w `plan-1a.md`, a projekt ma własne skille audytowe w `.claude/skills` (poprawność, uproszczenia, wydajność, bezpieczeństwo, dostępność, wygląd, teksty, wydanie, zależności, testy, portfolio). Poprawki z audytów wchodzą dopiero po mojej decyzji.

## Licencja

[MIT](LICENSE). Licencje bibliotek i krojów pisma: w aplikacji, Ustawienia → Licencje open source.
