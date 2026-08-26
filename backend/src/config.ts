/**
 * Everything the service needs from its environment, read once and checked once.
 *
 * Deliberately loud about what is missing. A backend that boots happily without a
 * Gemini key and only fails on the first real request turns a five-second
 * misconfiguration into a support ticket from a paying user.
 */

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(
      `${name} is not set. Add it in Railway → Variables. See backend/.env.example.`,
    );
  }
  return value;
}

function optional(name: string, fallback: string): string {
  return process.env[name]?.trim() || fallback;
}

export const config = {
  port: Number(process.env.PORT ?? 8080),

  geminiApiKey: required('GEMINI_API_KEY'),

  /** Cheapest model that reads images. Overridable so a price change is a variable, not a deploy. */
  geminiModel: optional('GEMINI_MODEL', 'gemini-3.5-flash-lite'),

  jwtSecret: required('JWT_SECRET'),

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

/** How long a session token is good for. Short, because it is the key to spending money. */
export const TOKEN_TTL_SECONDS = 60 * 60 * 6;
