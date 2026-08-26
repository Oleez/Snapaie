# snapaie

**A 500-page novel, told again in 150 pages. Or 50. Nothing skipped.**

snapaie condenses whole books on your device. Not a summary — an *abridgement*: the same
story, in the same order, in the author's own words, just shorter. The sentences that
survive are the ones the author wrote, untouched. Every event survives. Images come with
it. The table of contents is rebuilt against the new page numbers.

It runs entirely offline. No account, no cloud, no ads, no analytics. The only network
request the app ever makes is the one-time model download you explicitly approve. Until you
do, pages are still shortened — just without it.

## What it does

**Bring a book in** — share or "Open with" a PDF or EPUB from any app, pick one from
storage, or photograph a physical book page by page.

**Choose a length** — 30%, 10%, or an exact page count. The app tells you up front roughly
how long it will take, because a 500-page book is a multi-hour job on a phone.

**Read it as it is written** — chapters finish in order, so you can start chapter 1 while
chapter 20 is still running. Any passage can be opened beside its source to check it.

**Take it with you** — export as PDF (with working bookmarks and a clickable rebuilt
contents), EPUB, Markdown, or plain text. Share it out or save it to Files.

The original page-level tools are still here: select text anywhere in Android and tap
"Snap", share a single page, or photograph one. Those give the structured knowledge scan —
core idea, vocabulary, filler detection — plus Forge Recall, chat, and the writing
assistant.

## How the condensation works

```
PDF / EPUB / camera
        ↓  text layer where there is one, OCR where there is not
   one flat text buffer
        ↓  chapters from the outline, or from heading heuristics
   chapters → beats (~900 source words each)
        ↓  every beat, in order, carrying a story ledger forward
   condensed prose per beat
        ↓  layout, fixed-point index rebuild
   PDF / EPUB / Markdown / text
```

Four ideas hold it together.

**Shortening is deletion, not rewriting.** The model is never asked to produce prose. It is
given the passage split into numbered sentences and asked which numbers to keep; the text is
reassembled from the originals, in the original order.

**The device chooses first, and the model is asked to do better.** `SentenceRanker` ranks
sentences by shared vocabulary — TextRank, settled by power iteration — and answers in well
under a millisecond, so a readable page exists before the model is even woken. When the
model answers, its choice wins: it knows which sentence carries the turn in a scene and
which is scenery, and vocabulary overlap can only approximate that.

The order is the point. The model can be slow, fail to load, or return something
unusable, and none of it can leave you with an empty screen — because the page is already
there. What it cannot be any more is the only thing standing between a snap and a result. This is the difference
between an abridged edition and a summary, and it is why a good abridgement still reads
like the book — nothing is paraphrased into a flatter register, no detail is invented, and
the voice is the author's because the words are. It is also far cheaper: emitting ten
numbers costs a fraction of writing three hundred words, which is most of why the app got
faster. Styles that are genuinely a different piece of writing — Bullets, Steps, Analogy —
cannot be reached by deletion and are still composed by the model.

Because each sentence can be judged almost independently, a passage larger than the model's
context is simply walked in as many consecutive runs as it takes and the kept indices joined
at the end — so how long a capture can be has nothing to do with how much the model can read
at once. Those styles that must be composed are abridged down to fit first, so a bulleted
page is drawn from the whole capture rather than from whatever happened to come first.

**Beats tile the text exactly.** Every character of the source belongs to precisely one
beat, and a run is finished only when no beat is left unwritten. "Nothing was skipped"
stops being a claim about model behaviour and becomes a property of the segmentation,
checkable with a `COUNT`. It is also the entire resume mechanism — the job asks for the
next unfinished beat, which is the same question whether it is minute one or a restart
after an overnight reboot.

**A story ledger travels between beats.** The model only ever sees a ~900-word window.
Without carried state it re-introduces characters it has already met, renames them, forgets
who died, and drops threads set up chapters ago. The ledger holds who, where, what is
unresolved and where the last passage stopped — capped, and evicted by
least-recently-mentioned.

**Length is governed, not guessed.** A flat ratio does not land: a model that runs 25% long
finishes a 150-page target 40% over. After every beat the governor recomputes what ratio
the remaining source needs to hit the remaining budget — clamped, so an early overshoot
cannot starve the final chapters into collapse. A few percent long beats a gutted ending.

Below about 20% the work goes via an intermediate 30% pass, because asking a 2B model for
10:1 in one step is where skipping comes from. That intermediate edition is kept and is
readable in its own right.

**No passage can fail.** Over thousands of model calls something will come back empty,
truncated, or as a summary. Beats are retried with a widened allowance, then written
extractively rather than failed. A rough paragraph in the right place is recoverable; a
hole in a story is not. Those passages are marked in the reader.

## The model

It does the work: reads the page, decides what to cut, and writes the styles that have to
be composed. One thing on that list the device cannot do at all — **it reads the page**.

A text recogniser matches printed shapes. Hand it a page of handwriting and it returns a
few stray characters or nothing at all — which is why a letter, a lab notebook, lecture
notes or a form filled in by hand were previously out of reach. A model that looks at the
image can read them. Turn on "Handwritten page" under a photo and it transcribes what is
there; the shortening then happens on the device as usual.

It transcribes and stops. Asking it to read *and* condense in one pass was a single slow
call that could fail at either job with no way to tell which, and the condensing is better
done by the ranker anyway.

Beyond reading, it chooses what to cut from every page, writes the styles that must be
*written* rather than trimmed — Bullets, Steps, Analogy — and powers chat over a finished
book and the writing assistant. Without it the app still shortens pages; it just does the
choosing itself.

Gemma 4 E2B, Apache-2.0, in LiteRT-LM's `.litertlm` format:

| variant | size | when |
|---|---|---|
| `gemma-4-E2B-it-gpu.litertlm` | 1.9 GB | device has an OpenCL driver |
| `gemma-4-E2B-it.litertlm` | 2.4 GB | everything else |

From <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm> — not gated, no
token needed. Weights are never committed and never bundled in the APK.

Delivery is described in `docs/model-delivery.md`. In short: a remote `latest.json`
(`snapaie.model.manifest.url` in `gradle.properties`) is the only channel model facts reach
the app through, so a new model ships without a Play release. When no URL is configured the
app falls back to `assets/model/default-manifest.json`, so a clean checkout still has
working AI instead of silently degrading to heuristic drafts.

`ModelSessionManager` owns the engine: lazy-load on first inference, unload after 60s idle,
unload on `onTrimMemory` and on backgrounding, single-flight behind a mutex, and a
refcounted keep-alive so a multi-hour condense run does not reload 2 GB between passages.
A GPU load failure retries on CPU, and an artifact is only ever deleted when a different
proven model exists to fall back to.

## Known limitations

- **A full-length book takes hours.** The UI says so before you start, runs the job as
  resumable foreground work, pauses when the device gets too hot, and lets you read what is
  finished. It does not make it fast.
- **The document scanner needs Google Play Services.** ML Kit's scanner gives edge
  detection and auto-capture; without Play Services the app falls back to plain camera
  capture, so scanning still works but you crop by hand.
- **PDF export uses the standard PDF fonts**, which cover Western European text. Non-Latin
  scripts are not yet supported in PDF output; EPUB and Markdown have no such limit.

## Build

Open the project folder in Android Studio and run `app`. From a shell with Java 17 and the
Android SDK:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Toolchain: AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.10, Gradle 8.14.3, compileSdk 36, minSdk 31
(`Modifier.blur`). Debug APKs are large because LiteRT-LM and PDFBox ship native libraries
for four ABIs; release builds keep only `arm64-v8a` and `armeabi-v7a`.

Sources:

- LiteRT-LM Android guide: https://developers.google.com/edge/litert-lm/android
- LiteRT-LM repository: https://github.com/google-ai-edge/LiteRT-LM
- ML Kit text recognition: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- ML Kit document scanner: https://developers.google.com/ml-kit/vision/doc-scanner
