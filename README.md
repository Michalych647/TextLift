# TextLift

Select and copy text from any image on Android, without sending the image anywhere. It works a bit like Live Text on iOS or text selection on Pixel phones, but fully local and without Google Play services.

TextLift is a fork of [AhmetCanArslan/TextGrab](https://github.com/AhmetCanArslan/TextGrab), which is itself a fork of [notune/TextGrab](https://github.com/notune/TextGrab). Most of the app comes from their work, and this fork builds on top of it. It has its own name and package, so it installs next to both of them.

## What this fork adds

Everything below is on top of the parent forks.

- **PaddleOCR v5 engine.** An optional second recognition engine that runs on ONNX Runtime and reads tricky fonts and backgrounds much better. It uses the mobile models, which you download from inside the app.
- **Tesseract on top of ML Kit.** ML Kit still finds the text first, then Tesseract can refine the lines with language models you install. This fixes cases like Russian text coming out as lookalike Latin letters ("Рекомендация" turning into "PekomeHAcqa").
- **OCR section on the home screen.** An engine picker and a model manager. Models are listed alphabetically, installed ones sit on top, you can search them, and a tap on a card or its icon downloads or removes it.
- **First-launch model setup.** The app offers to download models for your system language and English. You can skip it and do it later.
- **More free translation engines.** Google, Yandex and Microsoft, no API key and no settings. They use unofficial public endpoints and can be rate-limited or change without notice.
- **Translation memory.** While an image is open, translated text is remembered, so switching back to the original and translating again does not hit the engine a second time. The memory is cleared when you close the image.
- **Translate only the selection.** If you select text and press translate, only that text is translated and remembered. Anything translated later is added to the memory as well.
- **Search inside the image.** The search button replaces the toolbar with a search bar. Matches are highlighted in blue as you type, for any alphabet.
- **Save to gallery.** The save button stores the image, and if translation is on it stores the translated version.
- **Russian interface** in addition to English and German.

## What the parent forks provide

- **Assistant gesture.** Long-press home or the navigation handle once TextLift is set as the default digital assistant.
- **adb trigger.** Start a capture from a computer. The receiver requires the `DUMP` permission, so only adb can send these:

```
adb shell am broadcast -a io.github.michalych647.textlift.action.CAPTURE -p io.github.michalych647.textlift
adb shell am broadcast -a io.github.michalych647.textlift.action.LATEST_SCREENSHOT -p io.github.michalych647.textlift
```

- **Capture preview, native-feeling text selection, filtering of icons and stray symbols, and bundled Chinese, Japanese and Korean models.**

## How to use

**From anything on your screen to copied text in a few seconds:**

1. Swipe down and tap the **OCR screenshot** tile.
2. Tap or drag over the text, then copy.

One-time setup: open quick settings, tap the edit (pencil) button and drag the **OCR screenshot** tile into your tiles. Then enable instant capture with the button on the app's home screen, so the tile can take the screenshot by itself (Android 12 and newer, it uses a screenshot-only accessibility service that reads no screen content). Without instant capture, take a screenshot first and the tile opens it.

**For existing images:** share any image from any app to TextLift and the text becomes selectable. Opening image files with TextLift from a file manager works too, and so does picking an image from the app's home screen.

**Long-press home:** set TextLift under Settings, Apps, Default apps, Digital assistant app. Long-pressing home (or the gesture handle) then captures the screen like the tile does. On devices with Circle to Search, turn that off first.

## Text recognition

There are two engines, and you can switch between them any time in the OCR section.

**Tesseract** is the default. ML Kit recognizes the text first, and Tesseract refines the lines using the language models you have installed. Models come from `tessdata_fast`.

**PaddleOCR v5** needs the Universal model (text detection and orientation) plus at least one language. Available language groups: Chinese, Japanese and English, Latin script languages, Russian, Ukrainian and Belarusian, other Cyrillic languages, Korean, Arabic, Devanagari, Thai, Greek, Tamil and Telugu. If Paddle is not ready, the app falls back to Tesseract without bothering you.

Recognition itself runs on your device. The internet is used only to download models and for online translation engines.

## Translation

Tap the translate button in the toolbar and every recognized line on the image is replaced in place by its translation. Tap it again to see the original, and long-press it to pick the target language. Selecting and copying works on the translated text. The source language is detected automatically and the target language is remembered.

Engines:

- On-device ML Kit translator. Each language model (about 30 MB) is downloaded once, then translation works offline.
- Free Google, Yandex and Microsoft. Text you translate with them is sent to that service.
- Cloud engines with your own API key.

## Known limitations

- Paddle finds text with axis-aligned boxes, so strongly rotated or curved text is read less well.
- If the orientation model fails to download, upside-down lines are not flipped. Everything else keeps working.
- ONNX Runtime adds roughly 15 to 20 MB to the APK per CPU architecture, and the first recognition after launch is slower while models load.
- The free translation engines depend on unofficial endpoints.
- Strings missing in a language fall back to English.

## Building

You need JDK 17 or newer and the Android SDK.

```
./gradlew assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`.

For a signed release, create a keystore and put its credentials in `keystore/keystore.properties` (that directory is gitignored):

```
storeFile=keystore/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then run `./gradlew assembleRelease`. The build produces separate APKs for `arm64-v8a` and `armeabi-v7a`, plus a universal APK that contains both, in `app/build/outputs/apk/release/`. Without the keystore file the release build is unsigned. Keep your keystore safe, because updates must be signed with the same key.

## Installing

Download an APK from [Releases](../../releases) and open it on your phone. Pick the one that matches your phone:

- `arm64-v8a`: almost every phone made in the last several years. Start here.
- `armeabi-v7a`: older 32-bit phones.
- `universal`: works on both, but it is the largest download. Use it if you are not sure.

You can also add this repository as a source in [Obtainium](https://github.com/ImranR98/Obtainium) to get updates.

TextLift has its own package name, so it does not replace the TextGrab apps you may already have. Settings, downloaded models and permissions are not shared between them. The quick settings tile, the accessibility service and the default assistant need to be set up again for TextLift.

To check a downloaded APK on a computer:

```
apksigner verify --print-certs TextLift.apk
```

## Notes

- `minSdk 26` (Android 8.0), `targetSdk 36`.
- Photo permission is only requested for the "latest screenshot" feature. Images opened via share or the picker need no permission.
- Tesseract models come from [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), and PaddleOCR v5 models are the ONNX versions published by [PaddlePaddle](https://huggingface.co/PaddlePaddle) on Hugging Face.

## Credits

Original app by [notune](https://github.com/notune/TextGrab). The fork this one is built on is by [AhmetCanArslan](https://github.com/AhmetCanArslan/TextGrab). TextLift is maintained by [Michalych647](https://github.com/Michalych647).

## License

The app source code is under the [MIT License](LICENSE). The original copyright notices are kept, as the license requires.

The bundled ML Kit text recognition SDK and its model are proprietary Google software, used and redistributed under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms). They are pulled from Google's Maven repository at build time and are not part of this repository. Tesseract, ONNX Runtime and PaddleOCR have their own licenses, so check them if you plan to redistribute.
