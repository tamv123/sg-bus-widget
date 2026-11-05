package sg.bus.core

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UsageTest {
    private val d = "2026-11-04"

    @Test fun dayIsSingaporeTime() {
        // 16:30 UTC on the 3rd is 00:30 SGT on the 4th
        assertEquals("2026-11-04", usageDay(OffsetDateTime.parse("2026-11-03T16:30:00Z").toInstant()))
        assertEquals("2026-11-03", usageDay(OffsetDateTime.parse("2026-11-03T15:59:59Z").toInstant()))
    }

    @Test fun tapsAndOpensCountAndMarkTheDayDirty() {
        val log = UsageLog().tap(d).tap(d).appOpen(d)
        assertEquals(UsageDay(d, taps = 2, appOpens = 1), log.days[d])
        assertEquals(setOf(d), log.dirty)
    }

    @Test fun firstWidgetSyncIsABaselineNotNewWidgets() {
        val log = UsageLog().syncWidgets(d, small = setOf(1), large = setOf(2, 3))
        assertEquals(UsageDay(d, widgetsSmall = 1, widgetsLarge = 2), log.days[d])
        assertEquals(setOf(1, 2, 3), log.knownWidgets)
    }

    @Test fun widgetSyncCountsAddsAndRemoves() {
        val log = UsageLog(knownWidgets = setOf(1, 2))
            .syncWidgets(d, small = setOf(1), large = setOf(5))      // 2 removed, 5 added
            .syncWidgets(d, small = setOf(1, 7), large = setOf(5))   // 7 added
        assertEquals(UsageDay(d, widgetsAdded = 2, widgetsRemoved = 1, widgetsSmall = 2, widgetsLarge = 1), log.days[d])
    }

    @Test fun unchangedWidgetSyncIsANoOp() {
        val log = UsageLog(knownWidgets = setOf(1)).syncWidgets(d, setOf(1), emptySet())
        val sent = log.sent(d, log.days[d]!!)
        assertSame(sent, sent.syncWidgets(d, setOf(1), emptySet()))
    }

    @Test fun sentClearsDirtyOnlyIfNothingChangedMeanwhile() {
        val log = UsageLog().tap(d)
        val uploaded = log.days[d]!!
        assertFalse(d in log.sent(d, uploaded).dirty)
        assertTrue(d in log.tap(d).sent(d, uploaded).dirty)   // a tap landed during the upload
    }

    @Test fun prunesDaysOlderThanTwoWeeks() {
        val log = UsageLog().tap("2026-10-20").tap("2026-10-21").tap(d).pruned(d)
        assertEquals(setOf("2026-10-21", d), log.days.keys)
        assertEquals(setOf("2026-10-21", d), log.dirty)
    }

    @Test fun firestoreBodyHasOnlyCountsAndNoPersonalFields() {
        val body = usageFirestoreJson("abc", UsageDay(d, taps = 3), app = "0.4.0", debug = true)
        val f = body["fields"]!!.jsonObject
        assertEquals(
            setOf("install", "day", "app", "debug", "schema", "taps", "appOpens", "widgetsAdded", "widgetsRemoved",
                "widgetsSmall", "widgetsLarge", "expireAt"),
            f.keys,
        )
        assertEquals("3", f["taps"]!!.jsonObject["integerValue"]!!.jsonPrimitive.content)
        assertEquals("2027-12-09T00:00:00Z", f["expireAt"]!!.jsonObject["timestampValue"]!!.jsonPrimitive.content)
        assertEquals("abc_2026-11-04", usageDocId("abc", d))
    }
}
