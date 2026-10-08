package app.ringdo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// Pure model + recurrence engine. No Android dependencies so it can be unit tested on the JVM.
// ---------------------------------------------------------------------------------------------

enum class Freq { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/** For monthly repeats: same date (the 5th), the Nth weekday (2nd Friday) or the last weekday (last Friday). */
enum class MonthMode { DAY, NTH, LAST }

data class Rule(
    val freq: Freq = Freq.NONE,
    val interval: Int = 1,
    val days: Set<Int> = emptySet(),      // ISO day-of-week 1 = Monday … 7 = Sunday (weekly only)
    val monthMode: MonthMode = MonthMode.DAY,
    val endEpochDay: Long? = null,        // last date (inclusive) an occurrence may fall on
    val times: Int? = null,               // total number of occurrences
) {
    val repeats get() = freq != Freq.NONE
    companion object { val NONE = Rule() }
}

data class Sub(val id: String = newId(), val text: String, val done: Boolean = false)

data class TodoList(val id: String, val name: String, val color: Long)

enum class EventType { DONE, STOPPED, SNOOZED, NAGGED, SKIPPED, MISSED }

data class HistoryEvent(
    val id: String = newId(),
    val todoId: String,
    val title: String,
    val listId: String?,
    val type: EventType,
    val at: Long,
)

data class Todo(
    val id: String = newId(),
    val title: String,
    val notes: String = "",
    val listId: String? = null,
    val start: Long,                 // anchor: first occurrence (date + time of day for repeats)
    val occAt: Long = start,         // the occurrence currently pending
    val due: Long = occAt,           // when it will actually ring (differs after snooze / nag)
    val rule: Rule = Rule.NONE,
    val occurrence: Int = 1,         // 1-based index of the pending occurrence
    val nag: Boolean = false,
    val nagMin: Int = 15,
    val toneUri: String? = null,
    val subtasks: List<Sub> = emptyList(),
    val done: Boolean = false,
    val fired: Boolean = false,      // rang and was stopped without completing (one-off todos)
    val snoozes: Int = 0,
    val nags: Int = 0,
    val completedAt: Long? = null,
    val isTest: Boolean = false,
    val created: Long = System.currentTimeMillis(),
) {
    val code: Int get() = id.hashCode()
}

fun newId() = UUID.randomUUID().toString().replace("-", "").take(10)

object Recur {
    var zone: ZoneId = ZoneId.systemDefault()

    fun ldt(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    fun ms(d: LocalDate, t: LocalTime): Long = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
    fun date(ms: Long): LocalDate = ldt(ms).toLocalDate()

    private fun nthOfMonth(d: LocalDate) = (d.dayOfMonth - 1) / 7 + 1
    private fun isLastOfMonth(d: LocalDate) = d.dayOfMonth + 7 > d.lengthOfMonth()

    /** Does [rule] (anchored at [anchor]) produce an occurrence on date [d]? */
    fun matches(rule: Rule, anchor: LocalDate, d: LocalDate): Boolean {
        if (d.isBefore(anchor)) return false
        rule.endEpochDay?.let { if (d.toEpochDay() > it) return false }
        val n = rule.interval.coerceAtLeast(1).toLong()
        return when (rule.freq) {
            Freq.NONE -> d == anchor
            Freq.DAILY -> ChronoUnit.DAYS.between(anchor, d) % n == 0L
            Freq.WEEKLY -> {
                val days = rule.days.ifEmpty { setOf(anchor.dayOfWeek.value) }
                val w = ChronoUnit.WEEKS.between(anchor.with(DayOfWeek.MONDAY), d.with(DayOfWeek.MONDAY))
                d.dayOfWeek.value in days && w % n == 0L
            }
            Freq.MONTHLY -> {
                val months = (d.year * 12L + d.monthValue) - (anchor.year * 12L + anchor.monthValue)
                months % n == 0L && when (rule.monthMode) {
                    MonthMode.DAY -> d.dayOfMonth == minOf(anchor.dayOfMonth, d.lengthOfMonth())
                    MonthMode.NTH -> d.dayOfWeek == anchor.dayOfWeek && nthOfMonth(d) == nthOfMonth(anchor)
                    MonthMode.LAST -> d.dayOfWeek == anchor.dayOfWeek && isLastOfMonth(d)
                }
            }
            Freq.YEARLY -> {
                val years = (d.year - anchor.year).toLong()
                years % n == 0L && d.month == anchor.month &&
                    d.dayOfMonth == minOf(anchor.dayOfMonth, d.lengthOfMonth())
            }
        }
    }

    /** First occurrence strictly after [afterMs], or null if the rule has ended. Ignores the `times` limit. */
    fun nextAfter(rule: Rule, start: Long, afterMs: Long): Long? {
        if (!rule.repeats) return null
        val s = ldt(start); val anchor = s.toLocalDate(); val tod = s.toLocalTime()
        var d = maxOf(date(afterMs), anchor)
        repeat(366 * 12) {
            if (matches(rule, anchor, d)) {
                val at = ms(d, tod)
                if (at > afterMs) return at
            }
            rule.endEpochDay?.let { if (d.toEpochDay() > it) return null }
            d = d.plusDays(1)
        }
        return null
    }

    /** Snap a freshly chosen start to the first date the pattern actually rings on (e.g. weekly Mon/Thu). */
    fun normalizeStart(rule: Rule, start: Long): Long {
        if (!rule.repeats) return start
        val s = ldt(start)
        if (rule.freq == Freq.WEEKLY && rule.days.isNotEmpty() && s.dayOfWeek.value !in rule.days) {
            var d = s.toLocalDate()
            repeat(7) { d = d.plusDays(1); if (d.dayOfWeek.value in rule.days) return ms(d, s.toLocalTime()) }
        }
        return start
    }

    /** All occurrences of a todo between [from] and [to] (inclusive), for the calendar. */
    fun occurrences(t: Todo, from: Long, to: Long, cap: Int = 62): List<Long> {
        if (t.done) return emptyList()
        if (!t.rule.repeats) return if (t.due in from..to) listOf(t.due) else emptyList()
        val out = mutableListOf<Long>()
        if (t.due in from..to) out += t.due
        var cur = maxOf(from - 1, t.occAt)
        var idx = t.occurrence
        while (out.size < cap) {
            val n = nextAfter(t.rule, t.start, cur) ?: break
            idx++
            if (t.rule.times != null && idx > t.rule.times) break
            if (n > to) break
            if (n != t.due) out += n
            cur = n
        }
        return out.sorted()
    }

    private fun dayShort(v: Int) = DayOfWeek.of(v).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun dayLong(v: Int) = DayOfWeek.of(v).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
    private val nthWord = listOf("", "first", "second", "third", "fourth", "fifth")

    /** Human description, e.g. "Every Mon, Wed, Fri", "Every 2 weeks on Thu", "Monthly on the last Friday". */
    fun describe(rule: Rule, start: Long): String {
        if (!rule.repeats) return "Once"
        val a = date(start); val n = rule.interval.coerceAtLeast(1)
        val base = when (rule.freq) {
            Freq.DAILY -> if (n == 1) "Every day" else if (n == 2) "Every other day" else "Every $n days"
            Freq.WEEKLY -> {
                val days = rule.days.ifEmpty { setOf(a.dayOfWeek.value) }.sorted()
                val list = when (days) {
                    listOf(1, 2, 3, 4, 5) -> "weekdays"
                    listOf(6, 7) -> "weekends"
                    listOf(1, 2, 3, 4, 5, 6, 7) -> "every day"
                    else -> if (days.size == 1) dayLong(days[0]) else days.joinToString(", ") { dayShort(it) }
                }
                when {
                    n == 1 && list == "every day" -> "Every day"
                    n == 1 -> "Every $list"
                    n == 2 -> "Every other week on $list"
                    else -> "Every $n weeks on $list"
                }
            }
            Freq.MONTHLY -> {
                val what = when (rule.monthMode) {
                    MonthMode.DAY -> "on the ${ordinal(a.dayOfMonth)}"
                    MonthMode.NTH -> "on the ${nthWord[nthOfMonth(a)]} ${dayLong(a.dayOfWeek.value)}"
                    MonthMode.LAST -> "on the last ${dayLong(a.dayOfWeek.value)}"
                }
                if (n == 1) "Monthly $what" else "Every $n months $what"
            }
            Freq.YEARLY -> {
                val md = "${a.dayOfMonth} ${a.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
                if (n == 1) "Yearly on $md" else "Every $n years on $md"
            }
            Freq.NONE -> "Once"
        }
        val end = when {
            rule.times != null -> ", ${rule.times} times"
            rule.endEpochDay != null -> {
                val e = LocalDate.ofEpochDay(rule.endEpochDay)
                ", until ${e.dayOfMonth} ${e.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
            }
            else -> ""
        }
        return base + end
    }

    fun monthModeLabel(mode: MonthMode, start: Long): String {
        val a = date(start)
        return when (mode) {
            MonthMode.DAY -> "On the ${ordinal(a.dayOfMonth)}"
            MonthMode.NTH -> "On the ${nthWord[nthOfMonth(a)]} ${dayShort(a.dayOfWeek.value)}"
            MonthMode.LAST -> "On the last ${dayShort(a.dayOfWeek.value)}"
        }
    }
    fun canUseLast(start: Long) = isLastOfMonth(date(start))
    fun canUseNth(start: Long) = nthOfMonth(date(start)) <= 4
}

/** State transitions shared by the alarm service and the UI, so both behave identically. */
object Actions {
    /** Move a repeating todo to its next occurrence, or finish it. */
    fun advance(t: Todo, now: Long): Todo {
        if (!t.rule.repeats) return t.copy(done = true, completedAt = now)
        val nextIdx = t.occurrence + 1
        val next = Recur.nextAfter(t.rule, t.start, maxOf(t.occAt, now))
        if (next == null || (t.rule.times != null && nextIdx > t.rule.times)) return t.copy(done = true, completedAt = now)
        return t.copy(
            occAt = next, due = next, occurrence = nextIdx, fired = false, snoozes = 0, nags = 0,
            subtasks = t.subtasks.map { it.copy(done = false) }
        )
    }

    fun complete(t: Todo, now: Long) = advance(t, now)
    fun skip(t: Todo, now: Long) = advance(t, now)

    fun snooze(t: Todo, now: Long, minutes: Int) = t.copy(due = now + minutes * 60_000L, fired = false, snoozes = t.snoozes + 1)

    /** "Stop" — with nag mode it comes back later; otherwise the occurrence is let go. */
    fun stop(t: Todo, now: Long): Todo = when {
        t.nag -> t.copy(due = now + t.nagMin * 60_000L, fired = false, nags = t.nags + 1)
        t.rule.repeats -> advance(t, now).let { if (it.done) it.copy(completedAt = null) else it }
        else -> t.copy(fired = true)
    }
}
