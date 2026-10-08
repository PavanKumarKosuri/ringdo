package app.ringdo

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

/** Full-screen ringing screen. Shows over the lock screen and turns the screen on. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        setContent {
            val t by AlarmService.ringing.collectAsStateWithLifecycle()
            var seen by remember { mutableStateOf(false) }
            LaunchedEffect(t) { if (t != null) seen = true else if (seen) finish() }
            val data by TodoStore.data.collectAsStateWithLifecycle()
            val shown = t?.let { r -> data.todos.firstOrNull { it.id == r.id } ?: r }
            if (shown == null) {
                Box(Modifier.fillMaxSize().background(Ink.bg))
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); if (AlarmService.ringing.value == null) finish() }
            } else RingScreen(shown, data.lists.firstOrNull { it.id == shown.listId },
                onToggleSub = { sid -> TodoStore.upsert(this, shown.copy(subtasks = shown.subtasks.map { if (it.id == sid) it.copy(done = !it.done) else it })) },
                onSnooze = { m -> send(AlarmService.ACTION_SNOOZE, shown.id, m) },
                onDone = { send(AlarmService.ACTION_DONE, shown.id) },
                onStop = { send(AlarmService.ACTION_STOP, shown.id) })
        }
    }

    private fun send(action: String, id: String, min: Int = 0) {
        startService(AlarmService.action(this, action, id, min))
        if (action == AlarmService.ACTION_DONE || action == AlarmService.ACTION_STOP || action == AlarmService.ACTION_SNOOZE) {
            // dismiss the keyguard request only for the alarm screen; the phone stays locked
            finish()
        }
    }

    @Deprecated("Back should not silently dismiss a ringing alarm")
    override fun onBackPressed() { /* ignore: use Stop / Snooze / Done */ }
}

@Composable
internal fun RingScreen(t: Todo, list: TodoList?, onToggleSub: (String) -> Unit, onSnooze: (Int) -> Unit, onDone: () -> Unit, onStop: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "ring")
    val pulse by inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")
    val shake by inf.animateFloat(-12f, 12f, infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF4A1A0C), Ink.bg), radius = 1400f))
            .safeDrawingPadding().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val compact = t.notes.isNotBlank() || t.subtasks.isNotEmpty()
            Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
            Box(Modifier.size(if (compact) 104.dp else 140.dp).scale(pulse).background(Ink.accent, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.rotate(shake)) { AlarmGlyph(Ink.accentInk, if (compact) 54.dp else 72.dp) }
            }
            Spacer(Modifier.height(if (compact) 16.dp else 28.dp))
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), color = Ink.text, fontSize = if (compact) 50.sp else 64.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
            Spacer(Modifier.height(10.dp))
            Text(t.title, color = Ink.text, fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 32.sp)
            Spacer(Modifier.height(6.dp))
            val sub = buildList {
                list?.let { add(it.name) }
                if (t.rule.repeats) add(Recur.describe(t.rule, t.start))
                if (t.snoozes > 0) add("Snoozed ${t.snoozes}×")
                if (t.nags > 0) add("Reminder #${t.nags + 1}")
            }.ifEmpty { listOf("Due now") }.joinToString(" · ")
            Text(sub, color = Ink.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (t.notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(t.notes, color = Ink.text, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(Ink.surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(14.dp))
            }
            if (t.subtasks.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth()
                    .background(Ink.surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(vertical = 4.dp)) {
                    t.subtasks.forEach { s -> SubRow(s) { onToggleSub(s.id) } }
                }
            }
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Snooze", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(5, 10, 30).forEach { m -> BigButton("$m min", Modifier.weight(1f), kind = 0) { onSnooze(m) } }
            }
            BigButton("Mark done & stop", Modifier.fillMaxWidth(), kind = 1, onClick = onDone)
            BigButton(if (t.nag) "Stop · ask again in ${t.nagMin} min" else "Stop alarm only", Modifier.fillMaxWidth(), kind = 2, onClick = onStop)
        }
    }
}
