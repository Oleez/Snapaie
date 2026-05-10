# SnapAE

SnapAE is an Android-first content and growth OS for turning rough product notes, transcripts, screenshots, updates, and ideas into structured launch packages. It is designed for local Gemma 4 inference through Google AI Edge LiteRT-LM, with no cloud LLM calls in the app architecture.

## Status

This is the initial Compose scaffold:

- Kotlin DSL Android project with a single `app` module.
- Jetpack Compose navigation shell for Input Hub, Orchestrator Run, Launch Package, and Library.
- Liquid-glass design primitives using Material 3, translucent surfaces, and restrained motion.
- Room persistence for offline launch run history.
- Model setup flow with storage/RAM warnings, resumable OkHttp download plumbing, checksum hook, cancellation-ready inference abstraction, and LiteRT-LM dependency wiring.
- Versioned prompt templates in `app/src/main/assets/prompts`.

## Local AI Stance

SnapAE treats AI generation as on-device only. Prompts, transcripts, product context, and generated packages should remain on the device. Future backend interfaces may support auth, sync, analytics, or subscriptions, but prompts should not be routed through servers unless that product decision is explicitly changed.

## Model Licensing

Gemma model files are not committed to this repository. Download or sideload only models you are licensed to use, and keep the model license visible to testers. The initial model URLs point at LiteRT Community Gemma 4 `.litertlm` files on Hugging Face. Add official SHA-256 values in `app/src/main/java/com/snapae/android/data/model/Models.kt` before production distribution.

The project follows the LiteRT-LM Android path documented by Google AI Edge. The Android guide lists the Gradle package `com.google.ai.edge.litertlm:litertlm-android` and shows streaming with `sendMessageAsync`.

Sources:

- LiteRT-LM Android guide: https://ai.google.dev/edge/litert-lm/android
- LiteRT-LM repository and releases: https://github.com/google-ai-edge/LiteRT-LM

## Hardware Expectations

Gemma 4 edge models are large. The app surfaces warnings when reported RAM or available storage look too low for the selected tier. Production builds should provide multiple quantized tiers and degrade gracefully with shorter contexts, fewer workflow passes, and smaller output targets.

## Build

Open the folder in Android Studio:

```powershell
H:\The Run\3. SnapAE
```

Then sync Gradle and run the `app` configuration. From a shell with Java and Android SDK configured:

```powershell
.\gradlew.bat :app:assembleDebug
```

## GitHub

Remote target:

```powershell
git remote add origin https://github.com/Oleez/SnapAE.git
git push -u origin main
```
