import pg from 'pg';
import { config } from './config.js';

/**
 * How many pages an account has left, and a ceiling across all of them.
 *
 * Quota has to live here rather than in the app. A counter on the phone is a spend
 * limit anyone can edit, and the bill for getting that wrong arrives from Google
 * rather than from the user.
 */

const SCHEMA = `
CREATE TABLE IF NOT EXISTS accounts (
  id            TEXT PRIMARY KEY,
  pages_left    INTEGER NOT NULL DEFAULT 0,
  plan          TEXT    NOT NULL DEFAULT 'none',
  renews_at     TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS usage_daily (
  day           DATE PRIMARY KEY,
  pages         INTEGER NOT NULL DEFAULT 0
);
`;

let pool: pg.Pool | null = null;

/** In-memory fallback so the service boots for a smoke test without a database. */
const memory = {
  accounts: new Map<string, { pagesLeft: number; plan: string }>(),
  today: { day: '', pages: 0 },
};

export async function initQuota(): Promise<void> {
  if (!config.databaseUrl) {
    console.warn(
      '[quota] DATABASE_URL is not set. Quota is in memory and resets on every ' +
        'restart, so a user who waits gets unlimited pages. Add the Railway Postgres ' +
        'plugin before taking real money.',
    );
    return;
  }
  pool = new pg.Pool({ connectionString: config.databaseUrl, max: 5 });
  await pool.query(SCHEMA);
  console.log('[quota] Postgres ready.');
}

function todayKey(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The global ceiling, checked before any account's own balance. */
async function globalPagesToday(): Promise<number> {
  if (!pool) {
    if (memory.today.day !== todayKey()) memory.today = { day: todayKey(), pages: 0 };
    return memory.today.pages;
  }
  const { rows } = await pool.query<{ pages: number }>(
    'SELECT pages FROM usage_daily WHERE day = $1',
    [todayKey()],
  );
  return rows[0]?.pages ?? 0;
}

async function recordGlobalPages(pages: number): Promise<void> {
  if (!pool) {
    memory.today.pages += pages;
    return;
  }
  await pool.query(
    `INSERT INTO usage_daily (day, pages) VALUES ($1, $2)
     ON CONFLICT (day) DO UPDATE SET pages = usage_daily.pages + EXCLUDED.pages`,
    [todayKey(), pages],
  );
}

export async function balanceOf(accountId: string): Promise<number> {
  if (!pool) return memory.accounts.get(accountId)?.pagesLeft ?? 0;
  const { rows } = await pool.query<{ pages_left: number }>(
    'SELECT pages_left FROM accounts WHERE id = $1',
    [accountId],
  );
  return rows[0]?.pages_left ?? 0;
}

export async function grantPages(accountId: string, pages: number, plan: string): Promise<number> {
  if (!pool) {
    const current = memory.accounts.get(accountId)?.pagesLeft ?? 0;
    memory.accounts.set(accountId, { pagesLeft: current + pages, plan });
    return current + pages;
  }
  const { rows } = await pool.query<{ pages_left: number }>(
    `INSERT INTO accounts (id, pages_left, plan, updated_at)
     VALUES ($1, $2, $3, now())
     ON CONFLICT (id) DO UPDATE
       SET pages_left = accounts.pages_left + EXCLUDED.pages_left,
           plan = EXCLUDED.plan,
           updated_at = now()
     RETURNING pages_left`,
    [accountId, pages, plan],
  );
  return rows[0].pages_left;
}

export class QuotaError extends Error {
  constructor(readonly code: 'NO_PAGES' | 'GLOBAL_CAP', message: string) {
    super(message);
  }
}

/**
 * Takes one page from an account, or refuses.
 *
 * Decremented *before* the Gemini call, not after. A crash between the call and the
 * bookkeeping should cost the user a page, not cost you an unmetered endpoint —
 * and the single UPDATE with a `pages_left > 0` guard means two requests racing
 * cannot both win.
 */
export async function consumePages(accountId: string, pages: number): Promise<number> {
  const globalToday = await globalPagesToday();
  if (globalToday + pages > config.maxPagesPerDayGlobal) {
    throw new QuotaError('GLOBAL_CAP', 'Cloud Read is at capacity today. Try again tomorrow.');
  }

  if (!pool) {
    const account = memory.accounts.get(accountId);
    if (!account || account.pagesLeft < pages) {
      throw new QuotaError('NO_PAGES', 'Not enough Cloud Read pages left.');
    }
    account.pagesLeft -= pages;
    await recordGlobalPages(pages);
    return account.pagesLeft;
  }

  // One guarded UPDATE, not a read followed by a write. Two requests arriving together
  // must not both see the same balance and both succeed — the `pages_left >= $2` clause
  // is what makes the loser return no rows instead of overdrawing the account.
  const { rows } = await pool.query<{ pages_left: number }>(
    `UPDATE accounts SET pages_left = pages_left - $2, updated_at = now()
     WHERE id = $1 AND pages_left >= $2
     RETURNING pages_left`,
    [accountId, pages],
  );
  if (rows.length === 0) throw new QuotaError('NO_PAGES', 'Not enough Cloud Read pages left.');

  await recordGlobalPages(pages);
  return rows[0].pages_left;
}

/** Gives pages back when the work failed through no fault of the user. */
export async function refundPages(accountId: string, pages: number): Promise<void> {
  if (pages <= 0) return;
  if (!pool) {
    const account = memory.accounts.get(accountId);
    if (account) account.pagesLeft += pages;
    return;
  }
  await pool.query('UPDATE accounts SET pages_left = pages_left + $2 WHERE id = $1', [
    accountId,
    pages,
  ]);
}
