package app.ringdo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Understands everyday phrases (spoken or typed), e.g.
 *  "call the bank tomorrow at 10"
 *  "gym every monday wednesday and friday at 6 am"
 *  "pay rent on the 1st of every month #home"
 *  "take medicine every day at 9 pm until done"
 *  "team call every other thursday at 4:30 pm"
 *  "in 20 minutes check the oven"
 */
object Parser {
    data class Result(
        val title: String,
        val due: Long,
        val rule: Rule,
        val listId: String?,
        val nag: Boolean,
        val hadTime: Boolean,
        val hadDate: Boolean,
    )

    private val dayNames = mapOf(
        "monday" to 1, "mon" to 1, "tuesday" to 2, "tue" to 2, "tues" to 2, "wednesday" to 3, "wed" to 3,
        "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4, "friday" to 5, "fri" to 5,
        "saturday" to 6, "sat" to 6, "sunday" to 7, "sun" to 7,
    )
    private const val DAY = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)s?"
    private val months = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private const val MONTH = "(?:january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty five" to 45, "other" to 2, "second" to 2, "third" to 3,
    )
    private const val NUM = "(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty five|forty)"
    private val nthWords = mapOf("first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4, "last" to -1)

    private fun num(s: String) = s.toIntOrNull() ?: numberWords[s] ?: 1
    private fun dayOf(s: String) = dayNames[s.removeSuffix("s").let { if (it in dayNames) it else s }] ?: dayNames[s] ?: 1
    private fun monthOf(s: String) = months.indexOfFirst { it.startsWith(s.take(3)) } + 1

    fun parse(input: String, now: Long = System.currentTimeMillis(), lists: List<TodoList> = emptyList()): Result {
        // Work on a lowercase copy; keep a mask of consumed characters so the title keeps the user's casing.
        val orig = input.trim().replace(Regex("\\s+"), " ")
        var low = orig.lowercase(Locale.ROOT).replace("a.m.", "am  ").replace("p.m.", "pm  ")
        if (low.length != orig.length) low = low.padEnd(orig.length).take(orig.length)
        val used = BooleanArray(orig.length)
        fun take(re: Regex): MatchResult? {
            val m = re.find(low) ?: return null
            for (i in m.range) used[i] = true
            // also swallow padding left by the "a.m."/"p.m." normalisation
            var e = m.range.last + 1
            while (e < orig.length && low[e] == ' ' && orig[e] != ' ') { used[e] = true; e++ }
            // blank it out so later patterns don't re-match
            low = low.replaceRange(m.range, " ".repeat(m.value.length))
            return m
        }

        val nowDt = Recur.ldt(now)
        var date: LocalDate? = null
        var time: LocalTime? = null
        var relative: Long? = null
        var rule = Rule.NONE
        var listId: String? = null
        var nag = false
        var partOfDay: String? = null

        // ---- filler / intent words ----
        take(Regex("^(?:please )?(?:remind me (?:to |about )?|set (?:an |a )?(?:alarm|reminder) (?:to |for )?|alarm (?:to |for )?|reminder (?:to |for )?|i need to |i have to |don'?t forget to )"))

        // ---- nag mode ----
        if (take(Regex("\\b(?:until (?:it'?s |i'?m |i )?(?:done|do it|finished|complete)|keep (?:reminding|ringing)(?: me)?|nag(?: me)?|(?:and )?don'?t let me forget)\\b")) != null) nag = true

        // ---- list / tag ----
        take(Regex("#(\\w+)"))?.let { m -> listId = lists.firstOrNull { it.name.equals(m.groupValues[1], true) }?.id }
        if (listId == null) for (l in lists) {
            val n = Regex.escape(l.name.lowercase(Locale.ROOT))
            if (take(Regex("\\b(?:(?:in|to|on|for|under) (?:my |the )?)?$n list\\b")) != null) { listId = l.id; break }
        }

        // ---- repeats ----
        val endRe = Regex("\\b(?:until|till) (?:the )?(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTH)\\b")
        var endDay: Long? = null
        take(endRe)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = monthOf(m.groupValues[2])
            var ld = LocalDate.of(nowDt.year, mo, minOf(d, 28).coerceAtLeast(1)).withDayOfMonth(minOf(d, LocalDate.of(nowDt.year, mo, 1).lengthOfMonth()))
            if (ld.isBefore(nowDt.toLocalDate())) ld = ld.plusYears(1)
            endDay = ld.toEpochDay()
        }
        var times: Int? = null
        take(Regex("\\b(?:for )?$NUM times\\b"))?.let { times = num(it.groupValues[1]) }

        fun daysIn(s: String) = Regex(DAY).findAll(s).map { dayOf(it.value) }.toSet()

        run repeats@{
            // every other day / every 3 days
            take(Regex("\\bevery $NUM days?\\b"))?.let { rule = Rule(Freq.DAILY, num(it.groupValues[1])); return@repeats }
            take(Regex("\\b(?:every ?day|daily|each day|every single day|every morning|every night|every evening)\\b"))?.let {
                rule = Rule(Freq.DAILY)
                if ("morning" in it.value) partOfDay = "morning"; if ("night" in it.value) partOfDay = "night"; if ("evening" in it.value) partOfDay = "evening"
                return@repeats
            }
            take(Regex("\\b(?:every |on |each )?weekdays?\\b"))?.let { rule = Rule(Freq.WEEKLY, days = setOf(1, 2, 3, 4, 5)); return@repeats }
            take(Regex("\\b(?:every |on |each )?weekends?\\b"))?.let { rule = Rule(Freq.WEEKLY, days = setOf(6, 7)); return@repeats }
            // every first/last friday (of the month)
            take(Regex("\\b(?:every|each|on the|the) (first|1st|second|2nd|third|3rd|fourth|4th|last) ($DAY)(?: of (?:the |every |each )?month)?\\b"))?.let { m ->
                val nth = nthWords[m.groupValues[1]] ?: 1; val dow = DayOfWeek.of(dayOf(m.groupValues[2]))
                fun inMonth(y: Int, mo: Int): LocalDate {
                    val first = LocalDate.of(y, mo, 1)
                    return if (nth == -1) first.with(TemporalAdjusters.lastInMonth(dow)) else first.with(TemporalAdjusters.dayOfWeekInMonth(nth, dow))
                }
                var d = inMonth(nowDt.year, nowDt.monthValue)
                if (d.isBefore(nowDt.toLocalDate())) d = inMonth(d.plusMonths(1).year, d.plusMonths(1).monthValue)
                date = d
                rule = Rule(Freq.MONTHLY, monthMode = if (nth == -1) MonthMode.LAST else MonthMode.NTH); return@repeats
            }
            // every 2 weeks (on thursday) / every other week on mon and wed
            take(Regex("\\bevery $NUM weeks?(?: on)?((?:(?:,| and| &| or)? ?$DAY)*)"))?.let { m ->
                rule = Rule(Freq.WEEKLY, num(m.groupValues[1]), daysIn(m.groupValues[2])); return@repeats
            }
            // every other thursday / every 2nd monday and friday (fortnightly)
            take(Regex("\\bevery (?:other|alternate|second) ($DAY(?:(?:,| and| &)? ?$DAY)*)\\b"))?.let { m ->
                rule = Rule(Freq.WEEKLY, 2, daysIn(m.groupValues[1])); return@repeats
            }
            // every monday and thursday / every mon, wed, fri / on fridays / mondays and thursdays
            take(Regex("\\b(?:every|each|on) ($DAY(?:(?:,| and| &)? ?$DAY)*)\\b"))?.let { m ->
                val raw = m.groupValues[1]
                // "on friday" (no plural, no every) is a single date, not a repeat
                if (m.value.startsWith("on ") && !Regex("${DAY.removeSuffix("s?")}s\\b").containsMatchIn(raw) && daysIn(raw).size == 1) {
                    low = low.replaceRange(m.range, orig.substring(m.range).lowercase(Locale.ROOT)); for (i in m.range) used[i] = false
                } else { rule = Rule(Freq.WEEKLY, days = daysIn(raw)); return@repeats }
            }
            take(Regex("\\b((?:mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays)(?:(?:,| and| &)? ?$DAY)*)\\b"))?.let { m ->
                rule = Rule(Freq.WEEKLY, days = daysIn(m.groupValues[1])); return@repeats
            }
            take(Regex("\\bweekly\\b|\\bevery week\\b"))?.let { rule = Rule(Freq.WEEKLY); return@repeats }
            // monthly on the 5th / on the 1st of every month / every month on the 15th / every 3 months
            take(Regex("\\b(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)? (?:of )?(?:every|each) month\\b|\\b(?:every month|monthly|each month) on (?:the )?(\\d{1,2})(?:st|nd|rd|th)?\\b|\\bevery (\\d{1,2})(?:st|nd|rd|th) of the month\\b"))?.let { m ->
                val dom = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toInt().coerceIn(1, 31)
                var d = nowDt.toLocalDate().withDayOfMonth(minOf(dom, nowDt.toLocalDate().lengthOfMonth()))
                if (d.isBefore(nowDt.toLocalDate())) { val nm = d.plusMonths(1); d = nm.withDayOfMonth(minOf(dom, nm.lengthOfMonth())) }
                date = d; rule = Rule(Freq.MONTHLY); return@repeats
            }
            take(Regex("\\bevery $NUM months?\\b"))?.let { rule = Rule(Freq.MONTHLY, num(it.groupValues[1])); return@repeats }
            take(Regex("\\b(?:monthly|every month|each month)\\b"))?.let { rule = Rule(Freq.MONTHLY); return@repeats }
            take(Regex("\\b(?:yearly|annually|every year|each year)\\b"))?.let { rule = Rule(Freq.YEARLY); return@repeats }
        }
        if (rule.repeats) rule = rule.copy(endEpochDay = endDay, times = times)

        // ---- relative: in 20 minutes / in 2 hours / in half an hour ----
        take(Regex("\\b(?:in|after) (half an hour|$NUM (?:and a half )?(minutes?|mins?|hours?|hrs?))\\b"))?.let { m ->
            relative = if (m.groupValues[1] == "half an hour") 30 * 60_000L else {
                val n = num(m.groupValues[2]); val half = "and a half" in m.value
                if (m.groupValues[3].startsWith("h")) (n * 60 + if (half) 30 else 0) * 60_000L else n * 60_000L
            }
        }

        // ---- dates ----
        val today = nowDt.toLocalDate()
        take(Regex("\\b(?:the )?day after tomorrow\\b"))?.let { date = today.plusDays(2) }
        if (date == null) take(Regex("\\btomorrow\\b|\\btmrw?\\b"))?.let { date = today.plusDays(1) }
        if (date == null) take(Regex("\\btonight\\b"))?.let { date = today; partOfDay = partOfDay ?: "night" }
        if (date == null) take(Regex("\\btoday\\b"))?.let { date = today }
        if (date == null) take(Regex("\\b(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTH)(?: (\\d{4}))?\\b"))?.let { m ->
            date = mkDate(today, m.groupValues[1].toInt(), monthOf(m.groupValues[2]), m.groupValues[3].toIntOrNull())
        }
        if (date == null) take(Regex("\\b(?:on )?($MONTH) (\\d{1,2})(?:st|nd|rd|th)?(?:,? (\\d{4}))?\\b"))?.let { m ->
            date = mkDate(today, m.groupValues[2].toInt(), monthOf(m.groupValues[1]), m.groupValues[3].toIntOrNull())
        }
        if (date == null) take(Regex("\\b(?:on )?(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b"))?.let { m ->  // dd/mm (India)
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            date = mkDate(today, m.groupValues[1].toInt(), m.groupValues[2].toInt().coerceIn(1, 12), y)
        }
        if (date == null && !rule.repeats) take(Regex("\\b(?:on |this |next |coming )?($DAY)\\b"))?.let { m ->
            val dow = DayOfWeek.of(dayOf(m.groupValues[1]))
            date = if (m.value.trim().startsWith("next")) today.with(TemporalAdjusters.next(dow)) else today.with(TemporalAdjusters.nextOrSame(dow))
            if (date == today && !m.value.contains("this") && !m.value.contains("on")) date = today // keep today if time later today
        }

        // ---- times ----
        take(Regex("\\b(?:at |by |around )?(?:(noon|midday)|(midnight))\\b"))?.let { m -> time = if (m.groupValues[1].isNotEmpty()) LocalTime.NOON else LocalTime.of(23, 59) }
        var ampm: String? = null
        if (time == null) take(Regex("\\b(?:at |by |around |@ ?)?(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm)\\b"))?.let { m ->
            time = LocalTime.of(m.groupValues[1].toInt().coerceIn(0, 23) % 24, m.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0); ampm = m.groupValues[3]
        }
        if (time == null) take(Regex("\\b(?:at |by |around |@ ?)?(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve) ?(am|pm|o'?clock)\\b"))?.let { m ->
            time = LocalTime.of(num(m.groupValues[1]), 0); ampm = m.groupValues[2].takeIf { it == "am" || it == "pm" }
        }
        if (time == null) take(Regex("\\b(?:at |by |around |@ ?)(\\d{1,2})(?:[:.](\\d{2}))?(?: ?o'?clock)?\\b|\\b(\\d{1,2})[:](\\d{2})\\b"))?.let { m ->
            val h = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt(); val mi = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: 0
            if (h in 0..23 && mi in 0..59) time = LocalTime.of(h, mi)
        }
        take(Regex("\\b(?:in the |this |at )?(morning|afternoon|evening|night)\\b"))?.let { partOfDay = it.groupValues[1] }

        time?.let { t ->
            var h = t.hour
            when {
                ampm == "pm" && h < 12 -> h += 12
                ampm == "am" && h == 12 -> h = 0
                ampm == null && h in 1..11 -> {
                    h = when (partOfDay) {
                        "afternoon", "evening", "night" -> h + 12
                        "morning" -> h
                        else -> {
                            val d = date ?: today
                            // ambiguous "at 7": pick the next upcoming 7 today, otherwise a sensible daytime hour
                            if (d == today && !rule.repeats) {
                                val am = LocalDateTime.of(today, LocalTime.of(h, t.minute))
                                if (am.isAfter(nowDt)) h else h + 12
                            } else if (h <= 6) h + 12 else h
                        }
                    }
                }
            }
            time = LocalTime.of(h % 24, t.minute)
        }
        if (time == null && partOfDay != null) time = when (partOfDay) {
            "morning" -> LocalTime.of(8, 0); "afternoon" -> LocalTime.of(14, 0); "evening" -> LocalTime.of(18, 0); else -> LocalTime.of(21, 0)
        }

        // ---- combine ----
        val hadTime = time != null || relative != null
        val hadDate = date != null
        var due: Long = when {
            relative != null -> now + relative!!
            else -> {
                val d = date ?: today
                val t = time ?: if (hadDate || rule.repeats) LocalTime.of(9, 0) else nowDt.toLocalTime().plusHours(1).withSecond(0).withNano(0).let { it.withMinute(it.minute / 5 * 5) }
                var ms = Recur.ms(d, t)
                if (date == null && time == null && !rule.repeats) ms = now + 60 * 60_000L
                if (ms <= now && date == null) ms = Recur.ms(d.plusDays(1), t)   // "at 7" when 7 already passed → tomorrow
                ms
            }
        }
        if (relative == null) due = due / 60_000L * 60_000L
        if (rule.repeats) {
            due = Recur.normalizeStart(rule, due)
            if (due <= now) due = Recur.nextAfter(rule, due, now) ?: due
        }

        // ---- title from untouched words ----
        val sb = StringBuilder()
        for (i in orig.indices) sb.append(if (used[i]) ' ' else orig[i])
        var title = sb.toString().replace(Regex("\\s+"), " ").trim()
        val filler = Regex("^(?:(?:to|and|at|on|for|by|in|the|every|me|then|,|-)\\s+)+|(?:\\s+(?:to|and|at|on|for|by|in|the|every|of|,|-))+$", RegexOption.IGNORE_CASE)
        repeat(3) { title = title.replace(filler, "").trim().trim(',', '-', '.').trim() }
        if (title.isEmpty()) title = "Reminder"
        title = title.replaceFirstChar { it.titlecase(Locale.getDefault()) }

        return Result(title, due, rule, listId, nag, hadTime, hadDate)
    }

    private fun mkDate(today: LocalDate, d: Int, m: Int, y: Int?): LocalDate {
        val year = y ?: today.year
        val mo = m.coerceIn(1, 12)
        val len = LocalDate.of(year, mo, 1).lengthOfMonth()
        var ld = LocalDate.of(year, mo, d.coerceIn(1, len))
        if (y == null && ld.isBefore(today)) ld = ld.plusYears(1)
        return ld
    }
}
