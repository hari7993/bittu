package app.bittu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Bittu's long-term memory. It starts empty and grows as the owner talks to Bittu. Nothing is ever thrown away:
 *
 *  - about.md    what Bittu knows about the owner, kept short. Always in Bittu's mind.
 *  - notes.txt   things learned recently, one per line. Always in Bittu's mind until they are folded into about.md.
 *  - days/DATE.md  a diary: what the owner asked and did each day. Never trimmed.
 *  - archive.txt every note ever learned, in its original words. Never trimmed.
 *
 * Only the first two are sent with every question, so they are kept small: when the notes grow past a limit they are
 * folded into about.md by the AI (the original lines stay in archive.txt). The diary and the archive are searched
 * with recall() when the owner asks about something older. That is how the memory has no limit while each answer
 * stays fast.
 */
object Memory {
    private const val NOTES_LIMIT = 2500   // characters of recent notes kept in mind before folding
    private const val ABOUT_LIMIT = 3000   // characters of the owner's profile kept in mind
    private val worker = Executors.newSingleThreadExecutor()
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    private fun dir(c: Context) = File(c.filesDir, "memory").apply { mkdirs() }
    private fun about(c: Context) = File(dir(c), "about.md")
    private fun notes(c: Context) = File(dir(c), "notes.txt")
    private fun archive(c: Context) = File(dir(c), "archive.txt")
    private fun days(c: Context) = File(dir(c), "days").apply { mkdirs() }
    private fun read(f: File) = if (f.exists()) f.readText().trim() else ""
    private fun today() = day.format(Date())

    /** True until Bittu has learned its first thing about the owner. */
    fun isNew(c: Context) = read(about(c)).isEmpty() && read(notes(c)).isEmpty()

    /** Saves one thing about the owner, in a full sentence. */
    @Synchronized fun remember(c: Context, fact: String): String {
        val line = fact.replace('\n', ' ').trim()
        if (line.length < 3) return "Nothing to remember."
        if (read(notes(c)).lines().any { it.substringAfter("] ").equals(line, true) }) return "Already remembered."
        notes(c).appendText("[${today()}] $line\n")
        archive(c).appendText("[${today()}] $line\n")
        if (read(notes(c)).length > NOTES_LIMIT) worker.execute { runCatching { fold(c) }.onFailure { android.util.Log.w("BittuMemory", "fold failed: $it") } }
        return "Remembered."
    }

    /** Removes what matches from Bittu's mind. The owner asked for it, so it also goes from the archive and the diary. */
    @Synchronized fun forget(c: Context, what: String): String {
        val words = what.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
        if (words.isEmpty()) return "Tell me what to forget."
        fun hit(line: String) = line.lowercase().let { l -> words.all { l.contains(it) } }
        var gone = 0
        for (f in listOf(notes(c), archive(c)) + (days(c).listFiles()?.toList() ?: emptyList())) {
            if (!f.exists()) continue
            val lines = f.readLines(); val keep = lines.filterNot(::hit)
            gone += lines.size - keep.size
            if (keep.size != lines.size) f.writeText(keep.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" })
        }
        val profile = read(about(c))
        if (profile.isNotEmpty() && hit(profile.replace('\n', ' '))) {
            val fresh = Agent.plain(c, "You keep a short profile of a person. Rewrite it with everything about the named subject removed. Keep the rest unchanged. Reply with the profile only.", "Subject to remove: $what\n\nProfile:\n$profile", 1200)
            if (fresh.isNotBlank()) { about(c).writeText(fresh.trim() + "\n"); gone++ }
        }
        return if (gone == 0) "I had nothing saved about that." else "Forgotten."
    }

    /** Looks through everything ever remembered, and the diary, for the words asked about. Newest first. */
    fun recall(c: Context, query: String): String {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.distinct()
        if (words.isEmpty()) return "Ask about something specific."
        val found = ArrayList<Pair<Int, String>>()
        fun scan(f: File, prefix: String) { if (f.exists()) f.forEachLine { line -> val l = line.lowercase(); val score = words.count { l.contains(it) }; if (score > 0) found.add(score to (prefix + line.trim())) } }
        days(c).listFiles()?.sortedByDescending { it.name }?.forEach { scan(it, "[${it.nameWithoutExtension}] ") }
        scan(archive(c), "")
        // A date asked for directly ("yesterday", "2026-11-20") returns that day's diary.
        val asked = Regex("\\d{4}-\\d{2}-\\d{2}").find(query)?.value ?: when { "yesterday" in query.lowercase() -> day.format(Date(System.currentTimeMillis() - 86_400_000)); "today" in query.lowercase() -> today(); else -> null }
        val whole = asked?.let { read(File(days(c), "$it.md")) }.orEmpty()
        val best = found.sortedByDescending { it.first }.take(14).joinToString("\n") { it.second }
        return listOf(if (whole.isNotEmpty()) "Diary for $asked:\n${whole.takeLast(1800)}" else "", best).filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { "Nothing in memory about that." }.take(4000)
    }

    /** One line in today's diary. */
    @Synchronized fun diary(c: Context, line: String) { if (line.isNotBlank()) File(days(c), "${today()}.md").appendText("${clock.format(Date())} ${line.replace('\n', ' ').trim()}\n") }

    /** What Bittu carries in mind for every answer. */
    fun inMind(c: Context): String {
        val profile = read(about(c)); val recent = read(notes(c))
        if (profile.isEmpty() && recent.isEmpty()) return "YOUR MEMORY: empty. You have only just met the owner and know nothing about them yet. Be warm and a little curious. When it fits the moment, ask one small friendly question about them (never more than one, never while doing a task), and remember what they tell you."
        val todayLog = read(File(days(c), "${today()}.md")).takeLast(700)
        val yesterday = read(File(days(c), day.format(Date(System.currentTimeMillis() - 86_400_000)) + ".md")).takeLast(400)
        return buildString {
            append("YOUR MEMORY (what you know about the owner from earlier days; use it naturally, never recite it):\n")
            if (profile.isNotEmpty()) append(profile.take(ABOUT_LIMIT)).append('\n')
            if (recent.isNotEmpty()) append("Learned recently:\n").append(recent.takeLast(NOTES_LIMIT)).append('\n')
            if (todayLog.isNotEmpty()) append("Earlier today:\n").append(todayLog).append('\n')
            if (yesterday.isNotEmpty()) append("Yesterday:\n").append(yesterday).append('\n')
            append("Older days and details are not shown here. Use recall to look them up before saying you do not know.")
        }
    }

    /**
     * After a conversation: picks out what is worth keeping about the owner and writes one diary line.
     * Runs in the background and costs one short AI call.
     */
    fun reflect(c: Context, transcript: String) = worker.execute {
        runCatching {
            if (!transcript.contains("Owner:")) return@runCatching
            val out = Agent.plain(c,
                "You are the memory of Bittu, a phone assistant that is becoming its owner's long-term friend. Read the conversation and reply with JSON only, no other text: " +
                    "{\"facts\": [..], \"day\": \"..\"}. " +
                    "\"facts\": lasting things learned about the owner that a good friend would remember: who they are, family and friends and what they call them, work or study, routines, likes and dislikes, plans, worries, health, places, how they like Bittu to behave. Each one a short full sentence starting with \"The owner\" or the person's name. Leave out one-off commands, anything already listed under KNOWN, and guesses. Usually zero to three facts; an empty list is normal. " +
                    "\"day\": one short sentence saying what the owner asked for or did in this conversation, or \"\" if it was nothing worth a diary line.",
                "KNOWN:\n${read(about(c)).take(2000)}\n${read(notes(c)).takeLast(1500)}\n\nCONVERSATION:\n${transcript.takeLast(7000)}", 500)
            val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0), out.lastIndexOf('}') + 1))
            val facts = json.optJSONArray("facts") ?: JSONArray()
            for (i in 0 until facts.length()) remember(c, facts.optString(i))
            diary(c, json.optString("day"))
            android.util.Log.i("BittuMemory", "reflected: ${facts.length()} facts, diary line ${json.optString("day").isNotBlank()}")
        }.onFailure { android.util.Log.w("BittuMemory", "reflect failed: $it") }
    }

    /** Folds the recent notes into the short profile, so the part carried in mind never grows past its limit. */
    @Synchronized private fun fold(c: Context) {
        val recent = read(notes(c)); if (recent.isEmpty()) return
        val fresh = Agent.plain(c,
            "You keep the profile a phone assistant holds about its owner, its long-term friend. Merge the new notes into the profile. Keep every lasting fact: names, relations, routines, likes, dislikes, plans, important dates, how they want to be treated. When a new note contradicts the profile, the new note wins. Drop duplicates and things that have clearly passed. Write short plain lines grouped under these headings: Who they are, People, Daily life, Likes and dislikes, Plans and worries, How to treat them. At most $ABOUT_LIMIT characters. Reply with the profile only.",
            "PROFILE:\n${read(about(c))}\n\nNEW NOTES:\n$recent", 1500)
        if (fresh.length < 40) return // the AI did not answer properly: keep the notes as they are and try again next time
        about(c).writeText(fresh.trim().take(ABOUT_LIMIT + 400) + "\n")
        notes(c).writeText("")
        android.util.Log.i("BittuMemory", "folded ${recent.length} characters of notes into the profile")
    }

    /** For the app's memory page. */
    fun show(c: Context): JSONObject = JSONObject().put("about", read(about(c))).put("notes", JSONArray(read(notes(c)).lines().filter { it.isNotBlank() }))
        .put("days", days(c).listFiles()?.size ?: 0).put("total", read(archive(c)).lines().count { it.isNotBlank() })
        .put("today", read(File(days(c), "${today()}.md")).takeLast(1200))

    /** The owner asked to erase everything Bittu knows about them. */
    @Synchronized fun clear(c: Context) { dir(c).deleteRecursively(); Agent.reset() }
}
