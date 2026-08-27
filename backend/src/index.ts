import express from 'express';
import { z } from 'zod';
import { config } from './config.js';
import { chooseSentences, condensePassages, transcribe } from './gemini.js';
import { requireAuth, issueToken, type AuthedRequest } from './auth.js';
import { PLAY_NOT_CONFIGURED, verifyPurchase } from './play.js';
import {
  QuotaError,
  balanceOf,
  consumePages,
  grantPages,
  initQuota,
  refundPages,
} from './quota.js';

const app = express();

// A page image is the largest thing this service accepts. The app downscales to a
// 1024px long edge before sending, so anything near this ceiling is a client that
// skipped that step rather than a genuinely large page.
app.use(express.json({ limit: '12mb' }));

/**
 * Railway polls this. It must not touch Gemini, Play or the database — a health
 * check that depends on everything reports "unhealthy" for problems that have
 * nothing to do with whether the process is alive, and Railway restarts a service
 * that was fine.
 */
app.get('/healthz', (_req, res) => {
  res.json({ ok: true, model: config.geminiModel, quota: config.databaseUrl ? 'postgres' : 'memory' });
});

const sessionBody = z.object({
  productId: z.string().min(1),
  purchaseToken: z.string().min(1),
});

/**
 * Exchanges a Play purchase for a short-lived token.
 *
 * The purchase is checked with Google every time rather than trusted from the app.
 * This is also where pages are granted, so a replayed token tops up the same account
 * it already belongs to rather than minting a new balance.
 */
app.post('/v1/auth/session', async (req, res) => {
  const parsed = sessionBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'bad_request' });
    return;
  }

  try {
    const purchase = await verifyPurchase(parsed.data.productId, parsed.data.purchaseToken);
    if (!purchase) {
      res.status(402).json({ error: 'purchase_not_valid' });
      return;
    }
    const pagesLeft = await grantPages(purchase.accountId, purchase.pages, purchase.plan);
    const token = await issueToken(purchase.accountId, purchase.plan);
    res.json({ token, plan: purchase.plan, pagesLeft });
  } catch (error) {
    if (error instanceof Error && error.message === PLAY_NOT_CONFIGURED) {
      // Being explicit beats a 500. This is a deployment that has not finished
      // being set up, not a user who did something wrong.
      res.status(503).json({ error: 'purchase_verification_not_configured' });
      return;
    }
    console.error('[auth] session failed', error);
    res.status(500).json({ error: 'server_error' });
  }
});

app.get('/v1/balance', requireAuth, async (req: AuthedRequest, res) => {
  res.json({ pagesLeft: await balanceOf(req.accountId!) });
});

const transcribeBody = z.object({
  imageBase64: z.string().min(1),
  mimeType: z.string().default('image/jpeg'),
});

/**
 * Reads one page image — the thing the phone genuinely cannot do.
 *
 * A page is taken before the model runs and given back if the model fails, so a
 * timeout or a bad gateway costs the user nothing while a real refusal still costs
 * a page. The one case that deliberately does *not* refund is a reply that came back
 * fine but was not a transcription: the work was done and paid for either way.
 */
app.post('/v1/transcribe', requireAuth, async (req: AuthedRequest, res) => {
  const parsed = transcribeBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'bad_request' });
    return;
  }
  const accountId = req.accountId!;

  let pagesLeft: number;
  try {
    pagesLeft = await consumePages(accountId, 1);
  } catch (error) {
    if (error instanceof QuotaError) {
      res.status(402).json({ error: error.code, message: error.message });
      return;
    }
    throw error;
  }

  try {
    const text = await transcribe(parsed.data.imageBase64, parsed.data.mimeType);
    res.json({ text, pagesLeft, readable: text.length > 0 });
  } catch (error) {
    await refundPages(accountId, 1);
    console.error('[transcribe] failed', error);
    res.status(502).json({ error: 'transcription_failed' });
  }
});

const condenseBody = z.object({
  numberedSentences: z.string().min(1),
  targetWords: z.number().int().positive().max(20_000),
});

/**
 * Chooses which sentences survive.
 *
 * Returns indices, never prose. The app reassembles from its own copy of the text,
 * so nothing this endpoint says can end up in front of a reader as the author's
 * words — the worst a bad reply can do is choose badly, and the app can see that.
 */
app.post('/v1/condense', requireAuth, async (req: AuthedRequest, res) => {
  const parsed = condenseBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'bad_request' });
    return;
  }
  const accountId = req.accountId!;

  let pagesLeft: number;
  try {
    pagesLeft = await consumePages(accountId, 1);
  } catch (error) {
    if (error instanceof QuotaError) {
      res.status(402).json({ error: error.code, message: error.message });
      return;
    }
    throw error;
  }

  try {
    const keep = await chooseSentences(parsed.data.numberedSentences, parsed.data.targetWords);
    res.json({ keep, pagesLeft });
  } catch (error) {
    await refundPages(accountId, 1);
    console.error('[condense] failed', error);
    res.status(502).json({ error: 'condense_failed' });
  }
});

const batchBody = z.object({
  passages: z
    .array(
      z.object({
        id: z.number().int().nonnegative(),
        text: z.string().min(1),
        targetWords: z.number().int().positive().max(20_000),
      }),
    )
    .min(1)
    .max(25),
  ledger: z.string().max(8_000).default(''),
  style: z.string().max(1_000).default(''),
  /** Source pages this batch represents, which is what it costs. */
  pages: z.number().int().positive().max(500),
});

/**
 * Condenses many passages of a book in one request.
 *
 * This endpoint is the reason a book takes minutes rather than hours. The phone walks a
 * book one passage at a time because it can only hold one at a time; repeating that over
 * HTTP would spend the whole saving on round trips.
 *
 * Charged per source page, not per request. A batch is not cheaper to serve because it
 * arrived in one envelope, and pricing it per call would let a client make books free by
 * batching harder.
 *
 * Passages that come back missing are simply absent from the reply rather than blank or
 * shifted. The caller condenses those itself, so one skipped passage costs that passage
 * and nothing after it.
 */
app.post('/v1/condense/batch', requireAuth, async (req: AuthedRequest, res) => {
  const parsed = batchBody.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'bad_request' });
    return;
  }
  const accountId = req.accountId!;
  const { passages, ledger, style, pages } = parsed.data;

  let pagesLeft: number;
  try {
    pagesLeft = await consumePages(accountId, pages);
  } catch (error) {
    if (error instanceof QuotaError) {
      res.status(402).json({ error: error.code, message: error.message });
      return;
    }
    throw error;
  }

  try {
    const condensed = await condensePassages(passages, ledger, style);
    // Give back what was paid for and not delivered, so a half-answered batch is not
    // charged in full.
    const missing = passages.length - condensed.length;
    if (missing > 0) {
      await refundPages(accountId, Math.round((pages * missing) / passages.length));
    }
    res.json({ passages: condensed, pagesLeft: await balanceOf(accountId) });
  } catch (error) {
    await refundPages(accountId, pages);
    console.error('[condense/batch] failed', error);
    res.status(502).json({ error: 'condense_failed' });
  }
});

app.use((_req, res) => res.status(404).json({ error: 'not_found' }));

async function main(): Promise<void> {
  await initQuota();
  app.listen(config.port, () => {
    console.log(`[snapaie] listening on ${config.port}, model ${config.geminiModel}`);
  });
}

main().catch((error) => {
  // Refusing to start beats starting broken. A service that boots without its
  // configuration only fails later, in front of someone who paid.
  console.error('[snapaie] failed to start:', error instanceof Error ? error.message : error);
  process.exit(1);
});
