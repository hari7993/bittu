package app.bittu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skills: tasks Bittu has worked out once and can now do at once.
 *
 * The first time the owner asks for something inside an app ("share this reel to Akka"), the AI finds its way step
 * by step: read the screen, tap, read again. That takes many seconds. When it succeeds, the taps and typing that
 * worked are saved here as a skill, with the parts that came from the owner's words (the name, the message) left as
 * blanks. The next time a request has the same shape, Bittu fills the blanks and replays the steps directly, with no
 * AI call. If any step does not find what it expects, the replay stops and the AI takes over, so a changed screen
 * costs time but never a wrong tap chain.
 *
 * One skill: {id, say: "send this reel to {1} on instagram", app: package it starts from ("" = anywhere),
 *             steps: [{tool, args}], ok: times it worked, bad: times it failed}
 */
object Skills {
    private val replayable = setOf("open_app", "tap_on", "type_text", "press_button", "scroll", "send_message", "call_person", "navigate_to", "youtube_search")
    private val harmless = setOf("read_screen", "remember", "recall", "forget", "my_location", "phone_info")
    private val failed = Regex("was found on screen|could not|couldn't|No text box|switched off|There is no app|Nobody called|It failed|not running|does not exist|cannot be|Nothing here", RegexOption.IGNORE_CASE)
    private val gaveUp = Regex("couldn't|could not|wasn't able|unable to|not able to|failed|did not work|didn't work|not found|can't find|cannot find|\\?\\s*$", RegexOption.IGNORE_CASE)

    private class Recording(val said: String, val app: String) { val steps = ArrayList<JSONObject>(); var tainted = false }
    @Volatile private var rec: Recording? = null

    private fun file(c: Context) = File(c.filesDir, "skills.json")
    @Synchronized private fun load(c: Context): JSONArray = runCatching { JSONArray(file(c).readText()) }.getOrDefault(JSONArray())
    @Synchronized private fun save(c: Context, all: JSONArray) { file(c).writeText(all.toString()) }

    /** The owner's words without the wake word and politeness, lower case. */
    private fun plain(text: String) = text.lowercase().trim().trim('.', '!', '?', ',')
        .replace(Regex("^(hey |hi |ok |okay )?(bittu|bitu|beetu)[, ]*"), "").replace(Regex("^(please |can you |could you |will you |just )+"), "").replace(Regex("( please| now| for me)+$"), "").replace(Regex("\\s+"), " ").trim()

    private fun front() = ScreenControl.instance?.front().orEmpty()

    // ---------- recording ----------
    fun begin(c: Context, said: String) { rec = Recording(plain(said), front()) }

    /** Called for every tool the AI uses while a request is being recorded. */
    fun step(name: String, args: JSONObject, result: String) {
        val r = rec ?: return
        when {
            name in harmless -> {}
            name !in replayable -> r.tainted = true // something that depends on the moment (a search, a command): not a fixed recipe
            failed.containsMatchIn(result) -> {}     // a try that did not work is left out; only what worked is kept
            else -> r.steps.add(JSONObject().put("tool", name).put("args", JSONObject(args.toString())))
        }
    }

    /** The request is finished. If it worked and was more than a single step, it becomes a skill. */
    fun end(c: Context, reply: String) {
        val r = rec ?: return
        rec = null
        if (r.tainted || r.steps.size < 2 || r.steps.size > 12 || gaveUp.containsMatchIn(reply) || r.said.length < 6) return
        // Find the parts of the steps that came from the owner's words: those become blanks.
        var say = r.said
        val blanks = ArrayList<String>()
        for (s in r.steps) {
            val a = s.getJSONObject("args")
            for (k in a.keys().asSequence().toList()) {
                val v = (a.opt(k) as? String)?.trim() ?: continue
                val low = v.lowercase()
                if (low.length < 2) continue
                val at = blanks.indexOf(low)
                if (at >= 0) { a.put(k, "{${at + 1}}"); continue }
                if (Regex("(^| )" + Regex.escape(low) + "( |$)").containsMatchIn(say)) {
                    blanks.add(low)
                    say = say.replaceFirst(Regex("(^| )" + Regex.escape(low) + "( |$)"), "$1{${blanks.size}}$2")
                    a.put(k, "{${blanks.size}}")
                }
            }
        }
        if (say.replace(Regex("\\{\\d\\}"), "").trim().split(' ').count { it.isNotBlank() } < 2) return // nothing left to recognise the request by
        val all = load(c)
        val kept = JSONArray()
        for (i in 0 until all.length()) all.getJSONObject(i).let { if (it.optString("say") != say) kept.put(it) }
        kept.put(JSONObject().put("id", "s" + System.currentTimeMillis().toString(36)).put("say", say).put("app", if (r.steps[0].optString("tool") == "open_app") "" else r.app)
            .put("steps", JSONArray(r.steps as Collection<*>)).put("ok", 1).put("bad", 0).put("made", System.currentTimeMillis()))
        save(c, kept)
        android.util.Log.i("BittuSkills", "learned: \"$say\" in ${r.steps.size} steps")
    }

    fun cancel() { rec = null }

    // ---------- replay ----------
    /**
     * Does the request with a saved skill, if one fits. Returns what to say ("" for nothing), or null when no skill
     * fits or a step failed, in which case the AI should handle the request as usual.
     */
    fun tryReplay(c: Context, said: String): String? {
        val text = plain(said)
        if (text.length < 6) return null
        val all = load(c)
        for (i in 0 until all.length()) {
            val skill = all.getJSONObject(i)
            val pattern = "^" + Regex.escape(skill.optString("say")).replace(Regex("\\{(\\d)\\}"), "\\\\E(.+?)\\\\Q") + "$"
            val m = runCatching { Regex(pattern).find(text) }.getOrNull() ?: continue
            if (skill.optString("app").isNotEmpty() && skill.optString("app") != front()) continue // it starts from a screen the phone is not on
            val order = Regex("\\{(\\d)\\}").findAll(skill.optString("say")).map { it.groupValues[1].toInt() }.toList()
            val fill = HashMap<Int, String>(); order.forEachIndexed { n, slot -> fill[slot] = m.groupValues[n + 1].trim() }
            val steps = skill.getJSONArray("steps")
            val t0 = System.currentTimeMillis()
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val args = JSONObject(step.getJSONObject("args").toString())
                for (k in args.keys().asSequence().toList()) (args.opt(k) as? String)?.let { v -> Regex("^\\{(\\d)\\}$").find(v)?.let { args.put(k, fill[it.groupValues[1].toInt()] ?: v) } }
                val out = run(c, step.optString("tool"), args)
                if (failed.containsMatchIn(out)) {
                    android.util.Log.i("BittuSkills", "replay of \"${skill.optString("say")}\" stopped at step ${s + 1}: $out")
                    skill.put("bad", skill.optInt("bad") + 1)
                    // A skill that keeps failing is out of date (the app changed): forget it so it is learned afresh.
                    if (skill.optInt("bad") > skill.optInt("ok") + 2) { val kept = JSONArray(); for (j in 0 until all.length()) if (j != i) kept.put(all.getJSONObject(j)); save(c, kept) } else save(c, all)
                    return null
                }
            }
            skill.put("ok", skill.optInt("ok") + 1); save(c, all)
            android.util.Log.i("BittuSkills", "replayed \"${skill.optString("say")}\" in ${System.currentTimeMillis() - t0} ms")
            return "Done."
        }
        return null
    }

    /** One step of a replay. Taps wait for their target to appear, so no fixed pauses are needed between screens. */
    private fun run(c: Context, tool: String, args: JSONObject): String {
        val screen = ScreenControl.instance
        return when (tool) {
            "tap_on" -> {
                if (screen == null) return "Screen control is switched off."
                // Wait for the item to appear. If it does not, it may be further down the list: scroll and look again, a few times.
                var ok = screen.tap(args.optString("words"), 3500)
                var scrolls = 0
                while (!ok && scrolls++ < 4 && screen.scroll(true)) { Thread.sleep(350); ok = screen.tap(args.optString("words"), 700) }
                if (ok) "Tapped." else "Nothing with the words \"${args.optString("words")}\" was found on screen."
            }
            "type_text" -> { if (screen == null) return "Screen control is switched off."; var ok = false; repeat(8) { if (!ok) { ok = screen.type(args.optString("text")); if (!ok) Thread.sleep(300) } }; if (ok) "Typed it." else "No text box is open on screen." }
            "scroll" -> if (screen?.scroll(args.optString("direction") != "up") == true) "Scrolled." else "Nothing here can be scrolled."
            else -> Tools.run(c, tool, args)
        }.also { Thread.sleep(250) }
    }

    // ---------- for the app's Skills page ----------
    fun list(c: Context): String = load(c).toString()
    fun delete(c: Context, id: String) { val all = load(c); val kept = JSONArray(); for (i in 0 until all.length()) all.getJSONObject(i).let { if (it.optString("id") != id) kept.put(it) }; save(c, kept) }
}
