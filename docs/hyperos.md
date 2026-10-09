# MemEm on the Xiaomi 15 Ultra (HyperOS 3, Android 16)

Menu paths come from the ChatLens notes (`INSTALL-XIAOMI.md`, `RECHERCHE.md` in [ChatLens](https://github.com/slickdajackson/ChatLense)) and from community reports. They have not been rechecked on this device. Labels can differ. The app UI itself is German, so the quotes below are the strings on screen.

## Sideload

Install the debug APK. HyperOS can show a security check. Leave Advanced Protection off, because from Android 17 that mode can limit accessibility services to verified tools.

## Accessibility

1. In MemEm tap "Bedienungshilfe öffnen" and turn on "MemEm Meme-Hilfe".
2. If the restricted-settings warning appears: close the dialog, then Settings, Apps, MemEm, three-dot menu, "Eingeschränkte Einstellungen zulassen", confirm with fingerprint or PIN. On some builds the entry is at the bottom of the app info page.
3. Go back to accessibility and turn the switch on.
4. If an overlay blocks the switch, switch from gestures to buttons for a moment.

The service sees only `com.whatsapp`. It reads the open chat and inserts an image. It does not tap send.

## Dot, battery, autostart

HyperOS stops background services. Otherwise the dot, the download, and the `:llm` process disappear.

* Turn autostart on for MemEm (app info or the security app).
* Battery: "Keine Einschränkungen". Turn off "App-Aktivität anhalten, wenn nicht verwendet".
* Lock MemEm and WhatsApp in the recents view.
* "Über anderen Apps einblenden" for the dot.
* "Pop-up-Fenster im Hintergrund", or the dot often fails to open the app on HyperOS.
* The "Schwebender Punkt" switch in MemEm. Remove it with the switch or the notification.

After a system update, check accessibility again.

## Clipboard

The PNG sits in the clipboard as a content URI. The keyboard tries paste. WhatsApp often reports no success on that path. The accessibility service then runs `ACTION_PASTE` on `com.whatsapp:id/entry`. If that fails too, the hint is to long-press and choose paste. The file stays so the URI remains valid.

## Models

Download through the notification. If HyperOS kills the service, the partial file stays. "Fortsetzen" or the download button continues from the bytes already there and then checks SHA-256. Wi-Fi and power are worth it. Gemma 4 E2B is about 2.6 GB.
