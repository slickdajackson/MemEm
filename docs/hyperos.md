# MemEm auf dem Xiaomi 15 Ultra (HyperOS 3, Android 16)

Die Menüpfade stammen aus den ChatLens-Notizen (INSTALL-XIAOMI.md, RECHERCHE.md) und aus Community-Berichten. Sie sind an diesem Gerät nicht neu geprüft. Bezeichnungen können abweichen.

## Sideload

Debug-APK installieren. HyperOS kann eine Sicherheitsprüfung zeigen. Advanced Protection aus lassen, weil der Modus ab Android 17 Bedienungshilfen auf verifizierte Tools beschränken kann.

## Bedienungshilfe

1. In MemEm „Bedienungshilfe öffnen“ tippen und „MemEm Meme-Hilfe“ einschalten.
2. Kommt der Hinweis auf eingeschränkte Einstellungen: Dialog schließen, dann Einstellungen, Apps, MemEm, Drei-Punkte-Menü, „Eingeschränkte Einstellungen zulassen“, mit Fingerabdruck oder PIN bestätigen. Auf manchen Ständen steht der Eintrag unten auf der App-Infoseite.
3. Zurück zur Bedienungshilfe und den Schalter einschalten.
4. Blockiert ein Overlay den Schalter, vorübergehend von Gesten auf Schaltflächen wechseln.

Der Dienst sieht nur `com.whatsapp`. Er liest den offenen Chat und fügt ein Bild ein. Er tippt nicht auf Senden.

## Punkt, Akku, Autostart

HyperOS beendet Hintergrunddienste. Sonst verschwinden Punkt, Download und der Prozess `:llm`.

* Autostart für MemEm an (App-Info oder Sicherheits-App).
* Akku: „Keine Einschränkungen“. „App-Aktivität anhalten, wenn nicht verwendet“ aus.
* MemEm und WhatsApp in der Zuletzt-Ansicht sperren.
* „Über anderen Apps einblenden“ für den Punkt.
* „Pop-up-Fenster im Hintergrund“, sonst öffnet der Punkt die App auf HyperOS oft nicht zuverlässig.
* Schalter „Schwebender Punkt“ in MemEm. Entfernen über den Schalter oder die Benachrichtigung.

Nach einem Systemupdate die Bedienungshilfe erneut prüfen.

## Zwischenablage

Das PNG liegt als content-URI in der Zwischenablage. Die Tastatur versucht Einfügen. Auf diesem Weg meldet WhatsApp oft keinen Erfolg. Dann löst die Bedienungshilfe `ACTION_PASTE` im Feld `com.whatsapp:id/entry` aus. Klappt auch das nicht, erscheint der Hinweis, lange zu tippen und Einfügen zu wählen. Die Datei bleibt liegen, damit die URI gültig bleibt.

## Modelle

Download über die Benachrichtigung. Bricht HyperOS den Dienst ab, bleibt die Teildatei. „Fortsetzen“ oder der Download-Knopf lädt ab der vorhandenen Stelle weiter und prüft danach SHA-256. WLAN und Strom sind sinnvoll, Gemma 4 E2B ist etwa 2,6 GB.
