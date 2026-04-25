# MultiTimer Code-Dokumentation

Diese Datei dokumentiert die aktuelle Implementierung der MultiTimer-App.

## 1. Ziel der App

Die App verwaltet mehrere Timer parallel.
Jeder Timer hat eigene Zustandslogik und eine eigene Notification.
Beim Ablauf wird der Timer per TextToSpeech angesagt.

## 2. Technischer Rahmen

- Sprache: Java 21
- Plattform: Android
- minSdk: 26
- compileSdk: 36
- targetSdk: 36
- Build: Gradle 8.7, Android Gradle Plugin 8.5.2
- Paket: com.example.multitimer

## 3. Architektur-Ueberblick

Die App besteht aus einer einfachen 3-Schichten-Struktur:

1. UI-Schicht
- SplashActivity startet MainActivity.
- MainActivity verwaltet Dialoge, Eingaben und RecyclerView.
- TimerAdapter bindet Timerzustand auf die Timer-Card-UI.

2. Service-Schicht
- TimerService verwaltet alle Timer zentral (in-memory), tickt sekundenweise, setzt Notifications und TTS.

3. Persistenz-Schicht
- TimerPersistence speichert und laedt Timer als JSON in SharedPreferences.

## 4. Klassen und Verantwortungen

### SplashActivity
Datei: app/src/main/java/com/example/multitimer/SplashActivity.java

- Aktiviert Android SplashScreen API.
- Leitet direkt auf MainActivity weiter.

### MainActivity
Datei: app/src/main/java/com/example/multitimer/MainActivity.java

- UI-Einstiegspunkt der App.
- Initialisiert RecyclerView und Adapter.
- Oeffnet Dialoge fuer:
  - Neuer Timer
  - Timer bearbeiten
  - Timer abbrechen
  - Timer loeschen
- Fordert auf Android 13+ Notification-Permission an.
- Nutzt periodisches UI-Refresh (1s), um Restzeit und Status aktuell anzuzeigen.
- Zeigt Start-Banner als Overlay.
- Sperrt Hintergrund-Interaktion waehrend Dialogen mit `uiInteractionBlocker`.

Wichtige Methoden:

- `showCreateTimerDialog()`:
  - Validiert Name und Dauer.
  - Legt Timer ueber TimerService an.
- `showEditTimerDialog(ManagedTimer timer)`:
  - Ersetzt bestehenden Timer mit neuer Konfiguration.
- `refreshTimers()`:
  - Holt Snapshot vom TimerService.
  - Aktualisiert Adapter und Empty-State.
- `setDialogUiBlocked(boolean blocked)`:
  - Aktiviert/Deaktiviert Scrim und Interaktionen im Hintergrund.

### TimerAdapter
Datei: app/src/main/java/com/example/multitimer/TimerAdapter.java

- Bindet Timerzustand auf Card-Elemente.
- Definiert die Action-Callbacks ueber `OnTimerActionListener`.
- Setzt Statuschip-Farbe, Text und Blink-Animation.

UI-Verhalten pro Zustand:

1. Ready (angelegt, noch nicht gestartet)
- Status: "Bereit"
- Action-Button: Start
- Delete: aktiv
- Mute: inaktiv

2. Running
- Status: "Laeuft" (gelb + blinkend)
- Action-Button: Abbrechen
- Delete: inaktiv
- Mute: inaktiv

3. Completed
- Status: "Fertig" (gruen)
- Action-Button: Neustart
- Delete: aktiv
- Mute: aktiv, solange Notification nicht dismissed wurde
- Blinkt, solange Notification aktiv ist

4. Cancelled
- Status: "Abgebrochen" (rot)
- Action-Button: Neustart
- Delete: aktiv
- Mute: inaktiv

### ManagedTimer
Datei: app/src/main/java/com/example/multitimer/ManagedTimer.java

Datenmodell eines Timers mit Kernfeldern:

- `id`
- `name`
- `durationMillis`
- `endTimeMillis`
- `started`
- `completed`
- `cancelled`
- `notificationDismissed`

Kernmethoden:

- `isRunning(now)`
- `shouldComplete(now)`
- `markStarted(now)`
- `markCompleted()`
- `markCancelled()`
- `markNotificationDismissed()`

### TimerService
Datei: app/src/main/java/com/example/multitimer/TimerService.java

Zentrale Laufzeitlogik fuer alle Timer.

Wesentliche Aufgaben:

- Verarbeitet Start-Intents fuer Timer-Operationen.
- Fuehrt sekundenweises Tick-Update aus.
- Markiert abgelaufene Timer als completed.
- Haltet Foreground-Service aktiv, solange noetig.
- Verwalten einzelner Timer-Notifications (pro Timer eine Notification).
- Ansage abgeschlossener Timer ueber TextToSpeech.
- Persistiert Zustandsaenderungen.

Wichtige statische APIs fuer UI:

- `enqueueCreateTimer(...)`
- `enqueueRestartTimer(...)`
- `enqueueCancelTimer(...)`
- `enqueueReplaceTimer(...)`
- `enqueueDismissNotification(...)`
- `enqueueDismissTimer(...)`
- `getTimersSnapshot()`
- `ensureTimersLoaded(...)`
- `ensureServiceRunningForActiveTimers(...)`

#### Timer-Sortierung (`getTimersSnapshot`)

Die Anzeige wird in Gruppen sortiert:

1. Completed + Notification noch nicht dismissed
2. Running
3. Sonstige terminale Timer (z. B. dismissed oder cancelled)

Sortierung innerhalb der Gruppen:

- Running nach `endTimeMillis`
- Sonstige nach Name (case-insensitive), dann ID

#### Foreground-Service und Android 14+/16

- Service ist als Foreground-Service mit `foregroundServiceType="dataSync"` deklariert.
- Start erfolgt typisiert mit `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
- Relevante Permissions stehen im Manifest.

#### Notifications

- Running-Channel: niedrige Prioritaet.
- Finished-Channel: still, ohne Vibration/Sound.
- Jede Timer-ID entspricht einer eigenen Notification-ID.
- Bei completed wird eine Dismiss-Action angeboten.

#### TTS-Logik

- Beim Abschluss: Ansage "<Timername> Fertig".
- Wiederholung alle 5 Sekunden, solange completed und nicht dismissed.
- Fallback auf Standard-Locale, falls `Locale.GERMAN` nicht verfuegbar ist.

### TimerPersistence
Datei: app/src/main/java/com/example/multitimer/TimerPersistence.java

- Speichert Timerliste als JSON-Array in `SharedPreferences` (`multitimer_prefs`).
- Laedt beim Start und rekonstruiert `ManagedTimer`-Objekte.
- Parse-Fehler werden geloggt; Daten werden nicht blind ueberschrieben.

### BootReceiver
Datei: app/src/main/java/com/example/multitimer/BootReceiver.java

- Reagiert auf:
  - `BOOT_COMPLETED`
  - `LOCKED_BOOT_COMPLETED`
  - `MY_PACKAGE_REPLACED`
- Startet den Service nur dann, wenn aktive Timer vorhanden sind.

### TimerFormatter
Datei: app/src/main/java/com/example/multitimer/TimerFormatter.java

- Formatiert Dauer als:
  - `mm:ss` oder
  - `hh:mm:ss`
- Locale: Germany

## 5. AndroidManifest-relevante Punkte

Datei: app/src/main/AndroidManifest.xml

- Service: `.TimerService`, `foregroundServiceType="dataSync"`
- Receiver: `.BootReceiver`
- Permissions:
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_DATA_SYNC`
  - `POST_NOTIFICATIONS`
  - `RECEIVE_BOOT_COMPLETED`
  - `VIBRATE`

## 6. Lebenszyklus eines Timers

1. Benutzer legt Timer in MainActivity an.
2. MainActivity sendet Intent an TimerService (`enqueueCreateTimer`).
3. TimerService erzeugt ManagedTimer und persistiert Zustand.
4. Tick-Loop aktualisiert Restzeit und Completion.
5. Running-Timer hat aktive Notification.
6. Bei Ablauf:
- Status wird completed.
- Notification bleibt sichtbar (still).
- TTS-Ansage startet und wiederholt sich.
7. Benutzer kann:
- Neustarten
- Notification stummschalten (dismiss)
- Timer loeschen

## 7. Build und lokales Testen

Projektwurzel:
- /home/alex/MultiTimer

Debug-Build:
- ./gradlew --no-daemon assembleDebug --console=plain

APK-Ausgabe:
- app/build/outputs/apk/debug/app-debug.apk

## 8. Bekannte Wartungshinweise

- Bei TimerService-Aenderungen immer mit mehreren parallelen Timern testen.
- Notification-Logik nicht auf Sammelbenachrichtigung umstellen (pro Timer eigene Notification).
- Bei neuen Android-API-Aenderungen minSdk 26 und Java-21-Kompatibilitaet beachten.
- Bei Problemen auf Android 14+ zuerst Foreground-Service-Type und Permissions pruefen.

## 9. Moegliche Erweiterungen

- Diff-basiertes RecyclerView-Update (statt `notifyDataSetChanged`) fuer bessere Performance.
- Unit-Tests fuer Sortierlogik in `getTimersSnapshot()`.
- Instrumented Tests fuer Dialog-Validierung und Status-Transitions.
- Optionaler Export/Import von Timern als JSON-Datei.
