# Model delivery

The offline model is **not** bundled in the APK. The app ships only model-management
logic and learns everything about the artifact at runtime from a remote manifest, so a
new model can be released without a Play Store update.

## Manifest

`snapaie.model.manifest.url` in `gradle.properties` points at a small `latest.json`.
See `latest.example.json` for the shape.

| Field | Meaning |
| --- | --- |
| `modelId` | Stable identity of the model line (e.g. the model family + variant). |
| `version` | Model version, independent of the app version. Dotted numeric compare. |
| `filename` | File name to store on device. |
| `downloadUrl` | Direct HTTPS URL to the artifact. |
| `sizeBytes` | Exact expected size; mismatches abort before hashing. |
| `sha256` | Exact expected digest; mismatches discard the download. |
| `runtime` / `runtimeVersion` | Runtime contract. The app refuses artifacts it cannot load. |
| `minAppVersion` | Lowest app `versionCode` allowed to use this artifact. |
| `releaseNotes` | Optional text shown on the update card. |

App version and model version are deliberately independent: app 1.0.0 can move from
model `x v1.0.0` to `x v1.1.0` with no APK change.

## Flow

1. On app start (throttled to once per 24h) the manifest is fetched and cached.
2. Its version is compared with the installed model's version.
3. A newer compatible model surfaces an update card. Nothing downloads automatically —
   a multi-GB transfer always requires an explicit tap.
4. The download runs as unique WorkManager work with a foreground service and progress
   notification, resuming from a `.part` file across interruptions.
5. The file is size-checked, SHA-256 verified, then atomically renamed into place and
   recorded in the registry as installed (but not yet active).
6. The engine loads it. Only a successful load promotes it to active and prunes the old
   version. A failed load deletes the new artifact and leaves the old one serving.

## On-device layout

```
files/models/
  registry.json                     active pointer + installed records
  latest-manifest.json              cached manifest
  <modelId>/<version>/<filename>    the artifact
  <modelId>/<version>/<filename>.part   resumable partial download
```

## Failure handling

Manifest unreachable, malformed, incompatible, or absent: the app keeps using whatever
is installed and reports why. If nothing is installed, scans fall back to instant
heuristic drafts and the AI-dependent screens say the model is missing.
