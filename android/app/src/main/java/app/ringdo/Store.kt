package app.ringdo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AppData(
    val todos: List<Todo> = emptyList(),
    val lists: List<TodoList> = emptyList(),
    val history: List<HistoryEvent> = emptyList(),
)

val DefaultLists = listOf(
    TodoList("personal", "Personal", 0xFFFF6A3D),
    TodoList("work", "Work", 0xFF6AA8FF),
    TodoList("health", "Health", 0xFF7BD88F),
    TodoList("home", "Home", 0xFFFFC857),
)

val ListColors = listOf(0xFFFF6A3D, 0xFF6AA8FF, 0xFF7BD88F, 0xFFFFC857, 0xFFC792EA, 0xFFFF7EB6, 0xFF4FD1C5, 0xFFB0A090)

/** Persistent store in device-protected storage (alarms work after reboot before first unlock). */
object TodoStore {
    private const val KEY_V1 = "todos"
    private const val KEY = "data_v2"
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) =
        ctx.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("ringdo", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): AppData {
        if (loaded) return _data.value
        val p = prefs(ctx)
        val raw = p.getString(KEY, null)
        _data.value = if (raw != null) runCatching { decode(JSONObject(raw)) }.getOrDefault(AppData(lists = DefaultLists))
        else {
            // migrate Ringdo 1.0 data
            val old = runCatching {
                val arr = JSONArray(p.getString(KEY_V1, "[]")); List(arr.length()) { todoV1(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            AppData(todos = old, lists = DefaultLists).also { p.edit().putString(KEY, encode(it).toString()).apply() }
        }
        loaded = true
        return _data.value
    }

    fun todos(ctx: Context) = load(ctx).todos
    fun get(ctx: Context, id: String) = load(ctx).todos.firstOrNull { it.id == id }

    @Synchronized
    fun mutate(ctx: Context, fn: (AppData) -> AppData) {
        val next = fn(load(ctx))
        _data.value = next
        prefs(ctx).edit().putString(KEY, encode(next).toString()).apply()
    }

    fun upsert(ctx: Context, t: Todo) = mutate(ctx) { d -> d.copy(todos = d.todos.filter { it.id != t.id } + t) }
    fun remove(ctx: Context, id: String) = mutate(ctx) { d -> d.copy(todos = d.todos.filter { it.id != id }) }
    fun log(ctx: Context, t: Todo, type: EventType) {
        if (t.isTest) return
        mutate(ctx) { d -> d.copy(history = (listOf(HistoryEvent(todoId = t.id, title = t.title, listId = t.listId, type = type, at = System.currentTimeMillis())) + d.history).take(600)) }
    }
    fun upsertList(ctx: Context, l: TodoList) = mutate(ctx) { d ->
        val i = d.lists.indexOfFirst { it.id == l.id }
        d.copy(lists = if (i >= 0) d.lists.toMutableList().also { it[i] = l } else d.lists + l)
    }
    fun removeList(ctx: Context, id: String) = mutate(ctx) { d ->
        d.copy(lists = d.lists.filter { it.id != id }, todos = d.todos.map { if (it.listId == id) it.copy(listId = null) else it })
    }

    // ---------------- JSON ----------------
    private fun encode(d: AppData) = JSONObject().apply {
        put("todos", JSONArray().apply { d.todos.forEach { put(todoJson(it)) } })
        put("lists", JSONArray().apply { d.lists.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) } })
        put("history", JSONArray().apply {
            d.history.forEach { put(JSONObject().put("id", it.id).put("todo", it.todoId).put("title", it.title).put("list", it.listId ?: "").put("type", it.type.name).put("at", it.at)) }
        })
    }

    private fun decode(o: JSONObject): AppData {
        fun <T> arr(name: String, f: (JSONObject) -> T): List<T> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() }
        }
        return AppData(
            todos = arr("todos", ::todoFrom),
            lists = arr("lists") { TodoList(it.getString("id"), it.getString("name"), it.getLong("color")) },
            history = arr("history") {
                HistoryEvent(it.getString("id"), it.getString("todo"), it.getString("title"), it.optString("list").ifEmpty { null },
                    EventType.valueOf(it.getString("type")), it.getLong("at"))
            },
        )
    }

    private fun ruleJson(r: Rule) = JSONObject().apply {
        put("freq", r.freq.name); put("n", r.interval); put("days", JSONArray(r.days.sorted())); put("mm", r.monthMode.name)
        r.endEpochDay?.let { put("end", it) }; r.times?.let { put("times", it) }
    }

    private fun ruleFrom(o: JSONObject?): Rule {
        if (o == null) return Rule.NONE
        val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        return Rule(
            freq = runCatching { Freq.valueOf(o.getString("freq")) }.getOrDefault(Freq.NONE),
            interval = o.optInt("n", 1), days = days,
            monthMode = runCatching { MonthMode.valueOf(o.getString("mm")) }.getOrDefault(MonthMode.DAY),
            endEpochDay = if (o.has("end")) o.getLong("end") else null,
            times = if (o.has("times")) o.getInt("times") else null,
        )
    }

    private fun todoJson(t: Todo) = JSONObject().apply {
        put("id", t.id); put("title", t.title); put("notes", t.notes); put("list", t.listId ?: "")
        put("start", t.start); put("occAt", t.occAt); put("due", t.due); put("rule", ruleJson(t.rule)); put("occ", t.occurrence)
        put("nag", t.nag); put("nagMin", t.nagMin); put("tone", t.toneUri ?: "")
        put("subs", JSONArray().apply { t.subtasks.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) } })
        put("done", t.done); put("fired", t.fired); put("snoozes", t.snoozes); put("nags", t.nags)
        t.completedAt?.let { put("completedAt", it) }; put("test", t.isTest); put("created", t.created)
    }

    private fun todoFrom(o: JSONObject): Todo {
        val subs = o.optJSONArray("subs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Sub(it.getString("id"), it.getString("text"), it.optBoolean("done")) } } ?: emptyList()
        val due = o.getLong("due")
        return Todo(
            id = o.getString("id"), title = o.getString("title"), notes = o.optString("notes"), listId = o.optString("list").ifEmpty { null },
            start = o.optLong("start", due), occAt = o.optLong("occAt", due), due = due, rule = ruleFrom(o.optJSONObject("rule")),
            occurrence = o.optInt("occ", 1), nag = o.optBoolean("nag"), nagMin = o.optInt("nagMin", 15),
            toneUri = o.optString("tone").ifBlank { null }, subtasks = subs, done = o.optBoolean("done"), fired = o.optBoolean("fired"),
            snoozes = o.optInt("snoozes"), nags = o.optInt("nags"),
            completedAt = if (o.has("completedAt")) o.getLong("completedAt") else null,
            isTest = o.optBoolean("test"), created = o.optLong("created", System.currentTimeMillis()),
        )
    }

    /** Ringdo 1.0 format: {id,title,due,repeat:NONE|DAILY|WEEKDAYS,tone,done,fired,snoozes,test} */
    private fun todoV1(o: JSONObject): Todo {
        val due = o.getLong("due")
        val rule = when (o.optString("repeat")) {
            "DAILY" -> Rule(Freq.DAILY)
            "WEEKDAYS" -> Rule(Freq.WEEKLY, days = setOf(1, 2, 3, 4, 5))
            else -> Rule.NONE
        }
        return Todo(
            id = o.getString("id"), title = o.getString("title"), start = due, occAt = due, due = due, rule = rule,
            toneUri = o.optString("tone").ifBlank { null }, done = o.optBoolean("done"), fired = o.optBoolean("fired"),
            snoozes = o.optInt("snoozes"), isTest = o.optBoolean("test"), listId = "personal",
        )
    }
}
