# Hinweise zu Bestandteilen Dritter

Die Lizenz in `LICENSE` gilt für den eigenen MemEm-Code (App, Engine, `rewrite-cli`, Build-Skripte, Texte in diesem Repo, soweit unten nichts anderes steht). Sie gilt nicht für Modelle, Meme-Bilder, Datensätze und Schriften. Die sind hier einzeln genannt.

Geprüft wurden die mitgelieferten Dateien, die POMs der direkten Gradle-Abhängigkeiten und die Vermerke im Prototyp `reference/memechat/README.md`. Eine Copyleft-Lizenz (GPL und vergleichbar), die den eigenen Code erfassen würde, ist dabei nicht aufgetaucht. Apache-2.0-Bibliotheken und OFL-Schriften lassen sich mit MIT kombinieren, wenn ihre eigenen Hinweise bleiben. Deshalb ist MIT die Lizenz des eigenen Codes.

## Meme-Vorlagen und Textfelder

| Bestandteil | Lizenz | Link |
| --- | --- | --- |
| memegen, Vorlagenliste und Textfeld-Konfiguration (`config.yml`: Anker, Größe, Winkel, Ausrichtung, Schrift, Farbe). Darauf baut die Ablage in `catalog.json` und `MemeLayout` auf. | MIT | https://github.com/jacebrowning/memegen |
| 209 Vorlagenbilder unter `app/src/main/assets/images/*.webp` | Rechte Dritter, nicht von der MIT-Lizenz von memegen umfasst | dieselben Vorlagen wie bei memegen |

Die Bilder können Marken, Urheberrechte oder Persönlichkeitsrechte Dritter betreffen. MemEm liefert sie mit, damit die Tastatur offline rendern kann. Wer das Repo oder die APK weitergibt, muss diese Rechte selbst klären. Die MIT-Lizenz dieses Repos überträgt sie nicht.

Impact ist nicht enthalten. Das memegen-Schriftbild `thick` wird mit Anton gezeichnet.

## Suchtexte und Beispiele

| Bestandteil | Lizenz | Link | Wo |
| --- | --- | --- | --- |
| ImgFlip575K, Bildunterschriften | im Upstream keine Lizenz angegeben | https://github.com/schesa/ImgFlip575K_Dataset | Suchindex `app/src/main/assets/index/`, `data/payloads-6491.json`, `reference/memechat/data/captions/` |
| LoC-meme-generator, Bildunterschriften | ODC-BY 1.0 (Namensnennung) | https://huggingface.co/datasets/pszemraj/LoC-meme-generator | dieselben Caption-Dateien, soweit die Zeile aus diesem Satz stammt |
| Open Data Commons Attribution License | ODC-BY 1.0 | https://opendatacommons.org/licenses/by/1-0/ | gilt für die LoC-Zeilen |
| `caption-examples.json` | eigene kurze Beispielsprüche, MIT mit dem übrigen Code | in diesem Repo | Prompt-Beispiele, nicht der Rohdatensatz |

Die Index- und Payload-Dateien sind öffentliche Meme-Zeilen aus diesen Sammlungen, keine privaten Chats. ImgFlip575K nennt keine Lizenz. Diese Zeilen bleiben unter dem, was der Upstream erlaubt, und werden hier nicht auf MIT umgestellt.

## Modelle

Gemma-Gewichte liegen nicht in diesem Repo. Die App und `rewrite-cli` erwarten lokale Dateien, die der Nutzer selbst lädt.

| Modell | Bedingungen | Link |
| --- | --- | --- |
| Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) | Gemma Terms of Use, Google | https://ai.google.dev/gemma/terms |
| EmbeddingGemma 2 Text 270M (`embeddinggemma-2-text-270m.litertlm`) | dieselben Gemma Terms of Use | https://ai.google.dev/gemma/docs/embeddinggemma |

Überblick zu Gemma: https://ai.google.dev/gemma/docs/core

## Laufzeit und Bibliotheken

| Bestandteil | Lizenz | Link |
| --- | --- | --- |
| LiteRT-LM 0.18.0, Android (`litertlm-android`) und JVM (`litertlm-jvm`, inklusive `liblitertlm_jni.so` für linux-x86_64) | Apache-2.0, POM „The Apache Software License, Version 2.0“ | https://github.com/google-ai-edge/LiteRT-LM |
| Kotlin, kotlinx-coroutines, AndroidX (Core, AppCompat, Activity, Compose BOM 2026.03.01, Material 3, Test) | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| Gson 2.14.0, transitiv über LiteRT-LM | Apache-2.0 | https://github.com/google/gson |
| org.json (`tools/rewrite-cli` und Engine-Tests; auf Android die Plattformklasse) | JSON-Lizenz | https://github.com/stleary/JSON-java/blob/master/LICENSE |
| JUnit 4.13.2, nur Tests | EPL-2.0 | https://junit.org/junit4/license.html |
| Robolectric 4.16.1, nur Tests, damit entstehen die Bilder unter `docs/images` | MIT | https://github.com/robolectric/robolectric |

## Schriften

Alle fünf Dateien liegen unter `app/src/main/assets/fonts/`. Der Text der SIL Open Font License 1.1 liegt daneben in `OFL.txt`. OFL-Schriften bleiben unter der OFL, auch wenn die App MIT ist.

| Datei | Lizenz | Herkunft |
| --- | --- | --- |
| `Anton-Regular.ttf` | OFL 1.1 | Copyright 2020 The Anton Project Authors, Vernon Adams. https://github.com/googlefonts/AntonFont |
| `Kalam-Regular.ttf` | OFL 1.1 | Copyright 2014 Indian Type Foundry (Lipi Raval, Jonny Pinhorn). https://github.com/itfoundry/kalam |
| `TitilliumWeb-Black.ttf`, `TitilliumWeb-SemiBold.ttf` | OFL 1.1 | Copyright 2009 bis 2011 Accademia di Belle Arti di Urbino. https://fonts.google.com/specimen/Titillium+Web |
| `NotoSans-Bold.ttf` | Apache-2.0 | Copyright 2012 Google Inc., Monotype. https://github.com/notofonts/latin |

## Eigene Vorarbeit

[ChatLens](https://github.com/slickdajackson/ChatLense) ist die frühere eigene Codebasis. Übernommen sind Bedienungshilfe, Einfügen, der schwebende Punkt und die HyperOS-Hinweise, beschrieben in `docs/chatlens-uebernahme.md`. Der Agent, der Chats öffnet oder Nachrichten sendet, ist nicht enthalten.

## Nur im Prototyp, nicht in der App

`reference/memechat/data/e2e/report-20261008-233857.json` enthält erfundene Testchats aus `reference/memechat/eval/fake_chats.json` und kurze Auszüge aus DialogSum (deutsch und englisch). DialogSum steht unter CC BY-NC-SA 4.0 und ist nicht Teil der App, nicht Teil des Suchindex und nicht von der MIT-Lizenz erfasst. Die Auszüge bleiben unter CC BY-NC-SA 4.0.

https://creativecommons.org/licenses/by-nc-sa/4.0/

Im Repo liegen keine privaten Chats, keine Zugangsdaten und keine Gemma-Gewichte.
