import { GoogleAuth } from 'google-auth-library';
import { config } from './config.js';

/**
 * Checking with Google that a purchase actually happened.
 *
 * The app already tracks entitlement locally, and that is fine for hiding a theme or
 * a chat persona — the worst case is someone unlocks a colour. It is not fine for
 * anything that spends money, because the cached flag is a boolean in DataStore that
 * anyone with a rooted phone can flip. Anything that costs you per use has to be
 * verified against Google, here, where the user cannot reach it.
 */

/**
 * The allowance everyone gets without paying.
 *
 * Granted against the hashed device id rather than a purchase, so there is nothing to
 * verify with Google — which also means it is the one entitlement a determined person can
 * mint more of, by factory-resetting or spoofing the id. That is a deliberate trade for
 * having no sign-up wall in front of the best feature, and the reason the global daily
 * ceiling exists: the worst case is bounded spend, not unbounded.
 *
 * 120 pages is roughly two books and a few loose pages, at a cost to serve of a few pence
 * a month. Cheap enough that ads cover it, big enough to finish something you care about —
 * which is what actually converts, far more than a taste that runs out mid-chapter.
 */
export const FREE_TIER_PRODUCT = 'snapaie_free_monthly';
export const FREE_TIER_PAGES = 120;

/** What each product grants. The single source of truth for entitlements. */
export const PRODUCTS: Record<string, { pages: number; plan: string; recurring: boolean }> = {
  snapaie_starter_monthly: { pages: 1_500, plan: 'starter', recurring: true },
  snapaie_starter_yearly: { pages: 1_500, plan: 'starter', recurring: true },
  snapaie_pro_monthly: { pages: 4_000, plan: 'pro', recurring: true },
  snapaie_pro_yearly: { pages: 4_000, plan: 'pro', recurring: true },
  snapaie_credits_600: { pages: 600, plan: 'credits', recurring: false },
  snapaie_credits_1400: { pages: 1_400, plan: 'credits', recurring: false },
  snapaie_credits_3200: { pages: 3_200, plan: 'credits', recurring: false },
  snapaie_cloud_monthly: { pages: 500, plan: 'cloud_monthly', recurring: true },
  snapaie_cloud_yearly: { pages: 500, plan: 'cloud_yearly', recurring: true },
  snapaie_credits_100: { pages: 100, plan: 'credits', recurring: false },
  snapaie_credits_500: { pages: 500, plan: 'credits', recurring: false },
};

export const PLAY_NOT_CONFIGURED = 'PLAY_NOT_CONFIGURED';

let auth: GoogleAuth | null = null;

function client(): GoogleAuth {
  if (!config.playServiceAccountJson) {
    throw new Error(PLAY_NOT_CONFIGURED);
  }
  if (!auth) {
    auth = new GoogleAuth({
      credentials: JSON.parse(config.playServiceAccountJson),
      scopes: ['https://www.googleapis.com/auth/androidpublisher'],
    });
  }
  return auth;
}

export interface VerifiedPurchase {
  accountId: string;
  productId: string;
  pages: number;
  plan: string;
}

/**
 * Verifies a purchase token with the Play Developer API.
 *
 * Returns null when Google says the purchase is not valid — expired, refunded,
 * cancelled, or simply never existed. Throws only when *we* are misconfigured, so a
 * caller can tell "this user did not pay" apart from "we cannot currently check".
 */
export async function verifyPurchase(
  productId: string,
  purchaseToken: string,
): Promise<VerifiedPurchase | null> {
  const product = PRODUCTS[productId];
  if (!product) return null;

  const google = client();
  const token = await google.getAccessToken();
  const pkg = encodeURIComponent(config.playPackageName);
  const sku = encodeURIComponent(productId);
  const purchase = encodeURIComponent(purchaseToken);

  const url = product.recurring
    ? `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/purchases/subscriptions/${sku}/tokens/${purchase}`
    : `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${pkg}/purchases/products/${sku}/tokens/${purchase}`;

  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) return null;

  const body = (await response.json()) as Record<string, unknown>;

  if (product.recurring) {
    // A subscription is only good while it is paid for. expiryTimeMillis in the past
    // means cancelled, lapsed or refunded — all of which must stop costing us money.
    const expiry = Number(body.expiryTimeMillis ?? 0);
    if (!Number.isFinite(expiry) || expiry <= Date.now()) return null;
  } else if (Number(body.purchaseState ?? 1) !== 0) {
    // 0 is purchased. 1 is cancelled, 2 is pending.
    return null;
  }

  /*
   * The account identity.
   *
   * obfuscatedExternalAccountId is set by the app at purchase time and is stable for
   * that user. Falling back to the purchase token means one device's balance rather
   * than one person's — worse, but never someone else's, which is the property that
   * actually matters.
   */
  const accountId =
    (body.obfuscatedExternalAccountId as string | undefined) ?? `token:${purchaseToken.slice(0, 32)}`;

  return { accountId, productId, pages: product.pages, plan: product.plan };
}
