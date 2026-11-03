package sg.bus.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

val SGT: ZoneId = ZoneId.of("Asia/Singapore")

/** A tap starts a live session: refetch every [INTERVAL] until [LENGTH] after the (latest) tap. */
object Session {
    val LENGTH: Duration = Duration.ofMinutes(15)
    val INTERVAL: Duration = Duration.ofSeconds(20)
    fun endFrom(tap: Instant): Instant = tap + LENGTH
}

const val ARRIVALS_URL = "https://arrivelah2.busrouter.sg/?id="

class ApiException(message: String) : Exception(message)

/** SEA = seats, SDA = standing, LSD = limited standing (LTA's codes). */
enum class Load { SEA, SDA, LSD }

/** One bus on its way. [at] is the absolute arrival time: the anchor, never the next/next2 slot it sits in. */
data class Bus(val at: Instant, val monitored: Boolean, val load: Load?)

/** Live arrivals at one stop: service number → its next buses, soonest first. A service missing = no live info. */
data class Arrivals(val stop: String, val services: Map<String, List<Bus>>)

/** Parses an arrivelah response. Throws [ApiException] on anything we can't trust. */
fun parseArrivals(body: String, stop: String): Arrivals {
    val root = try { Json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
        throw ApiException("unparseable response: ${body.take(80)}")
    }
    val services = root["services"]?.jsonArray ?: throw ApiException("no services field")
    return Arrivals(stop, services.associate { el ->
        val s = el.jsonObject
        val no = s["no"]!!.jsonPrimitive.content.uppercase()
        // arrivelah repeats next2 as "subsequent" — de-duplicate by arrival time
        val buses = listOf("next", "subsequent", "next2", "next3").mapNotNull { s[it] as? JsonObject }
            .mapNotNull(::parseBus).distinctBy { it.at }.sortedBy { it.at }
        no to buses
    })
}

private fun parseBus(o: JsonObject): Bus? {
    val time = o["time"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return Bus(
        at = OffsetDateTime.parse(time).toInstant(),
        monitored = (o["monitored"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
        load = o["load"]?.jsonPrimitive?.contentOrNull?.let { l -> Load.entries.firstOrNull { it.name == l } },
    )
}

/** Buses still worth showing at [now]: drops ones that left more than 30 s ago. */
fun upcoming(buses: List<Bus>, now: Instant): List<Bus> = buses.filter { it.at > now.minusSeconds(30) }

/** Widget label: "Arr" under a minute, whole minutes otherwise; "~" marks a schedule estimate (no GPS on the bus). */
fun etaLabel(bus: Bus, now: Instant): String {
    val min = Duration.between(now, bus.at).toMinutes()
    val text = if (min < 1) "Arr" else "$min"
    return if (bus.monitored) text else "~$text"
}

data class Stop(val code: String, val name: String, val road: String, val lat: Double, val lon: Double)

/** busrouter `stops.min.json`: `{code: [lng, lat, name, road]}`. */
fun parseStops(body: String): Map<String, Stop> =
    Json.parseToJsonElement(body).jsonObject.mapValues { (code, v) ->
        val a = v.jsonArray
        Stop(code, a[2].jsonPrimitive.content, a[3].jsonPrimitive.content, lat = a[1].jsonPrimitive.double, lon = a[0].jsonPrimitive.double)
    }

/** busrouter `services.min.json` → stop code → services calling there (both directions), in bus-number order. */
fun parseStopServices(body: String): Map<String, List<String>> {
    val byStop = mutableMapOf<String, MutableSet<String>>()
    for ((no, v) in Json.parseToJsonElement(body).jsonObject) {
        for (route in v.jsonObject["routes"]?.jsonArray ?: continue)
            for (stop in route.jsonArray) byStop.getOrPut(stop.jsonPrimitive.content) { mutableSetOf() } += no
    }
    return byStop.mapValues { (_, s) -> s.sortedWith(serviceOrder) }
}

/** 2 < 10 < 10e < 970: number first, then suffix (same order as Via's /bus). */
val serviceOrder: Comparator<String> = compareBy<String>({ it.takeWhile(Char::isDigit).toIntOrNull() ?: 9999 }, { it.dropWhile(Char::isDigit) })

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

fun nearbyStops(lat: Double, lon: Double, stops: Collection<Stop>, n: Int = 10): List<Pair<Stop, Double>> =
    stops.map { it to distanceM(lat, lon, it.lat, it.lon) }.sortedBy { it.second }.take(n)

/** A 5-digit code matches exactly; otherwise every word must appear in the name or road. */
fun searchStops(query: String, stops: Map<String, Stop>, n: Int = 20): List<Stop> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    stops[q]?.let { return listOf(it) }
    val words = q.split(Regex("\\s+"))
    return stops.values.filter { s -> val hay = "${s.code} ${s.name} ${s.road}".lowercase(); words.all { it in hay } }
        .sortedBy { it.name }.take(n)
}

/** This list with the item at [index] moved [by] places (-1 = up); out-of-range moves leave it unchanged. */
fun <T> List<T>.moved(index: Int, by: Int): List<T> {
    val to = index + by
    if (index !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(index)) }
}

/** The stops directory is refreshed monthly; a failed try waits a day, so a dead host isn't hit on every tap. */
object DirectoryRefresh {
    val MAX_AGE: Duration = Duration.ofDays(30)
    val RETRY: Duration = Duration.ofDays(1)
    const val MIN_STOPS = 4000   // bundled snapshot has 5208: far fewer means a broken download, not a smaller Singapore

    fun due(lastOkMs: Long, lastTryMs: Long, nowMs: Long): Boolean =
        nowMs - lastOkMs >= MAX_AGE.toMillis() && nowMs - lastTryMs >= RETRY.toMillis()

    /** Throws [ApiException] unless both downloads parse and are about the size we expect; nothing is kept otherwise. */
    fun validate(stopsBody: String, servicesBody: String) {
        val stops = try { parseStops(stopsBody) } catch (e: Exception) { throw ApiException("stops list unreadable") }
        val at = try { parseStopServices(servicesBody) } catch (e: Exception) { throw ApiException("services list unreadable") }
        if (stops.size < MIN_STOPS) throw ApiException("stops list too small (${stops.size})")
        if (at.size < stops.size / 2) throw ApiException("services list too small (${at.size} stops)")
    }
}

/** A stop the user registered, with the services they care about there (in their order). */
@Serializable
data class Registration(val stop: String, val services: List<String>)

/** Per widget instance. [pinned] null = follow the nearest registered stop; else always this stop + services. */
@Serializable
data class WidgetConfig(val pinned: Registration? = null)

/** A chosen stop for one render; [distanceM] null when there's no location fix. */
data class Pick(val reg: Registration, val distanceM: Double?)

/**
 * Registered stops ranked nearest first. The first is the one to show, with hysteresis: [previous] stays on top
 * unless another stop is at least [hysteresisM] closer, so a fix wobbling between two stops doesn't flip the widget.
 * No fix → [previous] (if still registered) or the registration order, without distances.
 */
fun rankStops(
    lat: Double?, lon: Double?, regs: List<Registration>, stops: Map<String, Stop>,
    previous: String? = null, hysteresisM: Double = 50.0,
): List<Pick> {
    if (regs.isEmpty()) return emptyList()
    if (lat == null || lon == null) {
        val first = regs.firstOrNull { it.stop == previous } ?: regs.first()
        return (listOf(first) + (regs - first)).map { Pick(it, null) }
    }
    val ranked = regs.map { r -> Pick(r, stops[r.stop]?.let { distanceM(lat, lon, it.lat, it.lon) }) }
        .sortedBy { it.distanceM ?: Double.MAX_VALUE }
    val prev = ranked.firstOrNull { it.reg.stop == previous } ?: return ranked
    val best = ranked.first()
    val keep = prev.distanceM != null && best.distanceM != null && prev.distanceM - best.distanceM < hysteresisM
    return if (keep) listOf(prev) + (ranked - prev) else ranked
}

/** "120 m" / "1.4 km". */
fun distanceLabel(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(m / 1000)

val configJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// --- what a widget last showed (persisted, so an idle widget can redraw after the process is gone) ---

@Serializable
data class SavedBus(val atMs: Long, val monitored: Boolean, val load: String? = null) {
    fun bus() = Bus(Instant.ofEpochMilli(atMs), monitored, Load.entries.firstOrNull { it.name == load })
}

fun Bus.saved() = SavedBus(at.toEpochMilli(), monitored, load?.name)

/** One stop on a widget: the registered services in order, their buses, and why they're missing if they are. */
@Serializable
data class StopView(
    val stop: String,
    val services: List<String>,
    val distanceM: Double? = null,
    val buses: Map<String, List<SavedBus>> = emptyMap(),
    val error: String? = null,
)

/** Per widget instance. Live while [sessionEndMs] is in the future. [views] = shown stop first, second-nearest next. */
@Serializable
data class WidgetState(
    val views: List<StopView> = emptyList(),
    val fetchedAtMs: Long = 0,
    val sessionEndMs: Long = 0,
    val note: String? = null,
)
