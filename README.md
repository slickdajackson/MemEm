# MemEm 0.1.1

Private Android-Tastatur (Sideload), die aus dem getippten Text drei Memes vorschlägt und eines davon in das Eingabefeld legt. MemEm sendet nie selbst. Optional liest eine Bedienungshilfe den offenen WhatsApp-Chat und ein schwebender Punkt startet denselben Vorschlag.

Paket `app.memem`, minSdk 29, targetSdk 36, nur `arm64-v8a`.

## Ablauf

1. Tastatur in den Systemeinstellungen aktivieren (die App öffnet den passenden Bildschirm).
2. In WhatsApp mit MemEm tippen, oder mit einer anderen Tastatur tippen und dann zu MemEm wechseln. Der Text wird über `getExtractedText` und `getTextBeforeCursor` gelesen.
3. Auf **Meme** tippen. Es erscheinen immer drei Vorschaubilder.
4. Ein Vorschlag löscht den Text und fügt das PNG ein. Standardweg ist die Zwischenablage (`content://` über den FileProvider, danach Einfügen). Meldet die Tastatur keinen Erfolg und ist die Bedienungshilfe an, folgt `ACTION_PASTE` im WhatsApp-Feld. Danach Commit-Content, ganz zuletzt Teilen an WhatsApp.
5. Mit aktiver Bedienungshilfe gehen die letzten sichtbaren Nachrichten in die Suche und in den Prompt. Der getippte Text bleibt die Nachricht.
6. Welcher Weg gemeldet hat, steht in `files/debug/pipeline.jsonl` auf dem Gerät. Dort stehen auch die Zeiten für Einbetten, Suche, Gemma und Rendern.

Einrichtung auf HyperOS: `docs/hyperos.md`. Was aus ChatLens stammt: `docs/chatlens-uebernahme.md`.

## Modelle

Beim ersten Start lädt ein Vordergrunddienst:

* EmbeddingGemma 2 Text 270M (`embeddinggemma-2-text-270m.litertlm`)
* Gemma 4 E2B CPU (`gemma-4-E2B-it.litertlm`, 2,59 GB)
* optional die GPU-Datei

SHA-256 wird geprüft, ein abgebrochener Download wird fortgesetzt (Teildatei und Knopf Fortsetzen). Auf HyperOS die Akku-Einschränkung für MemEm aufheben, sonst beendet das System den Modell-Download, den Punkt und den Prozess `:llm`.

Gemma läuft in einem eigenen Prozess `:llm`. Ein nativer Abbruch beendet nur diesen Prozess. Die Tastatur startet ihn neu und versucht die Antwort einmal ohne JSON-Schema erneut. Das Schema enthält kein `minItems`/`maxItems`.

## Suche

Der mitgelieferte Index ist ein Vollscan über 6.491 Vektoren (float16, 768 Dimensionen), fusioniert wie im Prototyp (gewichtetes RRF, k=60, Gewichte Text 1, Bild 0,5, Bild+Text 1). Vorlagen mit mehr als vier Textfeldern fallen weg.

`tools/build_index.py` bettet die Punkte mit LiteRT-LM und EmbeddingGemma 2 Text 270M neu ein (Präfix `task: search result | text: `, Anfrage `task: search query | text: `). Solange das Einbettungsmodell auf dem Gerät fehlt, sucht die Tastatur mit dem Hash-Index `vectors-hash.f16`.

Bild- und Bild-Text-Punkte liegen in diesem Build im selben Textraum (Bedeutung bzw. Caption), nicht im 440M-Sichtmodell. Das steht in `docs/search-eval.json`.

## Bauen

```bash
python3 tools/build_assets.py
python3 tools/build_index.py /pfad/embeddinggemma-2-text-270m.litertlm
./gradlew :engine:test :app:assembleDebug
```

Der Prototyp liegt unter `reference/memechat/`. Impact.ttf wird nicht ausgeliefert. Ersatz ist Anton (OFL), dazu Titillium Web Black, Kalam und Noto Sans Bold.

## ReadEm

Das private Repo `slickdajackson/ReadEm` (Zweig `cursor/stimme-parakeet-086-dbc6`) war von diesem Lauf aus nicht lesbar. Die LiteRT-LM-Anbindung (Prozess `:llm`, Neustart nach Abbruch, Schema ohne Mindestanzahl) ist anhand von LiteRT-LM 0.18.0 neu geschrieben.
