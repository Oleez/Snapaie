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

There are two Dockerfiles for one service — this directory and the repository root — and
that is on purpose. Railway looks for a Dockerfile in the service's **Root Directory**, and
this repository has two defensible answers for what that should be: empty, because the repo
root is what GitHub connected, or `backend`, because that is where the server lives. Having
only one meant whichever value the dashboard happened to hold decided whether the build
worked. Now either does.

**Set Root Directory to `backend`.** That gives the service the same shape as any ordinary
Node repo: the service root *is* the server.

1. **Settings → Source → Root Directory** → `backend`
2. **Settings → Build → Builder** → must say **Dockerfile**. After a run of failed builds
   Railway may have pinned the service to Railpack, and a pinned builder ignores
   `railway.json` — this is the setting most likely to still be wrong.
3. **Variables** → from `.env.example`. Minimum to boot: `GEMINI_API_KEY` and `JWT_SECRET`
   (`openssl rand -base64 48`). The service refuses to start without them.
4. **New → Database → Postgres.** `DATABASE_URL` is injected and the schema creates itself.
   Without it quota is in memory and resets on every deploy — do not take real money then.
5. **Settings → Networking → Generate Domain.** A new service is unexposed, so there is
   nothing to curl until you do this.
6. `curl https://<your-domain>/healthz`

A successful build shows Docker layers in the log. If it still prints `railpack prepare` and
lists `build.gradle.kts`, Railway is reading the Android project and the Root Directory did
not take.

Then set `snapaie.cloud.base.url` in `gradle.properties` to that domain.

If you edit one Dockerfile, edit the other. They differ only in the `COPY` paths.

## Where the secrets live, and why Railway

Railway and Supabase are not competing for this job.

Railway **runs the server**. Supabase is a **Postgres database** with auth and storage
attached. The Gemini key has to live in whatever process makes the Gemini call, so it goes
in Railway's variables either way — Supabase has nowhere to put it unless you move the whole
server into Edge Functions.

So the split worth making is: Railway for the service, and either Railway's Postgres plugin
or Supabase for the data. Railway's plugin is one click and injects `DATABASE_URL` for you.
Supabase is worth choosing if you want one database across your projects, or its auth later
— which you will, when device ids stop being enough.

Either works unchanged. `DATABASE_URL` is a standard Postgres URL, and SSL is turned on
automatically for anything that is not local, because managed providers refuse an
unencrypted connection and say so unhelpfully when they do.

What must never happen is the key reaching the app. An APK is a zip file on someone's
phone; a key inside one is a key you have published.

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
