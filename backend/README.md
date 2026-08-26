# snapaie backend — Cloud Read

Transcription and condensation for pages the phone cannot handle offline. The offline
app reads printed pages with ML Kit and shortens them on-device; this service exists for
the one thing neither can do — **handwriting** — and for people who would rather a larger
model chose what to cut.

## Why this exists at all

The Gemini API key must never ship inside an APK. An APK is a zip file on someone's phone;
a key inside one is a key you have given away. It lives here, in a Railway variable, and
the app never sees it.

The same reasoning applies to entitlement. The app already caches a `isPro` boolean in
DataStore, which is fine for unlocking a colour scheme and useless for anything that spends
money — anyone can flip it. Every paid request here is checked against Google Play, and
quota is counted server-side, because a counter on the phone is a spend limit the spender
can edit.

## Deploying on Railway

The `Dockerfile` and `railway.json` that build this service live at the **repository root**,
not in this directory. That looks wrong and is deliberate: Railway looks for them in the
service's root directory, and this repository's root is an Android app. Left to itself,
railpack finds `build.gradle.kts` and `gradlew.bat` and fails with *"could not determine how
to build the app"*. Putting them at the root means no dashboard setting is needed and none
can be lost.

1. **Variables** → add the values from `.env.example`. Minimum to boot: `GEMINI_API_KEY` and
   `JWT_SECRET` (`openssl rand -base64 48`). The service refuses to start without them.
2. **New → Database → Postgres.** `DATABASE_URL` is injected automatically and the schema
   creates itself. Without it quota lives in memory and resets on every deploy — do not take
   real money in that state.
3. **Settings → Networking → Generate Domain.** A new service is unexposed, so there is
   nothing to curl until you do this.
4. Check it: `curl https://<your-domain>/healthz`

Leave **Root Directory** empty. Setting it to `backend` also works, but then Railway looks
for a Dockerfile in here and there isn't one.

Then set `snapaie.cloud.base.url` in `gradle.properties` to that domain.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/healthz` | — | Liveness. Touches nothing else, deliberately. |
| POST | `/v1/auth/session` | — | Play purchase token → short-lived JWT + page balance |
| GET | `/v1/balance` | Bearer | Pages remaining |
| POST | `/v1/transcribe` | Bearer | Page image → text (handwriting) |
| POST | `/v1/condense` | Bearer | Numbered sentences → indices to keep |

`/v1/condense` returns **indices, never prose**. The app reassembles from its own copy of
the text, so nothing this service returns can reach a reader as the author's words.

## Spend safety

Three independent limits, because the expensive failure is not one greedy user, it is a bug:

- **Per account** — pages are decremented before the model runs, in a single guarded
  `UPDATE`, so two racing requests cannot both succeed. A page is refunded if the model
  itself fails.
- **Global daily cap** — `MAX_PAGES_PER_DAY_GLOBAL`, across every account. This is what
  stands between a leaked token or a client retry loop and a serious bill.
- **Short tokens** — six hours. A token that escapes stops being useful the same day.

## Local development

```bash
cd backend
npm install
npm run build
GEMINI_API_KEY=... JWT_SECRET=dev-secret node dist/index.js
curl localhost:8080/healthz
```

The image is built from the root: `docker build -f Dockerfile -t snapaie-backend .`

The service refuses to start without its configuration rather than booting and failing on
the first real request, which turns a five-second mistake into a support ticket.
