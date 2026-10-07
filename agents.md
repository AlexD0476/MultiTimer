# MultiTimer Agents

## Zweck

Diese Datei beschreibt Projektkontext und Arbeitsregeln fuer Agenten und Automatisierungen, die an MultiTimer weiterarbeiten. Fuer detaillierte Methoden- und UI-Beschreibungen ist `CODE_DOKUMENTATION.md` die ergaenzende Referenz.

## Projektueberblick

- MultiTimer ist eine eigenstaendige Android-App zum parallelen Erstellen, Starten und Verwalten mehrerer Timer.
- Die Timeruebersicht hat zwei Tabs: "Laufende Timer" fuer unabhaengige Timerlaeufe und "Gespeicherte Timer" fuer wiederverwendbare Vorlagen.
- Ein Start erzeugt immer eine neue Lauf-Instanz. Die Vorlage bleibt gespeichert und kann mehrfach parallel gestartet werden.
- Es gibt Einzel-, Intervall- und Gruppentimer. Intervalltimer wiederholen dieselbe Dauer; `repeatCount = 0` bedeutet unbegrenzt, endliche Werte sind die Gesamtzahl der Intervalle. Die Fortsetzung ist standardmaessig automatisch und kann auf Bestaetigung umgestellt werden. Timergruppen laufen geordnet Schritt fuer Schritt, jeder Schritt hat eigene Dauer und eigenen Ansagetext.
- Laufende Timer sind alphabetisch, nach Startzeit oder Restdauer sortierbar; Vorlagen nach Nutzungszahl, Anlagedatum oder alphabetisch.
- Jede Timerkarte zeigt die Timerart als Piktogramm; beide Tabs koennen zusaetzlich nach Timerart sortiert werden.
- Abgeschlossene Timer erhalten je eine eigene Notification. Der Abschluss kann per TextToSpeech angesagt werden; Wiederholungsintervall und Alarm-Lautstaerke gehoeren zur Timerkonfiguration.
- Sprache: Java 21; Paket und Namespace: `com.example.multitimer`.
- Android: `minSdk 26`, `compileSdk 36`, `targetSdk 36`.
- Build: Gradle Wrapper (Gradle 8.7), Android Gradle Plugin 8.5.2.
- UI: Android Views/XML, RecyclerView, AppCompat und Material Components; bestehendes Dark-Theme und visuelle Sprache erhalten.
- Sprache: Locale-Auswahl beim Erststart; unterstützte Übersetzungen derzeit Deutsch, Englisch, Französisch, Spanisch und Portugiesisch (Portugal). Neue sichtbare Texte in alle vorhandenen Locale-`strings.xml` übertragen.

## Architektur

- `SplashActivity` zeigt den Android SplashScreen und oeffnet `MainActivity`.
- `MainActivity` verwaltet die Timer-Eingabe und -Aktionen, Dialoge, Berechtigungsabfrage, RecyclerView und UI-Aktualisierung.
- `TimerAdapter` bildet gespeicherte Vorlagen und einzelne Timerlaeufe auf Timerkarten ab.
- `SavedTimer` enthaelt die wiederverwendbare Timerkonfiguration sowie Erstellungszeit, letzte Startzeit und Nutzungshaeufigkeit.
- `TimerType` kennzeichnet Einzel-, Intervall- und Gruppenvorlagen; `TimerStep` speichert einen Gruppenabschnitt mit Dauer und Ansagetext.
- `ManagedTimer` repraesentiert genau einen Timerlauf mit eigener ID, Vorlagenreferenz, Start-/Endzeit und Notification-/Ansagestatus.
- `TimerService` ist die zentrale Laufzeitlogik und Quelle fuer Vorlagen- und Lauf-Snapshots. Er verarbeitet Aktionen, tickt Lauf-Instanzen, persistiert Aenderungen, aktualisiert Notifications und koordiniert Vibrations- und TTS-Abschluesse.
- `TimerPersistence` speichert Vorlagen und Lauf-Instanzen getrennt als JSON in privaten `SharedPreferences` und migriert das alte Timerlistenformat beim Laden.
- `BootReceiver` laedt nach Boot, Locked Boot oder App-Update den Zustand und reaktiviert den Service, falls Timer laufen.
- `TimerFormatter` enthaelt die gemeinsame Formatierung der Restzeit.

UI-Aktionen sollen die `TimerService.enqueue...`-APIs verwenden, statt Timerzustand in Activity oder Adapter direkt zu veraendern. Vorlagen duerfen nicht durch das Starten oder Beenden einer Lauf-Instanz entfernt oder veraendert werden; jeder Lauf braucht eine eigene stabile Notification-ID.

## Arbeitsregeln

- Bestehendes Dark-Theme, XML-View-Ansatz und vorhandene UI-Komponenten beibehalten; Aenderungen gezielt und im Stil der App umsetzen.
- Timerzustand zentral im `TimerService` halten. Neue UI-Aktionen ueber die Service-API fuehren und Persistenz bei Zustandsaenderungen beruecksichtigen.
- Timer-Notifications niemals zu einer Sammelmeldung zusammenfassen: jeder Timer muss eine stabile, eigene Notification-ID und einzeln bedienbare Notification behalten.
- Aenderungen an Timerlogik gegen mehrere gleichzeitige Starts derselben Vorlage sowie gegen Start, Abbruch, Neustart, Ablauf, Entfernen und Wiederherstellung nach Prozess-/Geraeteneustart pruefen.
- Zeitablauf anhand gespeicherter Endzeit berechnen, nicht davon ausgehen, dass sekundenweise Handler-Ticks waehrend Hintergrundbetrieb oder Prozessunterbrechungen exakt ausgefuehrt werden.
- TTS und Abschlussalarme muessen den Zustand einzelner Timer respektieren: bestaetigte Notifications duerfen keine weiteren Ansagen ausloesen; konkurrierende Abschluesse duerfen einander nicht verlieren.
- Notification-Kanaele und Android-13+-Notification-Berechtigung beachten. Eine verweigerte Notification-Berechtigung darf nicht zu einem Absturz fuehren.
- Der Service ist im Manifest als `dataSync`-Foreground-Service deklariert. Auf Android 14+/16 den typisierten `startForeground`-Pfad ueber `ServiceCompat` mit `FOREGROUND_SERVICE_TYPE_DATA_SYNC` beibehalten. Kein untypisierter oder Typ-0-Fallback auf API 34+.
- Neue Android-APIs muessen mit Java 21, `minSdk 26` und `targetSdk 36` funktionieren; benoetigte Berechtigungen und Manifestdeklarationen mitpruefen.
- Keine Bibliothek oder Architektur einfuehren, wenn die bestehende AndroidX-/Java-Struktur die Aufgabe bereits abdeckt.

## Build und Test

- Projektwurzel: /home/alex/MultiTimer
- Debug-Build: `./gradlew --no-daemon assembleDebug --console=plain`
- Gradle verwendet laut `gradle.properties` `/usr/lib/jvm/java-21-openjdk-amd64`; bei Build-Problemen zuerst `org.gradle.java.home` und die lokale JDK-21-Installation pruefen.
- Bei Timerlogik-Aenderungen parallel laufende Timer sowie Boot-/App-Update-Wiederherstellung auf Emulator oder Geraet mitpruefen; den Debug-Build nach Aenderungen mindestens ausfuehren.
- Relevante Projekt- und Ablaufdetails stehen in `CODE_DOKUMENTATION.md`; bei Abweichungen gilt der aktuelle Quellcode als massgeblich.