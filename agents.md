# MultiTimer Agents

## Zweck

Diese Datei beschreibt die Arbeitsregeln fuer Agenten oder Automatisierungen, die an MultiTimer weiterarbeiten.

## Projektueberblick

- Plattform: Eigenstaendige Android-App
- Sprache: Java 21
- Build: Gradle 8.7, Android Gradle Plugin 8.5.2
- Paketname: com.example.multitimer

## Architektur

- MainActivity verwaltet Eingabe, Presets und die Listenansicht.
- TimerService fuehrt mehrere Timer parallel im Hintergrund aus.
- Jeder Timer erzeugt eine eigene Notification.
- Beim Ablauf liest TextToSpeech zuerst den Timernamen und danach "Fertig" vor.

## Arbeitsregeln

- Bestehendes Dark-Theme beibehalten und nur gezielt erweitern.
- Timer-Notifications niemals zu einer einzigen Sammelmeldung zusammenfassen; jeder Timer braucht seine eigene Anzeige.
- Aenderungen an der Timerlogik immer gegen parallele Timer pruefen.
- Wenn neue Android-APIs verwendet werden, Java-21-Kompatibilitaet und minSdk 26 beachten.

## Build und Test

- Projektwurzel: /home/alex/MultiTimer
- Debug-Build: ./gradlew assembleDebug
- Bei Build-Problemen zuerst org.gradle.java.home und das lokale JDK 21 pruefen.