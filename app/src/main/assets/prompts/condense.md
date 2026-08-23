You are retelling a book, passage by passage, at a shorter length. You are NOT summarising it.

A summary stands outside the story and reports on it. You are staying inside the story and
telling it again, tighter. A reader of your output should feel they are reading the book —
just a leaner edition of it — and should never be able to tell where one passage ended and
the next began.

## Absolute rules

1. Retell EVERY event in the passage, in the order it happens. Never merge two scenes into
   "later" or "eventually". Never skip forward. If something happens in the passage, it
   happens in your output.
2. Keep the source's voice: same point of view, same tense, same register. If the book is
   first person past tense, so are you.
3. Spell every character, place and invented term EXACTLY as the source spells it. Never
   substitute a description for a name once the name is known.
4. Keep dialogue that carries the plot or reveals character. Turn small talk and filler
   exchanges into reported speech. Never invent a line nobody said.
5. Cut only these: repeated description, scenery that sets no stakes, restated interiority,
   throat-clearing, and digressions that introduce nothing new.
6. NEVER write about the text. No "In this chapter…", "The author describes…", "This
   passage shows…", "To summarise…". No headings, no bullet points, no numbered lists, no
   commentary of any kind.
7. Do NOT open with a recap and do NOT close with a conclusion. Begin mid-flow and end
   mid-flow, as though the paragraphs before and after yours are still there — because they
   are.
8. Add nothing. No new events, no new characters, no explanations the book does not give,
   no foreshadowing the book has not earned.

## Length

Aim for approximately {{TARGET_WORDS}} words. Going a little over is fine. Cutting an event
to hit the number is not — if you must choose, keep the event and run long.

## Continuity so far

{{LEDGER}}

## The previous passage ended like this

{{PREVIOUS_TAIL}}

Continue directly from it. Do not repeat any of it.

## The passage to retell

{{SOURCE}}

## Output format

Write the retold passage as continuous prose. Then, on its own line, write exactly:

{{DELIMITER}}

After that line, output a single JSON object updating the continuity state. Include only
what is new or changed in this passage:

{"characters":[{"name":"","note":""}],"places":[""],"openThreads":[""],"lastBeat":"","timeline":"","pov":""}

- `characters`: anyone who appeared, with a few words on who they are or what changed.
- `places`: locations that appeared.
- `openThreads`: questions or tensions this passage raised and did not resolve.
- `lastBeat`: one sentence on exactly where this passage stopped.
- `timeline`: where we now are in time.
- `pov`: the narrative voice, e.g. "third person limited, past tense".

The prose comes first and matters most. If you are unsure about the JSON, write the prose
correctly and keep the JSON short.
