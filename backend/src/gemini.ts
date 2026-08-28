import { GoogleGenAI } from '@google/genai';
import { config } from './config.js';

const ai = new GoogleGenAI({ apiKey: config.geminiApiKey });

/**
 * Openings a model uses when it describes a page instead of transcribing it.
 *
 * Mirrors `Transcription.kt` in the app on purpose. Asked to transcribe, a model
 * sometimes answers "This appears to be a handwritten note about…", and accepting
 * that would replace the document with a description of itself — after which nothing
 * downstream can tell, because a description condenses just as happily as a page.
 */
const DESCRIPTION_OPENINGS = [
  'this appears to be', 'this image shows', 'the image shows', 'this is a page',
  'this is an image', 'the page contains', 'here is the text', "here's the text",
  'sure, here', 'certainly, here', 'i can see', "i'm unable", 'i am unable',
  'i cannot', "i can't", 'unfortunately', 'the handwriting', 'the document shows',
];

const TRANSCRIBE_PROMPT = `Read the attached page and write out exactly what it says.

This is a transcription, not a summary. Copy the words that are on the page, in the order
they appear, including headings, dialogue, numbers and dates.

The page is likely handwritten. Where the writing is unclear, choose the reading that makes
sense in context rather than guessing letter by letter.

Rules:
- Do not shorten, explain, correct or comment on anything.
- Do not add a title, a preamble, or a note about what you are doing.
- Keep paragraph breaks where the page has them.
- If part of the page is genuinely illegible, write [unclear] in its place and carry on.

Write only the text of the page.`;

function cleanTranscription(raw: string): string {
  let text = raw.trim();
  if (!text) return '';

  for (const prefix of ['here is the text:', "here's the text:", 'transcription:', 'text:']) {
    if (text.toLowerCase().startsWith(prefix)) {
      text = text.slice(prefix.length).trim();
    }
  }
  if (!text) return '';

  const opening = text.slice(0, 90).toLowerCase();
  if (DESCRIPTION_OPENINGS.some((o) => opening.startsWith(o))) return '';

  // A page that is nothing but [unclear] markers was not read either.
  if (text.replace(/\[unclear\]/gi, '').trim().length < 12) return '';

  return text;
}

/** Transcribes one page image. Returns '' when the reply was not a transcription. */
export async function transcribe(imageBase64: string, mimeType: string): Promise<string> {
  const response = await ai.models.generateContent({
    model: config.geminiTranscribeModel,
    contents: [
      {
        role: 'user',
        parts: [
          { inlineData: { mimeType, data: imageBase64 } },
          { text: TRANSCRIBE_PROMPT },
        ],
      },
    ],
    config: {
      // Transcription is copying, not composing. Sampling a distribution here only
      // invents variation in text that has exactly one correct answer.
      temperature: 0,
      maxOutputTokens: 4096,
    },
  });
  return cleanTranscription(response.text ?? '');
}

/**
 * Chooses which sentences to keep, exactly as the offline path does.
 *
 * The contract is the same on purpose: numbered sentences in, bare indices out, and
 * the app reassembles from its own originals. Nothing this returns is ever shown to
 * a reader, so a model that rambles cannot put words in the author's mouth — the
 * worst it can do is choose badly, and the app can tell that it did.
 */
export async function chooseSentences(
  numberedSentences: string,
  targetWords: number,
): Promise<number[]> {
  const response = await ai.models.generateContent({
    model: config.geminiCondenseModel,
    contents: `You are abridging a document. You do not rewrite it — you decide what to cut.

Below are its numbered sentences. Choose which to KEEP so the result runs to roughly
${targetWords} words. The kept sentences are joined exactly as written, so the result stays
in the author's own words.

Keep sentences carrying an event, a decision, a revelation or a change; the first sentence;
and names, places and figures referred to later. Cut description that repeats what is
established, second and third examples of one idea, and digressions.

Reply with only the numbers you are keeping, separated by commas. No words, no explanation.

Example reply: 0, 1, 4, 5, 8

Sentences:
${numberedSentences}`,
    config: { temperature: 0, maxOutputTokens: 2048 },
  });

  // Tolerant on purpose: replies arrive as "1, 3, 4", as "[1,3,4]", or with prose
  // around them. Anything unparseable yields nothing and the caller keeps its own answer.
  return [...(response.text ?? '').matchAll(/\d+/g)]
    .map((m) => Number(m[0]))
    .filter((n) => Number.isFinite(n));
}

/** One passage of a book, and what came back for it. */
export interface Passage {
  id: number;
  text: string;
  targetWords: number;
}

export interface CondensedPassage {
  id: number;
  prose: string;
}

/**
 * Condenses several passages in one request.
 *
 * The phone walks a book one passage at a time because it can only hold one at a time.
 * Copying that granularity over HTTP would make the cloud path slow for no reason —
 * a five-hundred-page book is about 150 passages, and 150 round trips is minutes of
 * latency before any work happens. Ten passages a request turns that into fifteen.
 *
 * Passages are numbered and the reply is matched back by number rather than by
 * position, because a model asked for ten answers occasionally returns nine. Losing
 * one passage should cost that passage, not silently shift every one after it — which
 * is the failure that would put chapter four's text under chapter three's heading.
 */
export async function condensePassages(
  passages: Passage[],
  ledger: string,
  style: string,
): Promise<CondensedPassage[]> {
  const blocks = passages
    .map((p) => `### PASSAGE ${p.id} (target ${p.targetWords} words)
${p.text}`)
    .join('\n\n');

  const response = await ai.models.generateContent({
    model: config.geminiCondenseModel,
    contents: `You are condensing a book, passage by passage, in order.

${ledger ? `What has happened so far:\n${ledger}\n\n` : ''}Retell each passage below shorter, as continuous prose in the book's own voice. Keep
every event, name and decision. Do not summarise from outside the story, do not add
headings, and do not comment on what you are doing.

${style}

Return one block per passage, in this exact form and nothing else:

<<<PASSAGE id>>>
the condensed text
<<<END>>>

${blocks}`,
    config: { temperature: 0.3, maxOutputTokens: 8192 },
  });

  return parsePassages(response.text ?? '');
}

/** Pulls the delimited blocks back out, keyed by the id the model was given. */
function parsePassages(raw: string): CondensedPassage[] {
  const out: CondensedPassage[] = [];
  const pattern = /<<<PASSAGE\s+(\d+)>>>([\s\S]*?)<<<END>>>/g;
  for (const match of raw.matchAll(pattern)) {
    const id = Number(match[1]);
    const prose = match[2].trim();
    // A block that came back empty is a passage the model skipped. Dropping it here
    // lets the caller fall back for that one rather than write a blank chapter.
    if (Number.isFinite(id) && prose.length > 0) out.push({ id, prose });
  }
  return out;
}
