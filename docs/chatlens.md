# Adopted from ChatLens 0.3.0

Source: [github.com/slickdajackson/ChatLense](https://github.com/slickdajackson/ChatLense).

The base is the ChatLens source that shipped with the prototype. Only what MemEm needs for WhatsApp on the Xiaomi 15 Ultra was adopted. The agent that opens chats, scrolls, builds profiles, or sends messages is not included. MemEm never sends.

## Accessibility

`MememAccessibilityService` follows `ChatAccessibilityService`: events only from `com.whatsapp`, `flagReportViewIds`, and the active window is read only when it is WhatsApp. If MemEm is in front (keyboard or dot), the WhatsApp window underneath is looked up. Other apps yield no tree.

Field ids come from `assets/profiles/whatsapp.json` (`SelectorProfile` and `knownIds` from ChatLens). The input field is `com.whatsapp:id/entry`, otherwise the lowest editable field below 65 percent of the height. Message texts are `com.whatsapp:id/message_text`. Times, dates, the encryption notice, and status lines are dropped, as in `ChatParser`.

Paste: MemEm first puts the PNG on the clipboard as a content URI. The keyboard calls paste. If that reports no success, accessibility runs `ACTION_FOCUS` and `ACTION_PASTE` on the input field. The field text is cleared first (`ACTION_SET_TEXT`), as in `ReplyInserter`, and written back in the overlay if that fails. The send button (`com.whatsapp:id/send`) is never tapped. `ExperimentalSender` from ChatLens was not adopted.

While accessibility is on, the last visible messages also go into search and the Gemma prompt. The typed text stays the message. Context is the first thing dropped from the prompt when the token budget is tight.

## Floating dot

`OverlayService` takes the window type and flags from ChatLens: `TYPE_APPLICATION_OVERLAY`, not focusable, so WhatsApp stays active. The dot is the optional second entry next to the keyboard. A tap shows three suggestions for the open chat. Insert goes through the clipboard and accessibility, then share. A specialUse foreground service keeps the dot. The notification can remove it.

## Models and HyperOS

The download stays a foreground service with range resume and SHA-256. New is the message that the partial file remains, plus the resume action, and `START_REDELIVER_INTENT`, because HyperOS can kill the service. From 0.1.2, Gemma and the embedding run only on the CPU. The optional OpenCL entries from ChatLens are no longer in the manifest.

The setup notes (restricted settings, autostart, battery with no restriction, locking in recents, overlay, background pop-up, clipboard, Advanced Protection) are in `docs/hyperos.md` and in settings. They come from `INSTALL-XIAOMI.md` and `RECHERCHE.md` in ChatLens and have still not been rechecked on a Xiaomi 15 Ultra by this run.
