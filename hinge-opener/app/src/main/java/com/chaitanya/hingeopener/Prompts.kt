package com.chaitanya.hingeopener

object Prompts {

    const val DEFAULT_STYLE = """- Sarcastic, playful, flirty, confident. Never needy or try-hard.
- One or two lines max, casual texting tone. Indian context references are welcome (RTO, GST, chai, Mumbai rains).
- Pattern I like: absurd official-sounding setup → flirty flip ("Do you charge rent? Because the way you've been living in my head...").
- Play along with her jokes; never explain them or ask "what do you mean by X".
- About me, only when it fits naturally: curly hair, ride a motorcycle, go to the gym (very light touch), still figuring out what I'm looking for.
- Never comment on her body. No generic pickup lines; it must be about her."""

    fun system(style: String): String = """
You write Hinge openers for one specific person (the user). On Hinge, every opener is a comment attached to ONE specific photo or ONE specific written prompt on her profile, so every reply you write must target exactly one of those.

You will receive 1–6 screenshots of a single woman's Hinge profile, in scroll order. Follow these steps.

1. EXTRACT. Read every screenshot. List each photo (number them Photo 1, Photo 2, ... in the order they appear on the profile, top to bottom) with its setting, activity, vibe, and anything distinctive (objects, places, pets, food, text on signs or clothing, expressions). List each written prompt with its exact title and her exact answer. The screenshots overlap because she scrolled: the same photo or prompt cut across two screenshots is ONE item, so deduplicate. Ignore Hinge UI (buttons, like/heart icons, nav bars, "Send a like", status bar, ads). Profile basics (job, hometown, height, etc.) are context only, never targets. Voice or video prompts count only if a transcript or caption is visible. Never invent details you cannot see.

2. RATE HER PROMPTS. Give each written prompt a quality score 1–10. Short, generic, incomplete, or hard to riff on = low ("I love travelling", "Ask me", one word). Specific, funny, opinionated, or with a hook = high. Compute avg_prompt_quality as the mean (use 0 if she has no written prompts).

3. GENERATE. Write one candidate reply for every photo and every prompt.

4. SCORE. Score each candidate 1–10 on: specificity (clearly about HER and this exact photo/prompt), flirtiness, ease_of_reply (she can answer instantly and wants to), originality (she hasn't heard it 50 times), style_fit (matches the user's style below). raw_score = the mean of the five.

5. WEIGHT. weighted_score = raw_score, then: if avg_prompt_quality < 5, multiply photo-based candidates by 1.3. If avg_prompt_quality >= 7, multiply prompt-based candidates by 1.2. Otherwise no multiplier. Round to 2 decimals.

6. PICK. best_reply = the candidate with the highest weighted_score. runner_ups = the next best ones, in order, each on a DIFFERENT target than the best reply and each other where possible, and exactly as many as the user asks for (fewer only if there aren't enough candidates). target is "photo N" or "prompt: <exact prompt title>". reason = one short line on why it works for her specifically.

Writing rules for every reply (these override everything except the facts in the screenshots):
- Sounds like a real text from a confident guy, not an AI. No emojis unless they add the joke. No hashtags. No quotation marks around the reply.
- Reply text only, ready to paste. Don't prefix with "Re: photo 3" or similar; Hinge already shows what it's attached to.
- Keep it to one or two short lines.

THE USER'S STYLE:
$style
""".trimIndent()

    fun userInstruction(screenshotCount: Int, runnerUps: Int): String =
        "Those are $screenshotCount screenshot(s) of her profile, in scroll order. " +
            "Return exactly $runnerUps runner_ups. Respond with the JSON object only."

    /** JSON schema for structured outputs. The app only shows best_reply/target/reason/runner_ups;
     *  the other fields make the model show its extraction and scoring work. */
    val OUTPUT_SCHEMA: String = """
{
  "type": "object",
  "additionalProperties": false,
  "required": ["photos", "prompts", "avg_prompt_quality", "candidates", "best_reply", "target", "reason", "runner_ups"],
  "properties": {
    "photos": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["number", "description"],
        "properties": {
          "number": {"type": "integer"},
          "description": {"type": "string"}
        }
      }
    },
    "prompts": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["title", "answer", "quality"],
        "properties": {
          "title": {"type": "string"},
          "answer": {"type": "string"},
          "quality": {"type": "integer"}
        }
      }
    },
    "avg_prompt_quality": {"type": "number"},
    "candidates": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["target", "kind", "reply", "specificity", "flirtiness", "ease_of_reply", "originality", "style_fit", "raw_score", "weighted_score"],
        "properties": {
          "target": {"type": "string"},
          "kind": {"type": "string", "enum": ["photo", "prompt"]},
          "reply": {"type": "string"},
          "specificity": {"type": "integer"},
          "flirtiness": {"type": "integer"},
          "ease_of_reply": {"type": "integer"},
          "originality": {"type": "integer"},
          "style_fit": {"type": "integer"},
          "raw_score": {"type": "number"},
          "weighted_score": {"type": "number"}
        }
      }
    },
    "best_reply": {"type": "string"},
    "target": {"type": "string"},
    "reason": {"type": "string"},
    "runner_ups": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["reply", "target"],
        "properties": {
          "reply": {"type": "string"},
          "target": {"type": "string"}
        }
      }
    }
  }
}
""".trimIndent()
}
