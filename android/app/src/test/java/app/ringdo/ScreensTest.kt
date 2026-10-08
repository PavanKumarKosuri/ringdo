package app.ringdo

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()
    private val out = "build/screenshots"

    private fun settle() { rule.mainClock.advanceTimeBy(600) }
    @org.junit.Before fun noAuto() { rule.mainClock.autoAdvance = false }

    private fun seed() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        TodoStore.load(ctx)
        val todos = listOf(
            Todo(title = "Call the bank about the loan", start = now + 42 * 60_000, listId = "personal", notes = "Account no. 3021 4455 — ask for Ravi"),
            Todo(title = "Gym", start = Recur.ms(today.plusDays(1), LocalTime.of(6, 0)), rule = Rule(Freq.WEEKLY, days = setOf(1, 3, 5)), listId = "health", nag = true),
            Todo(title = "Team sync", start = Recur.ms(today.plusDays(3), LocalTime.of(16, 30)), rule = Rule(Freq.WEEKLY, 2, setOf(4)), listId = "work",
                subtasks = listOf(Sub(text = "Prepare slides", done = true), Sub(text = "Share agenda"), Sub(text = "Book room"))),
            Todo(title = "Pay rent", start = Recur.ms(today.plusDays(12), LocalTime.of(9, 0)), rule = Rule(Freq.MONTHLY), listId = "home"),
            Todo(title = "Water the plants", start = now - 20 * 60_000, fired = true, listId = "home"),
            Todo(title = "Buy groceries", start = now - 3600_000, done = true, completedAt = now - 1800_000),
        )
        val hist = listOf(
            HistoryEvent(todoId = "a", title = "Take medicine", listId = "health", type = EventType.DONE, at = now - 3600_000),
            HistoryEvent(todoId = "b", title = "Water the plants", listId = "home", type = EventType.STOPPED, at = now - 20 * 60_000),
            HistoryEvent(todoId = "c", title = "Morning walk", listId = "health", type = EventType.SNOOZED, at = now - 5 * 3600_000),
            HistoryEvent(todoId = "a", title = "Take medicine", listId = "health", type = EventType.DONE, at = now - 26 * 3600_000),
        )
        TodoStore.mutate(ctx) { it.copy(todos = todos, history = hist, lists = DefaultLists) }
    }

    @Test fun todos() { seed(); rule.setContent { RingdoTheme { App() } }; settle(); rule.onRoot().captureRoboImage("$out/1_todos.png") }
    @Test fun calendar() { seed(); rule.setContent { RingdoTheme { App() } }; rule.onNodeWithText("Calendar").performClick(); settle(); rule.onRoot().captureRoboImage("$out/3_calendar.png") }
    @Test fun history() { seed(); rule.setContent { RingdoTheme { App() } }; rule.onNodeWithText("History").performClick(); settle(); rule.onRoot().captureRoboImage("$out/4_history.png") }
    @Test fun editor() {
        seed()
        val t = TodoStore.data.value.todos.first { it.title == "Team sync" }
        rule.setContent { RingdoTheme { EditorScreen(t, false, DefaultLists, null, {}, {}, {}, {}) } }
        settle(); rule.onRoot().captureRoboImage("$out/2_editor.png")
    }
    @Test fun editorVoice() {
        seed()
        val p = Parser.parse("review budget every last friday of the month at 4 pm in my work list until done", System.currentTimeMillis(), DefaultLists)
        val t = Todo(title = p.title, start = p.due, rule = p.rule, listId = p.listId, nag = p.nag)
        rule.setContent { RingdoTheme { EditorScreen(t, true, DefaultLists, "review budget every last friday of the month at 4 pm in my work list until done", {}, {}, {}, {}) } }
        settle(); rule.onRoot().captureRoboImage("$out/2b_editor_voice.png")
    }
    @Test fun ring() {
        seed()
        val t = TodoStore.data.value.todos.first { it.title == "Team sync" }.copy(notes = "Room 4B · bring the Q3 numbers", nag = true)
        rule.setContent { RingdoTheme { RingScreen(t, DefaultLists[1], {}, {}, {}, {}) } }
        settle(); rule.onRoot().captureRoboImage("$out/5_ring.png")
    }
}
