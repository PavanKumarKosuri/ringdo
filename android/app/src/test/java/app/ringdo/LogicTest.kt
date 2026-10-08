package app.ringdo

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class LogicTest {
    private val z = ZoneId.of("Asia/Kolkata")
    // Friday 2 Oct 2026, 15:30 IST
    private val now = LocalDateTime.of(2026, 10, 2, 15, 30).atZone(z).toInstant().toEpochMilli()
    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, m, d, h, mi).atZone(z).toInstant().toEpochMilli()
    private fun show(ms: Long) = Recur.ldt(ms).toString()
    private val lists = DefaultLists

    @Before fun setup() { Recur.zone = z }

    // ---------------- recurrence ----------------
    @Test fun weeklyThuFri() {
        val r = Rule(Freq.WEEKLY, days = setOf(4, 5))
        val start = Recur.normalizeStart(r, at(2026, 10, 3, 7)) // Sat → next Thu
        assertEquals("2026-10-08T07:00", show(start))
        val n1 = Recur.nextAfter(r, start, start)!!; assertEquals("2026-10-09T07:00", show(n1))
        val n2 = Recur.nextAfter(r, start, n1)!!; assertEquals("2026-10-15T07:00", show(n2))
        assertEquals("Every Thu, Fri", Recur.describe(r, start))
    }
    @Test fun everyOtherWeek() {
        val r = Rule(Freq.WEEKLY, 2, setOf(4))
        val s = at(2026, 10, 8, 16, 30)
        assertEquals("2026-10-22T16:30", show(Recur.nextAfter(r, s, s)!!))
        assertEquals("Every other week on Thursday", Recur.describe(r, s))
    }
    @Test fun monthlyDay31() {
        val r = Rule(Freq.MONTHLY)
        val s = at(2026, 10, 31, 9)
        val n = Recur.nextAfter(r, s, s)!!; assertEquals("2026-11-30T09:00", show(n))
        assertEquals("2026-12-31T09:00", show(Recur.nextAfter(r, s, n)!!))
    }
    @Test fun monthlyLastFriday() {
        val r = Rule(Freq.MONTHLY, monthMode = MonthMode.LAST)
        val s = at(2026, 10, 30, 18) // last Fri of Oct
        assertEquals("2026-11-27T18:00", show(Recur.nextAfter(r, s, s)!!))
        assertEquals("Monthly on the last Friday", Recur.describe(r, s))
    }
    @Test fun monthlySecondTuesday() {
        val r = Rule(Freq.MONTHLY, monthMode = MonthMode.NTH)
        val s = at(2026, 10, 13, 10)
        assertEquals("2026-11-10T10:00", show(Recur.nextAfter(r, s, s)!!))
    }
    @Test fun yearlyLeap() {
        val r = Rule(Freq.YEARLY); val s = at(2028, 2, 29, 8)
        assertEquals("2029-02-28T08:00", show(Recur.nextAfter(r, s, s)!!))
    }
    @Test fun endsAfterTimes() {
        val t = Todo(title = "x", start = at(2026, 10, 3, 9), rule = Rule(Freq.DAILY, times = 2))
        val a = Actions.complete(t, now); assertFalse(a.done); assertEquals(2, a.occurrence)
        val b = Actions.complete(a, now); assertTrue(b.done)
    }
    @Test fun endsOnDate() {
        val r = Rule(Freq.DAILY, endEpochDay = LocalDate.of(2026, 10, 4).toEpochDay())
        val s = at(2026, 10, 3, 9)
        assertEquals("2026-10-04T09:00", show(Recur.nextAfter(r, s, s)!!))
        assertNull(Recur.nextAfter(r, s, at(2026, 10, 4, 9)))
    }
    @Test fun nagStop() {
        val t = Todo(title = "x", start = now, nag = true, nagMin = 15)
        val s = Actions.stop(t, now); assertFalse(s.done); assertFalse(s.fired); assertEquals(now + 15 * 60_000, s.due); assertEquals(1, s.nags)
    }
    @Test fun completeResetsSubtasks() {
        val t = Todo(title = "x", start = at(2026, 10, 2, 9), rule = Rule(Freq.DAILY), subtasks = listOf(Sub(text = "a", done = true)))
        val n = Actions.complete(t, now); assertEquals("2026-10-03T09:00", show(n.due)); assertFalse(n.subtasks[0].done)
    }
    @Test fun calendarOccurrences() {
        val t = Todo(title = "x", start = at(2026, 10, 5, 6), rule = Rule(Freq.WEEKLY, days = setOf(1, 3, 5)))
        val occ = Recur.occurrences(t, at(2026, 10, 1, 0), at(2026, 10, 31, 23, 59))
        assertEquals(12, occ.size)
    }

    // ---------------- parser ----------------
    private fun p(s: String) = Parser.parse(s, now, lists)

    @Test fun pTomorrowAt10() { val r = p("call the bank tomorrow at 10"); assertEquals("Call the bank", r.title); assertEquals("2026-10-03T10:00", show(r.due)) }
    @Test fun pGymDays() {
        val r = p("gym every monday wednesday and friday at 6 am")
        assertEquals("Gym", r.title); assertEquals(setOf(1, 3, 5), r.rule.days); assertEquals(Freq.WEEKLY, r.rule.freq); assertEquals("2026-10-05T06:00", show(r.due))
    }
    @Test fun pRent() {
        val r = p("pay rent on the 1st of every month #home")
        assertEquals("Pay rent", r.title); assertEquals(Freq.MONTHLY, r.rule.freq); assertEquals("home", r.listId); assertEquals("2026-11-01T09:00", show(r.due))
    }
    @Test fun pMedicineNag() {
        val r = p("take medicine every day at 9 pm until done")
        assertEquals("Take medicine", r.title); assertTrue(r.nag); assertEquals(Freq.DAILY, r.rule.freq); assertEquals("2026-10-02T21:00", show(r.due))
    }
    @Test fun pEveryOtherThursday() {
        val r = p("team call every other thursday at 4:30 pm")
        assertEquals("Team call", r.title); assertEquals(2, r.rule.interval); assertEquals(setOf(4), r.rule.days); assertEquals("2026-10-08T16:30", show(r.due))
    }
    @Test fun pRelative() { val r = p("in 20 minutes check the oven"); assertEquals("Check the oven", r.title); assertEquals(now + 20 * 60_000, r.due) }
    @Test fun pRemindMe() { val r = p("Remind me to call Amma at 7"); assertEquals("Call Amma", r.title); assertEquals("2026-10-02T19:00", show(r.due)) }
    @Test fun pThursdays() { val r = p("Water plants on thursdays and fridays"); assertEquals("Water plants", r.title); assertEquals(setOf(4, 5), r.rule.days) }
    @Test fun pOnFriday() { val r = p("submit report on friday at 5 pm"); assertEquals("Submit report", r.title); assertFalse(r.rule.repeats); assertEquals("2026-10-02T17:00", show(r.due)) }
    @Test fun pNextMonday() { val r = p("dentist next monday 11:30 am"); assertEquals("Dentist", r.title); assertEquals("2026-10-05T11:30", show(r.due)) }
    @Test fun pDate() { val r = p("Electricity bill on 15th october"); assertEquals("Electricity bill", r.title); assertEquals("2026-10-15T09:00", show(r.due)) }
    @Test fun pSlashDate() { val r = p("passport renewal 5/11 at 10am"); assertEquals("Passport renewal", r.title); assertEquals("2026-11-05T10:00", show(r.due)) }
    @Test fun pLastFriday() { val r = p("review budget every last friday of the month at 4 pm"); assertEquals("Review budget", r.title); assertEquals(MonthMode.LAST, r.rule.monthMode); assertEquals("2026-10-30T16:00", show(r.due)) }
    @Test fun pWeekdays() { val r = p("standup every weekday at 9:45 am in my work list"); assertEquals("Standup", r.title); assertEquals(setOf(1,2,3,4,5), r.rule.days); assertEquals("work", r.listId); assertEquals("2026-10-05T09:45", show(r.due)) }
    @Test fun pSpokenPm() { val r = p("call mom at 6 p.m."); assertEquals("Call mom", r.title); assertEquals("2026-10-02T18:00", show(r.due)) }
    @Test fun pTonight() { val r = p("take out trash tonight"); assertEquals("Take out trash", r.title); assertEquals("2026-10-02T21:00", show(r.due)) }
    @Test fun pYearly() { val r = p("Anniversary every year on 12 december"); assertEquals("Anniversary", r.title); assertEquals(Freq.YEARLY, r.rule.freq); assertEquals("2026-12-12T09:00", show(r.due)) }
    @Test fun pGoToWork() { val r = p("go to work tomorrow 8 am"); assertEquals("Go to work", r.title); assertNull(r.listId) }
    @Test fun pTimes() { val r = p("vitamin d every day at 8 am for 10 times"); assertEquals("Vitamin d", r.title); assertEquals(10, r.rule.times) }
}
