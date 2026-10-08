package app.ringdo

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    initial: Todo,
    isNew: Boolean,
    lists: List<TodoList>,
    heard: String?,
    onSave: (Todo) -> Unit,
    onDelete: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)

    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var listId by remember { mutableStateOf(initial.listId) }
    var at by remember { mutableLongStateOf(if (isNew) initial.due else initial.occAt) }
    var rule by remember { mutableStateOf(initial.rule) }
    var nag by remember { mutableStateOf(initial.nag) }
    var nagMin by remember { mutableIntStateOf(initial.nagMin) }
    var tone by remember { mutableStateOf(initial.toneUri?.let(Uri::parse)) }
    var subs by remember { mutableStateOf(initial.subtasks) }
    var newSub by remember { mutableStateOf("") }

    val toneName = remember(tone) { tone?.let { runCatching { RingtoneManager.getRingtone(ctx, it).getTitle(ctx) }.getOrNull() } ?: "Phone’s alarm tone" }
    val tonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            tone = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
    }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun save() {
        val tt = title.trim()
        if (tt.isEmpty()) { toast("Write the task first"); return }
        val now = System.currentTimeMillis()
        var start = Recur.normalizeStart(rule, at)
        if (start < now - 30_000) {
            if (rule.repeats) start = Recur.nextAfter(rule, start, now) ?: run { toast("This repeat has already ended"); return }
            else { toast("That time has already passed"); return }
        }
        val scheduleChanged = isNew || start != initial.occAt || rule != initial.rule || initial.done || initial.fired
        val base = initial.copy(
            title = tt, notes = notes.trim(), listId = listId, nag = nag, nagMin = nagMin,
            toneUri = tone?.toString(), subtasks = subs.filter { it.text.isNotBlank() }, rule = rule,
        )
        onSave(
            if (scheduleChanged) base.copy(start = start, occAt = start, due = start, occurrence = 1, done = false, fired = false, snoozes = 0, nags = 0, completedAt = null)
            else base
        )
    }

    Column(Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Text("✕", color = Ink.muted, fontSize = 18.sp) }
            Text(if (isNew) "New todo" else "Edit todo", color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            BigButton("Save", kind = 1, small = true, onClick = ::save)
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 40.dp)) {
            if (heard != null) {
                Text("Heard: “$heard”", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()
                    .background(Ink.surface, RoundedCornerShape(10.dp)).padding(12.dp))
                Spacer(Modifier.height(14.dp))
            }

            SectionLabel("Task")
            InputBox {
                BasicTextField(title, { title = it.take(140) }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(Ink.accent), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner -> if (title.isEmpty()) Text("What needs doing?", color = Ink.faint, fontSize = 17.sp); inner() },
                    modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            InputBox {
                BasicTextField(notes, { notes = it.take(1000) }, textStyle = TextStyle(color = Ink.text, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Ink.accent), minLines = 2, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner -> if (notes.isEmpty()) Text("Notes — shown on the alarm screen (e.g. account no., address)", color = Ink.faint, fontSize = 15.sp); inner() },
                    modifier = Modifier.fillMaxWidth())
            }

            SectionLabel("List")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("No list", { listId = null }, selected = listId == null)
                lists.forEach { l -> ColorPill(l.name, l.color, listId == l.id) { listId = l.id } }
            }

            SectionLabel("When")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InputBox(Modifier.weight(1.2f), onClick = {
                    val d = Recur.date(at)
                    DatePickerDialog(ctx, { _, y, m, dd -> at = Recur.ms(LocalDate.of(y, m + 1, dd), Recur.ldt(at).toLocalTime()) },
                        d.year, d.monthValue - 1, d.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
                }) { Text(fmtDay(at), color = Ink.text, fontSize = 16.sp) }
                InputBox(Modifier.weight(1f), onClick = {
                    val t = Recur.ldt(at)
                    TimePickerDialog(ctx, { _, h, m -> at = Recur.ms(Recur.date(at), LocalTime.of(h, m)) },
                        t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }) { Text(fmtTime(at), color = Ink.text, fontSize = 16.sp, fontFamily = Mono) }
            }

            SectionLabel("Repeat")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Freq.NONE to "Once", Freq.DAILY to "Daily", Freq.WEEKLY to "Weekly", Freq.MONTHLY to "Monthly", Freq.YEARLY to "Yearly").forEach { (f, l) ->
                    Pill(l, {
                        rule = if (f == Freq.NONE) Rule.NONE else rule.copy(freq = f, interval = if (rule.freq == f) rule.interval else 1,
                            days = if (f == Freq.WEEKLY) rule.days.ifEmpty { setOf(Recur.date(at).dayOfWeek.value) } else emptySet(),
                            monthMode = MonthMode.DAY)
                    }, selected = rule.freq == f)
                }
            }
            if (rule.repeats) {
                Spacer(Modifier.height(12.dp))
                Panel {
                    val unit = when (rule.freq) { Freq.DAILY -> "day"; Freq.WEEKLY -> "week"; Freq.MONTHLY -> "month"; else -> "year" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", color = Ink.muted, fontSize = 15.sp)
                        Spacer(Modifier.width(10.dp))
                        Stepper(rule.interval, 1..99) { rule = rule.copy(interval = it) }
                        Spacer(Modifier.width(10.dp))
                        Text(if (rule.interval == 1) unit else unit + "s", color = Ink.text, fontSize = 15.sp)
                    }
                    if (rule.freq == Freq.WEEKLY) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                                val v = i + 1; val on = v in rule.days
                                Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(if (on) Ink.accent else Ink.bg)
                                    .border(1.dp, if (on) Ink.accent else Ink.line, CircleShape)
                                    .clickable {
                                        val nd = if (on) rule.days - v else rule.days + v
                                        if (nd.isNotEmpty()) rule = rule.copy(days = nd)
                                    }, contentAlignment = Alignment.Center
                                ) { Text(l, color = if (on) Ink.accentInk else Ink.muted, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("Weekdays", { rule = rule.copy(days = setOf(1, 2, 3, 4, 5)) }, selected = rule.days == setOf(1, 2, 3, 4, 5))
                            Pill("Weekends", { rule = rule.copy(days = setOf(6, 7)) }, selected = rule.days == setOf(6, 7))
                            Pill("Every day", { rule = rule.copy(days = (1..7).toSet()) }, selected = rule.days.size == 7)
                        }
                    }
                    if (rule.freq == Freq.MONTHLY) {
                        Spacer(Modifier.height(14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(Recur.monthModeLabel(MonthMode.DAY, at), { rule = rule.copy(monthMode = MonthMode.DAY) }, selected = rule.monthMode == MonthMode.DAY)
                            if (Recur.canUseNth(at)) Pill(Recur.monthModeLabel(MonthMode.NTH, at), { rule = rule.copy(monthMode = MonthMode.NTH) }, selected = rule.monthMode == MonthMode.NTH)
                            if (Recur.canUseLast(at)) Pill(Recur.monthModeLabel(MonthMode.LAST, at), { rule = rule.copy(monthMode = MonthMode.LAST) }, selected = rule.monthMode == MonthMode.LAST)
                        }
                        Text("Pick the date above to change which day or weekday.", color = Ink.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Ends", color = Ink.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Never", { rule = rule.copy(endEpochDay = null, times = null) }, selected = rule.endEpochDay == null && rule.times == null)
                        Pill(rule.endEpochDay?.let { "On " + fmtDay(Recur.ms(LocalDate.ofEpochDay(it), LocalTime.NOON)) } ?: "On a date", {
                            val d = rule.endEpochDay?.let { LocalDate.ofEpochDay(it) } ?: Recur.date(at).plusMonths(1)
                            DatePickerDialog(ctx, { _, y, m, dd -> rule = rule.copy(endEpochDay = LocalDate.of(y, m + 1, dd).toEpochDay(), times = null) },
                                d.year, d.monthValue - 1, d.dayOfMonth).apply { datePicker.minDate = at }.show()
                        }, selected = rule.endEpochDay != null)
                        Pill("After N times", { rule = rule.copy(times = rule.times ?: 10, endEpochDay = null) }, selected = rule.times != null)
                    }
                    rule.times?.let { n ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("After", color = Ink.muted, fontSize = 15.sp); Spacer(Modifier.width(10.dp))
                            Stepper(n, 1..999) { rule = rule.copy(times = it) }; Spacer(Modifier.width(10.dp))
                            Text("times", color = Ink.text, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(Recur.describe(rule, Recur.normalizeStart(rule, at)) + " at " + fmtTime(at), color = Ink.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            SectionLabel("Nag mode")
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep ringing until it’s done", color = Ink.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("“Stop” only pauses it — it rings again until you tap Done", color = Ink.faint, fontSize = 12.5.sp, lineHeight = 17.sp)
                    }
                    Switch(nag, { nag = it }, colors = SwitchDefaults.colors(checkedThumbColor = Ink.accentInk, checkedTrackColor = Ink.accent, uncheckedTrackColor = Ink.bg, uncheckedBorderColor = Ink.line, uncheckedThumbColor = Ink.muted))
                }
                if (nag) {
                    Spacer(Modifier.height(12.dp))
                    Text("Ring again after", color = Ink.muted, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 30, 60).forEach { m -> Pill(if (m == 60) "1 hour" else "$m min", { nagMin = m }, selected = nagMin == m) }
                    }
                }
            }

            SectionLabel("Checklist")
            Panel(padding = 0.dp) {
                subs.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SubRow(s) { subs = subs.map { if (it.id == s.id) it.copy(done = !it.done) else it } } }
                        Box(Modifier.size(44.dp).clickable { subs = subs.filter { it.id != s.id } }, contentAlignment = Alignment.Center) { Text("✕", color = Ink.faint, fontSize = 14.sp) }
                    }
                }
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+", color = Ink.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    fun addSub() { if (newSub.isNotBlank()) { subs = subs + Sub(text = newSub.trim()); newSub = "" } }
                    BasicTextField(newSub, { newSub = it.take(120) }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(Ink.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                        keyboardActions = KeyboardActions(onDone = { addSub() }),
                        decorationBox = { inner -> if (newSub.isEmpty()) Text("Add a step", color = Ink.faint, fontSize = 15.sp); inner() },
                        modifier = Modifier.weight(1f))
                    if (newSub.isNotBlank()) Text("Add", color = Ink.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clickable { addSub() }.padding(6.dp))
                }
            }

            SectionLabel("Alarm sound")
            InputBox(onClick = {
                tonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, tone ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(toneName, color = Ink.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("Change", color = Ink.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))
            BigButton(if (isNew) "Add todo with alarm" else "Save changes", Modifier.fillMaxWidth(), kind = 1, onClick = ::save)
            if (!isNew) {
                Spacer(Modifier.height(10.dp))
                if (initial.rule.repeats && !initial.done) {
                    BigButton("Skip next (${fmtDay(initial.occAt)} ${fmtTime(initial.occAt)})", Modifier.fillMaxWidth(), small = true, onClick = onSkip)
                    Spacer(Modifier.height(10.dp))
                }
                Text("Delete todo", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onDelete).padding(14.dp))
            }
        }
    }
}

@Composable
fun SectionLabel(s: String) = Text(s, color = Ink.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
fun InputBox(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(Ink.surface).border(1.dp, Ink.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 14.dp)
    ) { content() }
}

@Composable
fun Panel(padding: androidx.compose.ui.unit.Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ink.surface).border(1.dp, Ink.line, shape).padding(padding), content = content)
}

@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.clip(shape).border(1.dp, Ink.line, shape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clickable { if (value > range.first) onChange(value - 1) }, contentAlignment = Alignment.Center) { Text("−", color = Ink.text, fontSize = 18.sp) }
        Text("$value", color = Ink.text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 34.dp))
        Box(Modifier.size(40.dp).clickable { if (value < range.last) onChange(value + 1) }, contentAlignment = Alignment.Center) { Text("+", color = Ink.text, fontSize = 18.sp) }
    }
}

@Composable
fun ColorPill(label: String, color: Long, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    Row(
        Modifier.clip(shape).background(if (selected) Color(color).copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) Color(color) else Ink.line, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListDot(color); Spacer(Modifier.width(7.dp))
        Text(label, color = if (selected) Ink.text else Ink.muted, fontSize = 13.sp)
    }
}
