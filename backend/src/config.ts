/**
 * Everything the service needs from its environment, read once and checked once.
 *
 * This used to throw on a missing variable, on the reasoning that refusing to start beats
 * starting broken. That reasoning is sound for a service someone is watching, and it was
 * wrong here. A process that exits on boot is restarted, exits again, and all the operator
 * is shown is "Healthcheck failure" — the actual message naming the missing variable is
 * buried in deploy logs, repeating once a second. It read as a build failure twice.
 *
 * So the service starts, and says what is wrong somewhere it will be seen. /healthz reports
 * exactly which variables are missing, and every route that would need one refuses with the
 * same message. Nothing can be spent by mistake: without a key there is no call to make.
 */

/** A variable that must be present before the thing needing it can work. */
function requiredOrMissing(name: string, missing: string[]): string {
  const value = process.env[name]?.trim();
  if (!value) {
    missing.push(name);
    return '';
  }
  return value;
}

function optional(name: string, fallback: string): string {
  return process.env[name]?.trim() || fallback;
}

const missing: string[] = [];

export const config = {
  port: Number(process.env.PORT ?? 8080),

  geminiApiKey: requiredOrMissing('GEMINI_API_KEY', missing),

  /**
   * Condensing a book. The cheapest model there is, deliberately.
   *
   * This is where the money goes and nowhere else. A five-hundred-page book is about
   * 200,000 tokens in and 60,000 out, so cost here is set almost entirely by the input
   * price — and 2.5-flash-lite is a third the input price of 3.5-flash-lite and a sixth
   * the output price. Same book: 4.4 cents against 21.
   *
   * The job is also the forgiving one. Choosing which sentences carry a passage is not
   * where a bigger model earns its keep, and the app keeps its own floor underneath: a
   * poor reply is rejected and the passage is condensed on the phone instead.
   */
  geminiCondenseModel: optional('GEMINI_MODEL_CONDENSE', 'gemini-2.5-flash-lite'),

  /**
   * Reading a photograph. A better model, because this is where being wrong is expensive.
   *
   * Handwriting misread is worse than handwriting unread: the mistake is invisible, and
   * everything downstream then condenses it confidently. Nobody sees the original again.
   *
   * It costs almost nothing to be careful here. One page is about a thousand tokens, so
   * even the strongest Flash model is a quarter of a penny — against 4.4 cents for the
   * book it belongs to. Paying three times as much for accuracy on the one step that
   * cannot self-correct is the easiest trade in the service.
   */
  geminiTranscribeModel: optional('GEMINI_MODEL_TRANSCRIBE', 'gemini-3.1-flash-lite'),

  jwtSecret: requiredOrMissing('JWT_SECRET', missing),

  /** Absent means in-memory quota: usable for a smoke test, unsafe to leave in production. */
  databaseUrl: process.env.DATABASE_URL?.trim() || null,

  playServiceAccountJson: process.env.PLAY_SERVICE_ACCOUNT_JSON?.trim() || null,
  playPackageName: optional('PLAY_PACKAGE_NAME', 'com.snapaie.android'),

  /**
   * A ceiling across every account, not per account.
   *
   * Per-account limits protect you from one user. This protects you from a bug —
   * a leaked token, a retry loop in a client you already shipped, a mistake in the
   * entitlement check. It is the number that decides whether a bad night costs
   * pennies or a fortune.
   */
  maxPagesPerDayGlobal: Number(optional('MAX_PAGES_PER_DAY_GLOBAL', '20000')),
} as const;

/**
 * Variables that are absent, in the order they were asked for.
 *
 * Empty means the service is fully configured. Anything else is reported by /healthz and
 * refused by the routes that need it, rather than discovered by a user who paid.
 */
export const missingConfig: readonly string[] = missing;

/** True when every variable needed to actually do work is present. */
export const isFullyConfigured = missing.length === 0;

/** How long a session token is good for. Short, because it is the key to spending money. */
export const TOKEN_TTL_SECONDS = 60 * 60 * 6;
