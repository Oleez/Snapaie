import { SignJWT, jwtVerify } from 'jose';
import type { NextFunction, Request, Response } from 'express';
import { config, TOKEN_TTL_SECONDS } from './config.js';

const secret = new TextEncoder().encode(config.jwtSecret);

export async function issueToken(accountId: string, plan: string): Promise<string> {
  return new SignJWT({ plan })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(accountId)
    .setIssuedAt()
    .setExpirationTime(`${TOKEN_TTL_SECONDS}s`)
    .sign(secret);
}

export interface AuthedRequest extends Request {
  accountId?: string;
}

/**
 * Rejects anything without a valid, unexpired token.
 *
 * Deliberately the first thing every paid route does. An endpoint that calls Gemini
 * before checking who is asking is an endpoint that spends money for strangers.
 */
export async function requireAuth(
  req: AuthedRequest,
  res: Response,
  next: NextFunction,
): Promise<void> {
  const header = req.header('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) {
    res.status(401).json({ error: 'unauthorized' });
    return;
  }
  try {
    const { payload } = await jwtVerify(token, secret);
    req.accountId = payload.sub;
    next();
  } catch {
    res.status(401).json({ error: 'unauthorized' });
  }
}
