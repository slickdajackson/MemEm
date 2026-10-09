# Übernahme aus ChatLens 0.3.0

Quelle: [github.com/slickdajackson/ChatLense](https://github.com/slickdajackson/ChatLense).

Grundlage ist der mitgelieferte ChatLens-Quellstand. Übernommen wurde nur, was MemEm für WhatsApp auf dem Xiaomi 15 Ultra braucht. Der Agent, der Chats öffnet, scrollt, Profile baut oder Nachrichten sendet, ist nicht enthalten. MemEm sendet nie.

## Bedienungshilfe

`MememAccessibilityService` folgt `ChatAccessibilityService`: Ereignisse nur von `com.whatsapp`, `flagReportViewIds`, aktives Fenster nur lesen, wenn es WhatsApp ist. Liegt MemEm vorn (Tastatur oder Punkt), wird das WhatsApp-Fenster darunter gesucht. Fremde Apps liefern keinen Baum.

Feld-IDs kommen aus `assets/profiles/whatsapp.json` (`SelectorProfile` und `knownIds` aus ChatLens). Das Eingabefeld ist `com.whatsapp:id/entry`, sonst das unterste editierbare Feld unterhalb von 65 Prozent der Höhe. Nachrichtentexte sind `com.whatsapp:id/message_text`. Uhrzeiten, Datum, Verschlüsselungshinweis und Statuszeilen fallen weg, wie in `ChatParser`.

Einfügen: zuerst legt MemEm das PNG als content-URI in die Zwischenablage. Die Tastatur ruft Einfügen auf. Meldet das keinen Erfolg, führt die Bedienungshilfe `ACTION_FOCUS` und `ACTION_PASTE` auf dem Eingabefeld aus. Der Text im Feld wird vorher geleert (`ACTION_SET_TEXT`), wie `ReplyInserter`, und bei Fehlschlag im Overlay zurückgeschrieben. Der Senden-Knopf (`com.whatsapp:id/send`) wird nie angetippt. `ExperimentalSender` aus ChatLens wurde nicht übernommen.

Ist die Bedienungshilfe aktiv, gehen die letzten sichtbaren Nachrichten zusätzlich in die Suche und in den Gemma-Prompt. Der getippte Text bleibt die Nachricht. Der Kontext fliegt als Erstes aus dem Prompt, wenn das Tokenbudget eng wird.

## Schwebender Punkt

`OverlayService` übernimmt Fensterart und Flags aus ChatLens: `TYPE_APPLICATION_OVERLAY`, nicht fokussierbar, damit WhatsApp aktiv bleibt. Der Punkt ist der optionale zweite Einstieg neben der Tastatur. Antippen zeigt drei Vorschläge für den offenen Chat. Einfügen läuft über Zwischenablage und Bedienungshilfe, danach Teilen. Ein Vordergrunddienst vom Typ specialUse hält den Punkt, die Benachrichtigung kann ihn entfernen.

## Modelle und HyperOS

Der Download bleibt ein Vordergrunddienst mit Bereichsfortsetzung und SHA-256. Neu ist die Meldung, dass die Teildatei bleibt, plus die Aktion Fortsetzen, und `START_REDELIVER_INTENT`, weil HyperOS den Dienst beenden kann. Ab 0.1.2 laufen Gemma und Embedding nur auf der CPU. Die optionalen OpenCL-Einträge aus ChatLens sind nicht mehr im Manifest.

Die Einrichtungshinweise (eingeschränkte Einstellungen, Autostart, Akku ohne Einschränkung, Sperren in der Zuletzt-Ansicht, Overlay, Pop-up im Hintergrund, Zwischenablage, Advanced Protection) stehen in `docs/hyperos.md` und in den Einstellungen. Sie stammen aus `INSTALL-XIAOMI.md` und `RECHERCHE.md` und sind am Xiaomi 15 Ultra weiterhin nicht von diesem Lauf geprüft.
