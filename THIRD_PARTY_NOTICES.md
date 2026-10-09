# Third-party notices

The license in `LICENSE` covers MemEm's own code (app, engine, `rewrite-cli`, build scripts, and the text in this repo, except where this file says otherwise). It does not cover models, meme images, datasets, or fonts. Those are listed here.

The shipped files, the POMs of the direct Gradle dependencies, and the notes in `reference/memechat/README.md` were checked. No copyleft license (GPL or similar) showed up that would cover the own code. Apache-2.0 libraries and OFL fonts can sit next to MIT when their own notices stay. MIT is therefore the license for the own code.

## Meme templates and text fields

| Piece | License | Link |
| --- | --- | --- |
| memegen, template list and text-field config (`config.yml`: anchor, size, angle, alignment, font, color). `catalog.json` and `MemeLayout` build on that. | MIT | https://github.com/jacebrowning/memegen |
| 209 template images under `app/src/main/assets/images/*.webp` | Third-party rights, not covered by the memegen MIT license | the same templates memegen uses |

The images may involve trademarks, copyrights, or personality rights of other people. MemEm ships them so the keyboard can render offline. Anyone who redistributes the repo or the APK has to clear those rights. The MIT license of this repo does not grant them.

Impact is not included. The memegen face `thick` is drawn with Anton.

## Search text and examples

| Piece | License | Link | Where |
| --- | --- | --- | --- |
| ImgFlip575K captions | no license stated upstream | https://github.com/schesa/ImgFlip575K_Dataset | search index `app/src/main/assets/index/`, `data/payloads-6491.json`, `reference/memechat/data/captions/` |
| LoC-meme-generator captions | ODC-BY 1.0 (attribution) | https://huggingface.co/datasets/pszemraj/LoC-meme-generator | the same caption files, where the line comes from that set |
| Open Data Commons Attribution License | ODC-BY 1.0 | https://opendatacommons.org/licenses/by/1-0/ | applies to the LoC lines |
| `caption-examples.json` | short example lines written for this project, MIT with the rest of the code | in this repo | prompt examples, not the raw dataset |

The index and payload files are public meme lines from those collections, not private chats. ImgFlip575K states no license. Those lines stay under whatever the upstream allows. They are not relicensed as MIT here.

## Models

Gemma weights are not in this repo. The app and `rewrite-cli` expect local files that the user downloads.

| Model | Terms | Link |
| --- | --- | --- |
| Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) | Gemma Terms of Use, Google | https://ai.google.dev/gemma/terms |
| EmbeddingGemma 2 Text 270M (`embeddinggemma-2-text-270m.litertlm`) | the same Gemma Terms of Use | https://ai.google.dev/gemma/docs/embeddinggemma |

Gemma overview: https://ai.google.dev/gemma/docs/core

## Runtime and libraries

| Piece | License | Link |
| --- | --- | --- |
| LiteRT-LM 0.18.0, Android (`litertlm-android`) and JVM (`litertlm-jvm`, including `liblitertlm_jni.so` for linux-x86_64) | Apache-2.0, POM "The Apache Software License, Version 2.0" | https://github.com/google-ai-edge/LiteRT-LM |
| Kotlin, kotlinx-coroutines, AndroidX (Core, AppCompat, Activity, Compose BOM 2026.03.01, Material 3, test) | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| Gson 2.14.0, transitive through LiteRT-LM | Apache-2.0 | https://github.com/google/gson |
| org.json (`tools/rewrite-cli` and engine tests; on Android the platform class) | JSON License | https://github.com/stleary/JSON-java/blob/master/LICENSE |
| JUnit 4.13.2, tests only | EPL-2.0 | https://junit.org/junit4/license.html |
| Robolectric 4.16.1, tests only, used to produce the images under `docs/images` | MIT | https://github.com/robolectric/robolectric |

## Fonts

All five files are under `app/src/main/assets/fonts/`. The SIL Open Font License 1.1 text is next to them in `OFL.txt`. OFL fonts stay under the OFL even though the app is MIT.

| File | License | Source |
| --- | --- | --- |
| `Anton-Regular.ttf` | OFL 1.1 | Copyright 2020 The Anton Project Authors, Vernon Adams. https://github.com/googlefonts/AntonFont |
| `Kalam-Regular.ttf` | OFL 1.1 | Copyright 2014 Indian Type Foundry (Lipi Raval, Jonny Pinhorn). https://github.com/itfoundry/kalam |
| `TitilliumWeb-Black.ttf`, `TitilliumWeb-SemiBold.ttf` | OFL 1.1 | Copyright 2009 to 2011 Accademia di Belle Arti di Urbino. https://fonts.google.com/specimen/Titillium+Web |
| `NotoSans-Bold.ttf` | Apache-2.0 | Copyright 2012 Google Inc., Monotype. https://github.com/notofonts/latin |

## Earlier own work

[ChatLens](https://github.com/slickdajackson/ChatLense) is the earlier own codebase. Accessibility, paste, the floating dot, and the HyperOS notes were adopted from it. The details are in `docs/chatlens.md`. The agent that opens chats or sends messages is not included.

## Prototype only, not in the app

`reference/memechat/data/e2e/report-20261008-233857.json` contains invented test chats from `reference/memechat/eval/fake_chats.json` and short excerpts from DialogSum (German and English). DialogSum is CC BY-NC-SA 4.0. It is not part of the app, not part of the search index, and not covered by the MIT license. Those excerpts stay under CC BY-NC-SA 4.0.

https://creativecommons.org/licenses/by-nc-sa/4.0/

The repo has no private chats, no credentials, and no Gemma weights.
