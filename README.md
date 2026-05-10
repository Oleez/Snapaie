# snapaie

**Cut the fluff. Keep the knowledge.**

snapaie is a local-first Android app for turning book pages into compressed understanding. The user snaps or imports a page, ML Kit OCR extracts the text, and local Gemma via LiteRT-LM compresses the scan into high-signal knowledge: concise meaning, core idea, author intent, filler detection, simplified explanation, smart vocabulary, and practical takeaways.

This is not an AI explainer clone, not generic OCR, and not a chatbot. The product goal is to make users feel like they are becoming smarter faster.

## Core Experience

1. Snap or import a book/page image.
2. OCR extracts page text on device.
3. Local AI identifies filler, repetition, low-value paragraphs, and unnecessary complexity.
4. snapaie outputs a sharp clarity result:
   - concise meaning
   - core idea
   - author intent
   - simplified explanation
   - actionable insights
   - important vocabulary
   - compression score
   - estimated time saved

## AI Modes

- **Concise:** aggressive compression.
- **Core Insight:** main takeaway, author intent, what truly matters.
- **Student:** simplified, clear, exam-focused.
- **Fast Read:** instant page summary to reduce reading time.
- **Deep Meaning:** hidden philosophy, psychology, or business insight when supported by the text.

## Current Scaffold

- Kotlin DSL Android project with one `app` module.
- Jetpack Compose navigation shell: Scan, Compress, Clarity, Growth.
- Liquid Glass Knowledge Interface primitives with translucent surfaces, dynamic lighting, scan line visual, and floating cards.
- ML Kit OCR wrapper for imported page images.
- LiteRT-LM local inference abstraction for Gemma 4 `.litertlm` models.
- Room persistence for offline scan history.
- Reader growth stats: streak, pages processed, minutes saved, average compression.
- Versioned prompts in `app/src/main/assets/prompts`.

## Local AI And Privacy

snapaie is designed for local OCR and local LLM inference. Book/page text should stay on device. Future backend interfaces may support auth, sync, subscriptions, or analytics, but prompts and OCR text should not be sent to a server unless that product decision is explicitly changed.

## Model Licensing

Gemma model files are not committed to this repository. Download or sideload only models you are licensed to use, and keep the model license visible to testers. The initial model URLs point at LiteRT Community Gemma 4 `.litertlm` files on Hugging Face. Add official SHA-256 values in `app/src/main/java/com/snapaie/android/data/model/Models.kt` before production distribution.

## Technical Notes

The app targets:

- ML Kit Text Recognition for OCR.
- Gemma 4 E2B via LiteRT-LM for low-latency on-device compression.
- API 31+ for consistent liquid glass effects.
- Room for offline library and growth stats.

Sources:

- ML Kit Text Recognition Android docs: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- LiteRT-LM Android guide: https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM repository and releases: https://github.com/google-ai-edge/LiteRT-LM

## Build

Open this folder in Android Studio:

```powershell
H:\The Run\3. snapaie
```

Then sync Gradle and run the `app` configuration. From a shell with Java and Android SDK configured:

```powershell
.\gradlew.bat :app:assembleDebug
```
