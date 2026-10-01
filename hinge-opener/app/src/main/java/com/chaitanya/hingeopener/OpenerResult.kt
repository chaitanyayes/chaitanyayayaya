package com.chaitanya.hingeopener

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Reply(val text: String, val target: String)

data class OpenerResult(
    val best: Reply,
    val reason: String,
    val runnerUps: List<Reply>,
)

object ResultParser {

    /** Parses the model's JSON text. Tolerates code fences and stray prose around the object. */
    fun parse(raw: String): OpenerResult {
        val json = extractJsonObject(raw)
            ?: throw ParseException("Claude didn't return JSON. Try again.")
        val obj = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ParseException("Couldn't read Claude's answer (bad JSON). Try again.")
        }

        val bestText = obj.optString("best_reply").trim()
        if (bestText.isEmpty()) throw ParseException("Claude's answer had no best_reply. Try again.")

        val runnerUps = mutableListOf<Reply>()
        val arr: JSONArray? = obj.optJSONArray("runner_ups")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val text = item.optString("reply").trim()
                if (text.isNotEmpty()) runnerUps += Reply(text, prettyTarget(item.optString("target")))
            }
        }

        return OpenerResult(
            best = Reply(bestText, prettyTarget(obj.optString("target"))),
            reason = obj.optString("reason").trim(),
            runnerUps = runnerUps,
        )
    }

    fun extractJsonObject(raw: String): String? {
        var s = raw.trim()
        // Strip ``` or ```json fences.
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "")
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
            s = s.trim()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1)
    }

    /** "photo 2" -> "Photo 2", "prompt: My simple pleasures" -> "Prompt: My simple pleasures". */
    fun prettyTarget(t: String): String {
        val s = t.trim()
        if (s.isEmpty()) return ""
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    class ParseException(message: String) : Exception(message)
}
