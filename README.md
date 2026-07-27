# snapaie

**Cut the fluff. Keep the knowledge.**

The only reading assistant that never sends your pages anywhere — it works with airplane mode on and costs zero data.

snapaie turns a page of text into compressed understanding entirely on-device: ML Kit reads the page, local Gemma (LiteRT-LM) compresses it, and everything is stored in a local database. No account, no login, no cloud sync, no ads.

## Three doors, one engine

All entry points feed the same pipeline:

1. **Select text anywhere in Android** → tap "Snap" in the selection toolbar (`ACTION_PROCESS_TEXT`). Zero permissions, instant result sheet over the host app.
2. **Share sheet** (`ACTION_SEND`) → plain text, PDFs, and images. PDFs render page-by-page through `PdfRenderer` into the same OCR path.
3. **Camera / import** → photograph a physical book page or pick an image.

## Core experience

Snap or share a page → on-device OCR → local AI compresses it into a structured result:

- concise meaning, core idea, author intent
- simplified explanation and actionable takeaways
- hidden meaning and key quotes worth keeping
- filler detection (repetition, padding, decorative setup)
- compression score and honest, locally computed time saved
- CEFR vocabulary (B2 / C1 / C2) on demand

**Explanation styles:** Auto, Concise, Detailed, Bullets, Analogy, Steps. Output can be generated in any of ~49 languages.

## Beyond the scan

- **Forge Recall** — spaced-repetition practice built from what you read. XP and levels, daily streak with streak freezes, a daily quest, a knowledge map with strength rings and due-now states, and three game modes: Rapid Fire (True/False), Survival (one life), and Explain It (Feynman-style AI scoring).
- **Chat with AE** — follow-up conversation about any scan, with 14 personas ("book lenses"), deliverable cards, and four chat appearances.
- **Writing assistant** — fix, rewrite, tone, shorten, expand, paraphrase, humanize, summarize, translate, synonyms, with sub-modes, dialects, and re-roll.
- **Narration** — system TextToSpeech reads results aloud, fully offline.
- **Reader Report** — weekly single screen plus a shareable PNG card (your numbers, no download CTA).

## Architecture

```
Entry (PROCESS_TEXT | SHARE | CAMERA)
  ├─ pixels ──> ML Kit Text Recognition ─┐
  └─ text ──────────────────────────────┤
                                        v
                        Prompt assembly (assets/prompts)
                                        v
                        Gemma / LiteRT-LM (on-device)
                                        v
                     JSON repair ladder ──> KnowledgeResult
                                        v
                        Room persistence + growth stats
```

**Model lifecycle** is the biggest crash risk and is handled in `ModelSessionManager`: lazy-load on first inference (never at app start), unload after 60s idle, unload on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` and when the app is backgrounded, single-flight inference behind a mutex, and a RAM gate based on `ActivityManager.MemoryInfo.totalMem`.

**Output contract:** the model is asked for strict JSON and parsed defensively through a repair ladder (direct parse → fence strip → outermost balanced braces → bare-array wrap → one stricter retry → plain-text render). It never shows a parse error to the user.

## Monetization

Free: unlimited snaps, all styles, all languages, basic history, 5 Forge topics, Rapid Fire, narration.

**Pro is a one-time purchase** (no subscription): full Forge Recall, Markdown/Obsidian export, batch PDF and multi-image processing, the larger model, all personas and chat styles, custom instructions, and no branding footer.

There are no ads and no analytics SDKs.

## Local AI and privacy

OCR, inference, history, stats, and narration all run on-device. The only network request the app makes is the one-time model download the user explicitly approves.

Gemma weights are never committed. Downloads are served from a self-hosted mirror configured by `snapaie.model.mirror.base.url` in `gradle.properties`, with the Gemma Terms of Use surfaced and accepted in-app before any download. Set the official SHA-256 values via `snapaie.model.sha256.e2b` / `snapaie.model.sha256.e4b` before production distribution — downloads are resumable and checksum-verified.

## Build

Open the project folder in Android Studio (the directory containing this README), sync Gradle, and run the `app` configuration. From a shell with Java 17 and the Android SDK configured:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Toolchain: AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.10, Gradle 8.14.3, compileSdk 36, minSdk 31 (required by `Modifier.blur`).

Sources:

- ML Kit Text Recognition Android docs: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- LiteRT-LM Android guide: https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM repository and releases: https://github.com/google-ai-edge/LiteRT-LM
