package sg.bus.core

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BusTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun t(s: String) = OffsetDateTime.parse(s).toInstant()

    private val avenueVilla = Stop("42139", "Avenue Villa", "Sixth Ave", lat = 1.32878, lon = 103.79444)
    private val office = Stop("03239", "Shenton Way Ter", "Shenton Way", lat = 1.2765, lon = 103.8466)
    private val stops = mapOf("42139" to avenueVilla, "03239" to office)
    private val home = Registration("42139", listOf("970", "156"))
    private val work = Registration("03239", listOf("970"))

    @Test fun parsesRealResponseAndDropsDuplicateSubsequent() {
        val a = parseArrivals(res("arrivals_42139_2026-10-29.json"), "42139")
        assertEquals(setOf("156", "77", "970"), a.services.keys)
        // next / subsequent / next2 / next3 → subsequent duplicates next2, so 3 buses not 4
        assertEquals(3, a.services["156"]!!.size)
        val b970 = a.services["970"]!!
        assertEquals(listOf(t("2026-10-29T23:19:24+08:00"), t("2026-10-29T23:39:31+08:00")), b970.map { it.at })
        assertTrue(b970[0].monitored)
        assertTrue(!b970[1].monitored)
        assertEquals(Load.SEA, b970[0].load)
    }

    @Test fun badResponsesAreLoud() {
        assertFailsWith<ApiException> { parseArrivals("<html>blocked</html>", "42139") }
        assertFailsWith<ApiException> { parseArrivals("""{"error":"x"}""", "42139") }
    }

    @Test fun etaLabels() {
        val now = t("2026-10-29T23:00:00+08:00")
        assertEquals("Arr", etaLabel(Bus(now.plusSeconds(59), true, null), now))
        assertEquals("Arr", etaLabel(Bus(now.minusSeconds(10), true, null), now))
        assertEquals("3", etaLabel(Bus(now.plusSeconds(200), true, null), now))
        assertEquals("~12", etaLabel(Bus(now.plusSeconds(12 * 60 + 5), false, null), now))
        val gone = Bus(now.minusSeconds(31), true, null)
        assertEquals(emptyList(), upcoming(listOf(gone), now))
    }

    @Test fun nearestRegisteredStopFollowsLocation() {
        // standing at Avenue Villa → home; near Shenton Way → office
        assertEquals("42139", rankStops(1.3289, 103.7946, listOf(work, home), stops).first().reg.stop)
        assertEquals("03239", rankStops(1.2770, 103.8460, listOf(home, work), stops).first().reg.stop)
        val d = rankStops(1.3289, 103.7946, listOf(home, work), stops).first().distanceM!!
        assertTrue(d < 50, "distance $d")
    }

    @Test fun hysteresisKeepsPreviousStopUnlessClearlyCloser() {
        val a = Stop("A", "A", "", 1.30000, 103.80000)
        val b = Stop("B", "B", "", 1.30000, 103.80100)   // ~111 m east of A
        val s = mapOf("A" to a, "B" to b)
        val regs = listOf(Registration("A", listOf("1")), Registration("B", listOf("2")))
        val nearMid = 103.80060                            // 67 m from A, 44 m from B: B only 22 m closer
        assertEquals("A", rankStops(1.3, nearMid, regs, s, previous = "A").first().reg.stop)
        assertEquals("B", rankStops(1.3, nearMid, regs, s).first().reg.stop)
        assertEquals("B", rankStops(1.3, 103.80100, regs, s, previous = "A").first().reg.stop) // 111 m closer
    }

    @Test fun noFixFallsBackToPreviousThenRegistrationOrder() {
        assertEquals("03239", rankStops(null, null, listOf(home, work), stops, previous = "03239").first().reg.stop)
        assertEquals("42139", rankStops(null, null, listOf(home, work), stops).first().reg.stop)
        assertNull(rankStops(null, null, listOf(home), stops).first().distanceM)
        assertEquals(emptyList(), rankStops(1.0, 103.0, emptyList(), stops))
    }

    @Test fun stopDirectoryAndSearch() {
        val all = parseStops("""{"42139":[103.79444,1.32878,"Avenue Villa","Sixth Ave"],"42131":[103.7946,1.3285,"Opp Avenue Villa","Sixth Ave"]}""")
        assertEquals(1.32878, all["42139"]!!.lat)
        assertEquals(listOf("42139"), searchStops("42139", all).map { it.code })
        assertEquals(2, searchStops("avenue villa", all).size)
        assertEquals(listOf("42131"), searchStops("opp sixth", all).map { it.code })
        assertEquals("42139", nearbyStops(1.32878, 103.79444, all.values, 1).first().first.code)
    }

    @Test fun servicesAtStopFromRoutes() {
        val m = parseStopServices("""{"970":{"routes":[["45009","42139"],["42131"]]},"156":{"routes":[["42139"]]},"10e":{"routes":[["42139"]]},"2":{"routes":[["42139"]]}}""")
        assertEquals(listOf("2", "10e", "156", "970"), m["42139"])
        assertEquals(listOf("970"), m["42131"])
    }

    @Test fun configRoundTrips() {
        val c = WidgetConfig(pinned = work)
        assertEquals(c, configJson.decodeFromString<WidgetConfig>(configJson.encodeToString(WidgetConfig.serializer(), c)))
        assertNull(configJson.decodeFromString<WidgetConfig>("{}").pinned)
        assertEquals("120 m", distanceLabel(124.0))
        assertEquals("1.4 km", distanceLabel(1440.0))
        assertEquals(Instant.parse("2026-10-29T15:15:00Z"), Session.endFrom(Instant.parse("2026-10-29T15:00:00Z")))
    }

    @Test fun savedBusRoundTrips() {
        val b = Bus(Instant.parse("2026-10-29T15:19:24Z"), monitored = false, load = Load.SDA)
        assertEquals(b, b.saved().bus())
        val st = WidgetState(views = listOf(StopView("42139", listOf("970"), 30.0, mapOf("970" to listOf(b.saved())))), sessionEndMs = 5)
        assertEquals(st, configJson.decodeFromString<WidgetState>(configJson.encodeToString(WidgetState.serializer(), st)))
    }
}
