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
- App-Sprachen: Deutsch, Englisch, Französisch, Spanisch und Portugiesisch (Portugal); die Sprache wird beim Erststart gewählt und bleibt in den AppCompat-Locale-Einstellungen gespeichert.

## 3. Architektur-Ueberblick

Die App besteht aus einer einfachen 3-Schichten-Struktur:

1. UI-Schicht
- SplashActivity startet MainActivity.
- MainActivity verwaltet Dialoge, Eingaben, zwei Tabs und Sortierauswahl.
- TimerAdapter bindet gespeicherte Vorlagen und einzelne Lauf-Instanzen auf die Timer-Card-UI.

2. Service-Schicht
- TimerService verwaltet Vorlagen und unabhaengige Lauf-Instanzen zentral (in-memory), tickt sekundenweise, setzt Notifications und TTS.

3. Persistenz-Schicht
- TimerPersistence speichert Vorlagen und Lauf-Instanzen getrennt als JSON in SharedPreferences und migriert das vorherige JSON-Arrayformat beim Laden.

## 4. Klassen und Verantwortungen

### SplashActivity
Datei: app/src/main/java/com/example/multitimer/SplashActivity.java

- Aktiviert Android SplashScreen API.
- Leitet direkt auf MainActivity weiter.

### MainActivity
Datei: app/src/main/java/com/example/multitimer/MainActivity.java

- UI-Einstiegspunkt der App.
- Initialisiert Tabs fuer laufende Timer und gespeicherte Vorlagen sowie RecyclerView und Adapter.
- Bietet Sortierung laufender Timer nach Name, Startzeit oder Restdauer und gespeicherter Vorlagen nach Nutzung, Erstellungszeit oder Name.
- Das Zahnradmenue enthaelt Info und App-Einstellungen.
- Die Einstellung "Alarmton bei Stummschaltung erzwingen" ist standardmaessig aktiv; deaktiviert laesst die App die System-Alarmlautstaerke unveraendert.
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
  - Erfasst Name und Timerart (Einzeltimer, Intervalltimer oder Timergruppe).
  - Zeigt je nach Timerart Dauer/Ansagetext, Wiederholungszahl und die Wahl automatischer oder bestaetigter Intervallfortsetzung beziehungsweise editierbare Gruppenschritte.
  - Fuellt den Abschlusstext aus dem Timernamen mit "Fertig" vor; eigene Anpassungen bleiben erhalten.
  - Legt eine wiederverwendbare Vorlage ueber TimerService an.
- `showEditTimerDialog(SavedTimer timer)`:
  - Oeffnet denselben typspezifischen Editor mit den gespeicherten Werten.
- `refreshTimers()`:
  - Holt je nach aktivem Tab einen Vorlagen- oder Lauf-Snapshot vom TimerService.
  - Aktualisiert Adapter und Empty-State.
- `setDialogUiBlocked(boolean blocked)`:
  - Aktiviert/Deaktiviert Scrim und Interaktionen im Hintergrund.

### TimerAdapter
Datei: app/src/main/java/com/example/multitimer/TimerAdapter.java

- Bindet Timerzustand auf Card-Elemente.
- Definiert die Action-Callbacks ueber `OnTimerActionListener`.
- Setzt Statuschip-Farbe, Text und Blink-Animation.

Im Vorlagen-Tab zeigt die Karte Name und Dauer. Die Nutzungshaeufigkeit wird nicht in der UI angezeigt, bleibt aber als Sortiermetadatum erhalten. Der Start-Button erzeugt eine neue Lauf-Instanz; Name antippen bearbeitet die Vorlage und Loeschen entfernt nur die Vorlage. Aktive Laeufe behalten ihre kopierte Konfiguration.

Im Lauf-Tab zeigt jede Karte genau eine gestartete Timer-Instanz. Laufende Instanzen lassen sich abbrechen; unbestaetigte Abschluesse bleiben dort bis Notification-Bestaetigung oder Entfernen sichtbar.

Jede Karte zeigt links neben dem Namen ein Piktogramm fuer Einzeltimer, Intervalltimer oder Timergruppe.

Sortierung:
- Lauf-Tab: alphabetisch, zuletzt gestartet, Restdauer oder Timerart.
- Vorlagen-Tab: am meisten genutzt, zuletzt angelegt, alphabetisch oder Timerart.
- Erneute Auswahl des aktiven Kriteriums wechselt je Tab zwischen auf- und absteigender Richtung; das Sortiersymbol zeigt die aktuelle Richtung.
- Das Zahnrad in der Kopfzeile oeffnet ein erweiterbares Kontextmenue mit dem Infoeintrag.

UI-Verhalten pro Laufzustand:

1. Running
- Status: "Laeuft" (gelb + blinkend)
- Action-Button: Abbrechen
- Delete: inaktiv
- Mute: inaktiv

2. Completed
- Status: "Fertig" (gruen)
- Action-Button: erneuter Start aus der Vorlage
- Delete: entfernt den abgeschlossenen Lauf, nicht die Vorlage
- Mute: aktiv, solange Notification nicht dismissed wurde
- Blinkt, solange Notification aktiv ist

### ManagedTimer
Datei: app/src/main/java/com/example/multitimer/ManagedTimer.java

Datenmodell einer einzelnen Lauf-Instanz mit Kernfeldern:

- `id`
- `sourceSavedTimerId`
- `name`
- `durationMillis`
- `startedAtMillis`
- `completionText` (optional; leer bedeutet Standardtext "Fertig")
- `endTimeMillis`
- `completed`
- `notificationDismissed`

Kernmethoden:

- `isRunning(now)`
- `shouldComplete(now)`
- `markCompleted()`
- `markNotificationDismissed()`

### SavedTimer

Datei: app/src/main/java/com/example/multitimer/SavedTimer.java

Enthaelt die wiederverwendbare Timerkonfiguration und Sortiermetadaten:

- `id`, `name`, `durationMillis`
- Alarmintervall, Alarmlautstaerke und optionaler `completionText`
- `createdAtMillis`, `lastStartedAtMillis`, `usageCount`
- `timerType`, `repeatCount` und geordnete `steps`
- `waitForIntervalConfirmation` fuer Intervalltimer

### TimerType und TimerStep

- `TimerType` unterscheidet `STANDARD`, `INTERVAL` und `GROUP`.
- `TimerStep` speichert Gruppenname, Dauer und individuellen Abschlusstext.
- Timergruppen besitzen zusaetzlich einen eigenen finalen Abschlusstext, der nach Bestaetigung des letzten Schritts gesprochen wird.
- Bei Intervallen bedeutet `repeatCount = 0` unbegrenzte Wiederholung; jeder endliche Wert ist die Gesamtzahl der Intervalle (z. B. 3 bedeutet genau 3 Durchlaeufe).
- Intervalltimer starten standardmaessig automatisch weiter und sprechen pro Intervallende genau einmal. Ist `waitForIntervalConfirmation` aktiviert, wiederholt sich die TTS-Ansage im Alarmabstand bis zur Bestaetigung.
- Gruppen zeigen bei jedem abgelaufenen Schritt einen nicht wegwischbaren Bestaetigungsdialog; erst danach startet der naechste Schritt. Der letzte Schritt wartet auf Bestaetigung zum Beenden.
- Die TTS-Ansage einer offenen Gruppen- oder bestaetigungspflichtigen Intervallphase wird im konfigurierten Alarmabstand wiederholt, bis bestaetigt oder der Lauf beendet wird. Die positive Notification-Aktion bestaetigt direkt; ein Swipe oeffnet separat eine Abbruchrueckfrage.
- Fortschritt und ausstehende Ansagen werden persistiert und nach Prozessneustart fortgesetzt.

### TimerService
Datei: app/src/main/java/com/example/multitimer/TimerService.java

Zentrale Laufzeitlogik fuer gespeicherte Vorlagen und unabhaengige Timerlaeufe.

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
- `enqueueStartSavedTimer(...)`
- `enqueueRestartTimer(...)`
- `enqueueDeleteSavedTimer(...)`
- `enqueueCancelTimer(...)`
- `enqueueReplaceTimer(...)`
- `enqueueDismissNotification(...)`
- `enqueueDismissTimer(...)`
- `getTimersSnapshot()`
- `getSavedTimersSnapshot()`
- `ensureTimersLoaded(...)`
- `ensureServiceRunningForActiveTimers(...)`

`getTimersSnapshot()` enthaelt laufende Instanzen, Gruppen-/Intervall-Uebergaenge und noch nicht bestaetigte Abschluesse. `getSavedTimersSnapshot()` enthaelt ausschliesslich Vorlagen. Jeder Start erstellt eine neue Lauf-ID und erhoeht die Nutzung der Vorlage.

#### Foreground-Service und Android 14+/16

- Service ist als Foreground-Service mit `foregroundServiceType="dataSync"` deklariert.
- Start erfolgt typisiert mit `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
- Relevante Permissions stehen im Manifest.

#### Notifications

- Running-Channel: niedrige Prioritaet.
- Finished-Channel: still, ohne Vibration/Sound.
- Jede Timer-ID entspricht einer eigenen Notification-ID.
- Completion-Sound verwendet `STREAM_ALARM`; `AppSettings` steuert, ob der Timer die System-Alarmlautstaerke vor dem Alarm auf seine konfigurierte Timerlautstaerke setzt.
- Alle aktiven Timerlaeufe werden einzeln angezeigt und koennen weggewischt werden; der Swipe oeffnet vor Abbruch/Entfernen eine Bestaetigungs-Activity.
- Bestaetigungspflichtige Gruppen- und Intervallphasen erhalten eine High-Priority-Alarm-Notification mit Full-Screen-Intent und Aktionen.
- Full-Screen-Intents benoetigen `USE_FULL_SCREEN_INTENT`; ist die Systemfreigabe nicht vorhanden, bleibt die Notification mit Heads-up-/Aktions-Fallback sichtbar.
- Normale abgeschlossene Timer bieten weiterhin eine Dismiss-Action.

#### TTS-Logik

- Beim Abschluss wird ausschliesslich der Inhalt des pro Timer gespeicherten Abschlusstexts gesprochen.
- Beim Erstellen wird `<Timername> Fertig` vorbelegt; bei leerem oder fehlendem Text gilt weiterhin "Fertig" als Fallback.
- Wiederholung im konfigurierten Alarmabstand, solange completed und nicht dismissed.
- TTS verwendet die aktive App-Locale und faellt bei fehlenden Sprachdaten auf die System-Locale zurueck.

### TimerPersistence
Datei: app/src/main/java/com/example/multitimer/TimerPersistence.java

- Speichert Vorlagen und aktive Lauf-Instanzen als getrennte JSON-Arrays in `SharedPreferences` (`multitimer_prefs`).
- Migriert beim ersten Laden bestehende JSON-Array-Eintraege zu Vorlagen; zuvor aktive, noch nicht quittierte Timer werden als Lauf-Instanzen wiederhergestellt.
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
- Locale: aktive App-/System-Locale

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

1. Benutzer legt in MainActivity eine Timer-Vorlage an.
2. MainActivity sendet Intent an TimerService (`enqueueCreateTimer`); die Vorlage bleibt gespeichert.
3. Jeder Start aus dem Vorlagen-Tab erzeugt eine neue `ManagedTimer`-Instanz mit eigener ID und Notification.
4. Intervalltimer starten nach jeder Ansage standardmaessig automatisch dieselbe Dauer erneut oder warten optional auf Bestaetigung; Gruppen warten mit wiederholter TTS-Ansage auf Bestaetigung, bevor der naechste `TimerStep` startet.
5. Der Tick-Loop aktualisiert Restzeit und Completion der Lauf-Instanzen.
6. Jede laufende Instanz hat eine eigene Notification.
7. Bei Ablauf:
- Status wird completed.
- Notification bleibt sichtbar (still).
- TTS-Ansage startet und wiederholt sich.
8. Benutzer kann:
- Eine weitere unabhaengige Instanz aus der Vorlage starten
- Notification stummschalten (dismiss)
- Den Lauf entfernen oder abbrechen, ohne die Vorlage zu loeschen

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
- Unit-Tests fuer endliche/unendliche Intervallphasen und Gruppenschritt-Fortschritt.
- Instrumented Tests fuer Dialog-Validierung und Status-Transitions.
- Optionaler Export/Import von Timern als JSON-Datei.
