package com.chaitanya.hingeopener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultParserTest {

    private val json = """
        {"photos": [], "prompts": [], "avg_prompt_quality": 4, "candidates": [],
         "best_reply": "Is this the RTO? Because I'd wait in line for you",
         "target": "photo 2",
         "reason": "Plays on the queue in her photo",
         "runner_ups": [
            {"reply": "Chai or coffee, choose wisely", "target": "prompt: My simple pleasures"},
            {"reply": "", "target": "photo 1"}
         ]}
    """.trimIndent()

    @Test
    fun parsesPlainJson() {
        val r = ResultParser.parse(json)
        assertEquals("Is this the RTO? Because I'd wait in line for you", r.best.text)
        assertEquals("Photo 2", r.best.target)
        assertEquals("Plays on the queue in her photo", r.reason)
        assertEquals(1, r.runnerUps.size) // blank reply dropped
        assertEquals("Prompt: My simple pleasures", r.runnerUps[0].target)
    }

    @Test
    fun stripsCodeFences() {
        val r = ResultParser.parse("```json\n$json\n```")
        assertEquals("Photo 2", r.best.target)
    }

    @Test
    fun ignoresSurroundingProse() {
        val r = ResultParser.parse("Here you go:\n$json\nGood luck!")
        assertEquals("Photo 2", r.best.target)
    }

    @Test
    fun rejectsNonJson() {
        val e = runCatching { ResultParser.parse("Sorry, I can't help with that.") }.exceptionOrNull()
        assertTrue(e is ResultParser.ParseException)
    }

    @Test
    fun rejectsMissingBestReply() {
        val e = runCatching { ResultParser.parse("""{"target": "photo 1"}""") }.exceptionOrNull()
        assertTrue(e is ResultParser.ParseException)
    }
}
