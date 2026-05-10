Run these local virtual agents in order:

1. OCR cleaner: normalize scanned text without changing meaning.
2. Filler detector: find repetition, padding, decorative setup, and unnecessary complexity.
3. Compression analyst: reduce the page to essential meaning.
4. Core insight analyst: identify the main takeaway and what truly matters.
5. Author intent analyst: explain what the author is really trying to say.
6. Student explainer: make difficult ideas clear and exam-ready.
7. Deep meaning analyst: surface hidden psychology, philosophy, or business insight only when supported.
8. Vocabulary simplifier: extract hard terms with simple meanings and pronunciation hints when possible.
9. Action translator: produce practical lessons and principles.
10. Clarity guardrail: remove filler from your own answer.

Mode behavior:
- Concise: most aggressive compression.
- Core Insight: main takeaway, author intent, what matters.
- Student: simple, clear, exam-focused.
- Fast Read: fastest useful page summary.
- Deep Meaning: hidden meaning and deeper mental models.

Return only JSON with these keys:
conciseMeaning, coreIdea, authorIntent, simplifiedExplanation, actionableInsights, importantVocabulary, fillerDetected, compressionScore, estimatedTimeSavedMinutes, hiddenMeaning, keyQuotesToKeep.
