package app.ringdo

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlarmService.ensureChannel(this)
        TodoStore.load(this)
        AlarmScheduler.rescheduleAll(this)
        setContent { RingdoTheme { App() } }
    }
}

// ---------- formatting helpers (shared) ----------
fun fmtTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
fun fmtDay(ms: Long): String {
    val d = Recur.date(ms); val today = LocalDate.now(Recur.zone)
    return when (java.time.temporal.ChronoUnit.DAYS.between(today, d).toInt()) {
        0 -> "Today"; 1 -> "Tomorrow"; -1 -> "Yesterday"
        else -> d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()) + ", " + d.dayOfMonth + " " +
            d.month.getDisplayName(JTextStyle.SHORT, Locale.getDefault()) + if (d.year != today.year) " ${d.year}" else ""
    }
}
fun countdown(ms: Long): String {
    val neg = ms < 0; val a = kotlin.math.abs(ms)
    val m = a / 60_000; val h = m / 60; val d = h / 24
    val s = when { d > 0 -> "${d}d ${h % 24}h"; h > 0 -> "${h}h ${m % 60}m"; m > 0 -> "${m}m"; else -> "${a / 1000}s" }
    return if (neg) "$s late" else "in $s"
}

private enum class Tab(val label: String) { TODOS("Todos"), CALENDAR("Calendar"), HISTORY("History") }
private data class Editing(val todo: Todo, val isNew: Boolean, val heard: String? = null)
private data class Check(val label: String, val why: String, val ok: Boolean, val fix: () -> Unit)

/** UI-side actions: update store, (re)schedule alarms, log history. */
private object Ops {
    fun save(ctx: Context, t: Todo) { TodoStore.upsert(ctx, t); AlarmScheduler.schedule(ctx, t) }
    fun complete(ctx: Context, t: Todo): Todo {
        val n = Actions.complete(t, System.currentTimeMillis())
        TodoStore.log(ctx, t, EventType.DONE); save(ctx, n); return n
    }
    fun skip(ctx: Context, t: Todo) { TodoStore.log(ctx, t, EventType.SKIPPED); save(ctx, Actions.skip(t, System.currentTimeMillis())) }
    fun delete(ctx: Context, t: Todo) { AlarmScheduler.cancel(ctx, t); TodoStore.remove(ctx, t.id) }
    fun restore(ctx: Context, t: Todo) { TodoStore.upsert(ctx, t); if (!t.done && !t.fired) AlarmScheduler.schedule(ctx, t) else AlarmScheduler.cancel(ctx, t) }
}

@Composable
internal fun App() {
    val ctx = LocalContext.current
    val data by TodoStore.data.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.TODOS) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var showLists by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) } // list id, null = all
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun undoable(msg: String, before: Todo, action: String = "Undo", onAction: (() -> Unit)? = null) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar(msg, actionLabel = action, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) (onAction ?: { Ops.restore(ctx, before) })()
        }
    }
    fun newTodo(): Todo {
        val due = (System.currentTimeMillis() + 15 * 60_000L) / 60_000L * 60_000L
        return Todo(title = "", start = due, listId = filter)
    }

    // ---------- voice ----------
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) {
            val p = Parser.parse(heard, System.currentTimeMillis(), data.lists)
            editing = Editing(Todo(title = p.title, start = p.due, rule = p.rule, listId = p.listId ?: filter, nag = p.nag), true, heard)
        }
    }
    fun startVoice() {
        try {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "e.g. “Gym every Monday and Thursday at 6 am”"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Voice input isn’t available — install or enable Google voice typing", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Scaffold(
            containerColor = Ink.bg,
            snackbarHost = { SnackbarHost(snack) { Snackbar(it, containerColor = Ink.text, contentColor = Ink.bg, actionColor = Ink.accent, shape = RoundedCornerShape(12.dp)) } },
            bottomBar = { BottomBar(tab) { tab = it } },
            floatingActionButton = {
                if (tab != Tab.HISTORY) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Ink.surface2).border(1.dp, Ink.line, CircleShape).clickable { startVoice() },
                        contentAlignment = Alignment.Center) { MicGlyph(Ink.text) }
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Ink.accent).clickable { editing = Editing(newTodo(), true) },
                        contentAlignment = Alignment.Center) { Text("+", color = Ink.accentInk, fontSize = 32.sp, fontWeight = FontWeight.Bold) }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.TODOS -> TodosScreen(data, filter, { filter = it }, onOpen = { editing = Editing(it, false) },
                        onManageLists = { showLists = true },
                        onComplete = { t ->
                            val n = Ops.complete(ctx, t)
                            undoable(if (n.done) "Done: ${t.title}" else "Done · next ${fmtDay(n.due)} ${fmtTime(n.due)}", t)
                        },
                        onDelete = { t -> Ops.delete(ctx, t); undoable("Deleted: ${t.title}", t) },
                        onQuickAdd = { text ->
                            val p = Parser.parse(text, System.currentTimeMillis(), data.lists)
                            val t = Todo(title = p.title, start = p.due, rule = p.rule, listId = p.listId ?: filter, nag = p.nag)
                            Ops.save(ctx, t)
                            val desc = (if (t.rule.repeats) Recur.describe(t.rule, t.start) + " · " else fmtDay(t.due) + " · ") + fmtTime(t.due)
                            undoable("Set: $desc", t, "Edit") { editing = Editing(TodoStore.get(ctx, t.id) ?: t, false) }
                        })
                    Tab.CALENDAR -> CalendarScreen(data) { editing = Editing(it, false) }
                    Tab.HISTORY -> HistoryScreen(data)
                }
            }
        }

        AnimatedVisibility(editing != null) {
            editing?.let { e ->
                EditorScreen(e.todo, e.isNew, data.lists, e.heard,
                    onSave = { t -> Ops.save(ctx, t); editing = null
                        Toast.makeText(ctx, "Alarm set · " + (if (t.rule.repeats) Recur.describe(t.rule, t.start) + " · " else fmtDay(t.due) + " ") + fmtTime(t.due), Toast.LENGTH_SHORT).show() },
                    onDelete = { val t = e.todo; Ops.delete(ctx, t); editing = null; undoable("Deleted: ${t.title}", t) },
                    onSkip = { Ops.skip(ctx, e.todo); editing = null
                        TodoStore.get(ctx, e.todo.id)?.let { n -> Toast.makeText(ctx, if (n.done) "That was the last one" else "Skipped · next ${fmtDay(n.due)} ${fmtTime(n.due)}", Toast.LENGTH_SHORT).show() } },
                    onClose = { editing = null })
            }
        }
        if (showLists) ListsScreen(data) { showLists = false }
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Ink.surface).border(width = 1.dp, color = Ink.line).navigationBarsPadding().height(60.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onTab(t) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Ink.accent else Color.Transparent))
                Spacer(Modifier.height(8.dp))
                Text(t.label, color = if (on) Ink.text else Ink.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MicGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val u = size.minDimension / 24f
        val sw = 2f * u
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(9f * u, 3f * u), size = androidx.compose.ui.geometry.Size(6f * u, 11f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(color, 0f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(5.5f * u, 6.5f * u), size = androidx.compose.ui.geometry.Size(13f * u, 11f * u),
            style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawLine(color, androidx.compose.ui.geometry.Offset(12f * u, 17.5f * u), androidx.compose.ui.geometry.Offset(12f * u, 21f * u), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// =========================================================================================
// Todos
// =========================================================================================
@SuppressLint("BatteryLife")
@Composable
private fun rememberChecks(): List<Check> {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(ctx).areNotificationsEnabled()) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    return remember(tick) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val pkg = Uri.parse("package:${ctx.packageName}")
        fun open(i: Intent) = runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        listOf(
            Check("Notifications", "Needed to show the alarm screen", NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            },
            Check("Full-screen alarm", "Lets Ringdo take over the lock screen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) {
                if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            },
            Check("Exact alarms", "Rings on the exact minute", AlarmScheduler.canScheduleExact(ctx)) {
                if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            },
            Check("Unrestricted battery", "Stops the phone from killing alarms", pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            },
        )
    }
}

@Composable
private fun TodosScreen(
    data: AppData, filter: String?, onFilter: (String?) -> Unit, onOpen: (Todo) -> Unit, onManageLists: () -> Unit,
    onComplete: (Todo) -> Unit, onDelete: (Todo) -> Unit, onQuickAdd: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val checks = rememberChecks()
    val allOk = checks.all { it.ok }
    var showDone by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }
    val listsById = data.lists.associateBy { it.id }

    val visible = data.todos.filter { !it.isTest && (filter == null || it.listId == filter) }
    val open = visible.filter { !it.done }.sortedBy { it.due }
    val done = visible.filter { it.done }.sortedByDescending { it.completedAt ?: it.due }
    val today = LocalDate.now(Recur.zone)
    val groups = open.groupBy { t ->
        val d = Recur.date(t.due)
        when {
            t.fired || t.due < now -> "Needs attention"
            d == today -> "Today"
            d == today.plusDays(1) -> "Tomorrow"
            d.isBefore(today.plusDays(7)) -> "This week"
            else -> "Later"
        }
    }
    val order = listOf("Needs attention", "Today", "Tomorrow", "This week", "Later")

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AlarmGlyph(Ink.accent, 30.dp, check = true, checkColor = Ink.text)
                Spacer(Modifier.width(10.dp))
                Text("Ringdo", color = Ink.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text(android.text.format.DateFormat.format("HH:mm:ss", now).toString(), color = Ink.muted, fontFamily = Mono, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text("Test", color = Ink.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    val t = Todo(title = "Test alarm — this is how Ringdo rings", start = System.currentTimeMillis() + 10_000, isTest = true)
                    TodoStore.upsert(ctx, t); AlarmScheduler.schedule(ctx, t)
                    Toast.makeText(ctx, "Rings in 10 seconds — lock your phone to try it", Toast.LENGTH_SHORT).show()
                }.border(1.dp, Ink.line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        if (!allOk) item { SetupCard(checks) }

        // quick add (typed natural language)
        item {
            val shape = RoundedCornerShape(14.dp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(Ink.surface).border(1.dp, Ink.line, shape).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(quick, { quick = it.take(200) }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Ink.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onDone = { if (quick.isNotBlank()) { onQuickAdd(quick); quick = "" } }),
                    decorationBox = { inner -> Box(Modifier.padding(vertical = 15.dp)) { if (quick.isEmpty()) Text("Quick add: “pay rent on the 1st of every month”", color = Ink.faint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } },
                    modifier = Modifier.weight(1f))
                if (quick.isNotBlank()) Text("Add", color = Ink.accent, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onQuickAdd(quick); quick = "" }.padding(10.dp))
            }
        }

        // list filter
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("All · ${data.todos.count { !it.done && !it.isTest }}", { onFilter(null) }, selected = filter == null)
                data.lists.forEach { l ->
                    ColorPill("${l.name} · ${data.todos.count { !it.done && !it.isTest && it.listId == l.id }}", l.color, filter == l.id) { onFilter(if (filter == l.id) null else l.id) }
                }
                Pill("+ Lists", onManageLists)
            }
        }

        if (open.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Ink.line, RoundedCornerShape(14.dp)).padding(28.dp), contentAlignment = Alignment.Center) {
                Text("Nothing to ring here.\nTap + or the mic to add a todo.", color = Ink.faint, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        }
        order.forEach { g ->
            val items = groups[g] ?: return@forEach
            item(key = "h_$g") { GroupHead(g, items.size, warn = g == "Needs attention") }
            items(items, key = { it.id }) { t -> SwipeRow(t, listsById[t.listId], now, onOpen, onComplete, onDelete) }
        }
        if (open.isNotEmpty()) item { Text("Tip: swipe right to complete · swipe left to delete · tap to edit", color = Ink.faint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center) }
        if (done.isNotEmpty()) {
            item(key = "h_done") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(8.dp)).clickable { showDone = !showDone }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("COMPLETED", color = Ink.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    Text("${done.size} · ${if (showDone) "hide" else "show"}", color = Ink.faint, fontSize = 12.sp)
                }
            }
            if (showDone) items(done.take(50), key = { it.id }) { t -> SwipeRow(t, listsById[t.listId], now, onOpen, onComplete = { Ops.restore(ctx, it.copy(done = false, fired = it.due < System.currentTimeMillis(), completedAt = null)) }, onDelete) }
        }
    }
}

@Composable
private fun SetupCard(checks: List<Check>) {
    Panel {
        Text("Set up loud alarms", color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Allow these once so alarms ring even when the app is closed.", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        checks.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (c.ok) Ink.ok else Ink.warn, CircleShape)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.label, color = Ink.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    Text(c.why, color = Ink.faint, fontSize = 12.sp)
                }
                if (!c.ok) BigButton("Allow", small = true, kind = 1, onClick = c.fix) else Text("On", color = Ink.ok, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GroupHead(title: String, n: Int, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title.uppercase(), color = if (warn) Ink.warn else Ink.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        Text("$n", color = Ink.faint, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(t: Todo, list: TodoList?, now: Long, onOpen: (Todo) -> Unit, onComplete: (Todo) -> Unit, onDelete: (Todo) -> Unit) {
    val current by rememberUpdatedState(t)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { onComplete(current); false }
            SwipeToDismissBoxValue.EndToStart -> { onDelete(current); false }
            else -> false
        }
    }, positionalThreshold = { it * 0.35f })
    SwipeToDismissBox(state, backgroundContent = {
        val dir = state.dismissDirection
        val (bg, label, align) = when (dir) {
            SwipeToDismissBoxValue.StartToEnd -> Triple(Ink.ok, if (t.done) "↺  Not done" else "✓  Done", Alignment.CenterStart)
            SwipeToDismissBoxValue.EndToStart -> Triple(Color(0xFFE5484D), "Delete  ✕", Alignment.CenterEnd)
            else -> Triple(Color.Transparent, "", Alignment.Center)
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 20.dp), contentAlignment = align) {
            Text(label, color = Ink.bg, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }) { TodoRow(t, list, now, onOpen, onComplete) }
}

@Composable
private fun TodoRow(t: Todo, list: TodoList?, now: Long, onOpen: (Todo) -> Unit, onComplete: (Todo) -> Unit) {
    val late = !t.done && (t.due < now || t.fired)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Ink.surface).border(1.dp, if (late) Color(0xFF5A2E20) else Ink.line, shape)
            .clickable { onOpen(t) }.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (t.done) Ink.ok else Color.Transparent)
            .border(2.dp, if (t.done) Ink.ok else list?.let { Color(it.color) } ?: Ink.line, CircleShape)
            .clickable { onComplete(t) }, contentAlignment = Alignment.Center) {
            if (t.done) Text("✓", color = Ink.bg, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (t.done) 0.55f else 1f)) {
            Text(t.title, color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 15.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null)
            if (t.notes.isNotBlank()) Text(t.notes, color = Ink.faint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Meta("${fmtDay(t.due)} · ${fmtTime(t.due)}", Ink.muted)
                if (!t.done) Meta(if (t.fired) "not done" else countdown(t.due - now), if (late) Ink.warn else Ink.accent)
                if (t.rule.repeats) Meta("↻ " + Recur.describe(t.rule, t.start), Ink.muted)
                if (t.subtasks.isNotEmpty()) Meta("☑ ${t.subtasks.count { it.done }}/${t.subtasks.size}", Ink.muted)
                if (t.nag) Meta("nag ${t.nagMin}m", Ink.warn)
                list?.let { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) { ListDot(l.color, 7.dp); Spacer(Modifier.width(4.dp)); Meta(l.name, Ink.muted) }
                }
            }
        }
    }
}

@Composable private fun Meta(s: String, c: Color) = Text(s, color = c, fontFamily = Mono, fontSize = 11.5.sp)

// =========================================================================================
// Calendar
// =========================================================================================
@Composable
private fun CalendarScreen(data: AppData, onOpen: (Todo) -> Unit) {
    val today = LocalDate.now(Recur.zone)
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var sel by remember { mutableStateOf(today) }
    val listsById = data.lists.associateBy { it.id }
    val active = data.todos.filter { !it.isTest && !it.done }

    // occurrences for the visible month
    val first = month.atDay(1); val last = month.atEndOfMonth()
    val occ: Map<LocalDate, List<Pair<Todo, Long>>> = remember(data, month) {
        val from = Recur.ms(first, LocalTime.MIN); val to = Recur.ms(last, LocalTime.MAX)
        active.flatMap { t -> Recur.occurrences(t, from, to).map { t to it } }.groupBy { Recur.date(it.second) }
    }
    val doneOn: Map<LocalDate, List<HistoryEvent>> = remember(data) { data.history.filter { it.type == EventType.DONE }.groupBy { Recur.date(it.at) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(month.month.getDisplayName(JTextStyle.FULL, Locale.getDefault()) + " " + month.year, color = Ink.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f))
                NavBtn("‹") { month = month.minusMonths(1) }
                Spacer(Modifier.width(6.dp))
                Text("Today", color = Ink.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { month = YearMonth.from(today); sel = today }.padding(8.dp))
                Spacer(Modifier.width(6.dp))
                NavBtn("›") { month = month.plusMonths(1) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, color = Ink.faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
            val lead = first.dayOfWeek.value - 1
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val idx = r * 7 + c - lead
                        if (idx < 0 || idx >= month.lengthOfMonth()) { Spacer(Modifier.weight(1f).height(52.dp)); continue }
                        val d = month.atDay(idx + 1)
                        val isSel = d == sel; val isToday = d == today
                        val dots = occ[d].orEmpty().map { listsById[it.first.listId]?.color ?: 0xFFA79A8D }.distinct().take(3)
                        Column(Modifier.weight(1f).height(52.dp).padding(2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) Ink.accent else if (isToday) Ink.surface2 else Color.Transparent)
                            .clickable { sel = d }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("${d.dayOfMonth}", color = if (isSel) Ink.accentInk else if (d.isBefore(today)) Ink.faint else Ink.text,
                                fontWeight = if (isToday || isSel) FontWeight.Black else FontWeight.Medium, fontSize = 15.sp)
                            Spacer(Modifier.height(3.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(6.dp)) {
                                dots.forEach { Box(Modifier.size(5.dp).background(if (isSel) Ink.accentInk else Color(it), CircleShape)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(fmtDay(Recur.ms(sel, LocalTime.NOON)).let { if (it.length < 10) it + " · " + sel.dayOfMonth + " " + sel.month.getDisplayName(JTextStyle.SHORT, Locale.getDefault()) else it },
                color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
        }
        val dayItems = (if (YearMonth.from(sel) == month) occ[sel] else null).orEmpty().sortedBy { it.second }
        val dayDone = doneOn[sel].orEmpty()
        if (dayItems.isEmpty() && dayDone.isEmpty()) item {
            Text("Nothing rings on this day.", color = Ink.faint, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        items(dayItems, key = { it.first.id + it.second }) { (t, at) ->
            val l = listsById[t.listId]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Ink.surface).border(1.dp, Ink.line, RoundedCornerShape(12.dp))
                .clickable { onOpen(t) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(at), color = Ink.accent, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(78.dp))
                Box(Modifier.width(3.dp).height(30.dp).background(l?.let { Color(it.color) } ?: Ink.line, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(l?.name, if (t.rule.repeats) Recur.describe(t.rule, t.start) else null).joinToString(" · ").ifEmpty { "Once" }, color = Ink.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (dayDone.isNotEmpty()) {
            item { Text("COMPLETED", color = Ink.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }
            items(dayDone, key = { "d" + it.id }) { e ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(e.at), color = Ink.faint, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(78.dp))
                    Text("✓  " + e.title, color = Ink.muted, fontSize = 14.sp, textDecoration = TextDecoration.LineThrough)
                }
            }
        }
    }
}

@Composable private fun NavBtn(s: String, onClick: () -> Unit) =
    Box(Modifier.size(38.dp).clip(CircleShape).border(1.dp, Ink.line, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(s, color = Ink.text, fontSize = 20.sp) }

// =========================================================================================
// History
// =========================================================================================
@Composable
private fun HistoryScreen(data: AppData) {
    val today = LocalDate.now(Recur.zone)
    val weekStart = today.with(DayOfWeek.MONDAY)
    val weekMs = Recur.ms(weekStart, LocalTime.MIN)
    val week = data.history.filter { it.at >= weekMs }
    val listsById = data.lists.associateBy { it.id }
    // streak: consecutive days (ending today or yesterday) with at least one DONE
    val doneDays = data.history.filter { it.type == EventType.DONE }.map { Recur.date(it.at) }.toSet()
    var streak = 0; var d = if (today in doneDays) today else today.minusDays(1)
    while (d in doneDays) { streak++; d = d.minusDays(1) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("History", color = Ink.text, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text("This week", color = Ink.muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Done", week.count { it.type == EventType.DONE }, Ink.ok, Modifier.weight(1f))
                Stat("Missed", week.count { it.type == EventType.MISSED || it.type == EventType.STOPPED }, Ink.warn, Modifier.weight(1f))
                Stat("Snoozed", week.count { it.type == EventType.SNOOZED }, Ink.accent, Modifier.weight(1f))
                Stat("Streak", streak, Ink.text, Modifier.weight(1f), suffix = "d")
            }
        }
        if (data.history.isEmpty()) item {
            Text("Every alarm you complete, snooze or miss shows up here.", color = Ink.faint, fontSize = 14.sp, modifier = Modifier.padding(vertical = 20.dp))
        }
        data.history.groupBy { Recur.date(it.at) }.forEach { (day, evs) ->
            item(key = "hd$day") { GroupHead(fmtDay(Recur.ms(day, LocalTime.NOON)), evs.size) }
            items(evs, key = { it.id }) { e ->
                val (label, color) = when (e.type) {
                    EventType.DONE -> "Done" to Ink.ok
                    EventType.STOPPED -> "Stopped, not done" to Ink.warn
                    EventType.MISSED -> "No response" to Color(0xFFFF6B6B)
                    EventType.SNOOZED -> "Snoozed" to Ink.accent
                    EventType.NAGGED -> "Paused, will nag" to Ink.warn
                    EventType.SKIPPED -> "Skipped" to Ink.muted
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color, CircleShape)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = Ink.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(label, listsById[e.listId]?.name).joinToString(" · "), color = Ink.faint, fontSize = 12.sp)
                    }
                    Text(fmtTime(e.at), color = Ink.muted, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, n: Int, c: Color, modifier: Modifier, suffix: String = "") {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Ink.surface).border(1.dp, Ink.line, RoundedCornerShape(12.dp)).padding(vertical = 12.dp, horizontal = 10.dp)) {
        Text("$n$suffix", color = c, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(label, color = Ink.muted, fontSize = 12.sp)
    }
}

// =========================================================================================
// Lists manager
// =========================================================================================
@Composable
private fun ListsScreen(data: AppData, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableLongStateOf(ListColors[4]) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) { Text("✕", color = Ink.muted, fontSize = 18.sp) }
            Text("Lists", color = Ink.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 4.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(data.lists, key = { it.id }) { l ->
                val count = data.todos.count { it.listId == l.id && !it.done }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ListDot(l.color, 12.dp); Spacer(Modifier.width(12.dp))
                        if (editingId == l.id) {
                            BasicTextField(editName, { editName = it.take(24) }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 16.sp),
                                cursorBrush = SolidColor(Ink.accent), modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (editName.isNotBlank()) TodoStore.upsertList(ctx, l.copy(name = editName.trim())); editingId = null }))
                            Text("Save", color = Ink.accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if (editName.isNotBlank()) TodoStore.upsertList(ctx, l.copy(name = editName.trim())); editingId = null }.padding(8.dp))
                        } else {
                            Column(Modifier.weight(1f).clickable { editingId = l.id; editName = l.name }) {
                                Text(l.name, color = Ink.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("$count open · tap to rename", color = Ink.faint, fontSize = 12.sp)
                            }
                            Text("Delete", color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.clickable {
                                TodoStore.removeList(ctx, l.id); Toast.makeText(ctx, "Deleted “${l.name}” — its todos moved to No list", Toast.LENGTH_SHORT).show()
                            }.padding(8.dp))
                        }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListColors.forEach { c ->
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (c == l.color) Ink.text else Color.Transparent, CircleShape)
                                .clickable { TodoStore.upsertList(ctx, l.copy(color = c)) })
                        }
                    }
                }
            }
            item {
                SectionLabel("New list")
                Panel {
                    BasicTextField(newName, { newName = it.take(24) }, singleLine = true, textStyle = TextStyle(color = Ink.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(Ink.accent), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        decorationBox = { inner -> if (newName.isEmpty()) Text("e.g. Shopping, Bills, Kids", color = Ink.faint, fontSize = 16.sp); inner() },
                        modifier = Modifier.fillMaxWidth())
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListColors.forEach { c ->
                            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (c == newColor) Ink.text else Color.Transparent, CircleShape).clickable { newColor = c })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BigButton("Add list", Modifier.fillMaxWidth(), kind = 1, small = true) {
                        val n = newName.trim()
                        if (n.isEmpty()) return@BigButton
                        if (data.lists.any { it.name.equals(n, true) }) { Toast.makeText(ctx, "You already have “$n”", Toast.LENGTH_SHORT).show(); return@BigButton }
                        TodoStore.upsertList(ctx, TodoList(newId(), n, newColor)); newName = ""
                    }
                    Text("Tip: say or type “#${data.lists.firstOrNull()?.name?.lowercase() ?: "work"}” or “in my work list” to file a todo by voice.", color = Ink.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}
