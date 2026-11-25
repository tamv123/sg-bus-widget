package sg.bus.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Anonymous usage counts, kept on the phone per Singapore day.
 *
 * Only these numbers ever leave the phone, plus a random install id, the app version and a debug flag.
 * NEVER location, stop codes, bus numbers, widget settings or anything about the device.
 * Each day's running totals are re-sent (overwriting the same record) on every tap or app open, so
 * yesterday's record is complete as soon as its last activity happened with a signal.
 */
@Serializable
data class UsageDay(
    val day: String,
    val taps: Int = 0,
    val appOpens: Int = 0,
    val widgetsAdded: Int = 0,
    val widgetsRemoved: Int = 0,
    val widgetsSmall: Int = 0,
    val widgetsLarge: Int = 0,
)

@Serializable
data class UsageLog(
    val days: Map<String, UsageDay> = emptyMap(),
    /** days changed since they were last sent */
    val dirty: Set<String> = emptySet(),
    /** widget ids we already know about; null until the first sync, which records a baseline without counting */
    val knownWidgets: Set<Int>? = null,
)

fun usageDay(now: Instant): String = now.atZone(SGT).toLocalDate().toString()

private fun UsageLog.change(day: String, f: (UsageDay) -> UsageDay) =
    copy(days = days + (day to f(days[day] ?: UsageDay(day))), dirty = dirty + day)

fun UsageLog.tap(day: String) = change(day) { it.copy(taps = it.taps + 1) }
fun UsageLog.appOpen(day: String) = change(day) { it.copy(appOpens = it.appOpens + 1) }

/**
 * Compare the widgets on the home screen now with the ones we knew: new ids were added, missing ids were removed.
 * Also stores how many of each size are live today. The first sync (after install or update) is a baseline only,
 * so widgets placed before counting existed aren't reported as new.
 */
fun UsageLog.syncWidgets(day: String, small: Set<Int>, large: Set<Int>): UsageLog {
    val now = small + large
    val known = knownWidgets
    val added = if (known == null) 0 else (now - known).size
    val removed = if (known == null) 0 else (known - now).size
    val today = days[day] ?: UsageDay(day)
    val next = today.copy(
        widgetsAdded = today.widgetsAdded + added, widgetsRemoved = today.widgetsRemoved + removed,
        widgetsSmall = small.size, widgetsLarge = large.size,
    )
    if (next == today && known == now) return this
    return copy(days = days + (day to next), dirty = dirty + day, knownWidgets = now)
}

fun UsageLog.sent(day: String, version: UsageDay) =
    // only clear the flag if nothing changed while the upload was in flight
    if (days[day] == version) copy(dirty = dirty - day) else this

/** Keep two weeks: enough to retry an offline day, small enough to stay tiny. */
fun UsageLog.pruned(today: String, keepDays: Long = 14): UsageLog {
    val oldest = LocalDate.parse(today).minusDays(keepDays).toString()
    return copy(days = days.filterKeys { it >= oldest }, dirty = dirty.filterTo(mutableSetOf()) { it >= oldest })
}

/** Firestore document id: one record per install per day, so a re-send overwrites instead of double-counting. */
fun usageDocId(install: String, day: String) = "${install}_$day"

/** Server deletes the record (Firestore TTL on expireAt) about 13 months after its day. */
fun usageExpiry(day: String): Instant = LocalDate.parse(day).plusDays(400).atStartOfDay().toInstant(ZoneOffset.UTC)

/** Firestore REST body. The field list must match firestore.rules exactly, or the write is refused. */
fun usageFirestoreJson(install: String, d: UsageDay, app: String, debug: Boolean): JsonObject = buildJsonObject {
    putJsonObject("fields") {
        fun str(k: String, v: String) = putJsonObject(k) { put("stringValue", v) }
        fun int(k: String, v: Int) = putJsonObject(k) { put("integerValue", v.toString()) }
        str("install", install)
        str("day", d.day)
        str("app", app)
        putJsonObject("debug") { put("booleanValue", debug) }
        int("schema", 1)
        int("taps", d.taps)
        int("appOpens", d.appOpens)
        int("widgetsAdded", d.widgetsAdded)
        int("widgetsRemoved", d.widgetsRemoved)
        int("widgetsSmall", d.widgetsSmall)
        int("widgetsLarge", d.widgetsLarge)
        putJsonObject("expireAt") { put("timestampValue", usageExpiry(d.day).toString()) }
    }
}
