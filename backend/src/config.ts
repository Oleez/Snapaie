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

  /** Cheapest model that reads images. Overridable so a price change is a variable, not a deploy. */
  geminiModel: optional('GEMINI_MODEL', 'gemini-3.5-flash-lite'),

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
