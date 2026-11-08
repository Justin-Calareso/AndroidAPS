package app.aaps.implementation.userEntry

import kotlin.test.Test
import kotlin.test.assertEquals

/** The CSV export crashed for every negative offset, because the offset was formatted as a time of day. */
class FormatUtcOffsetTest {

    private val hour = 3_600_000L

    @Test
    fun `west of UTC gets a minus sign and no exception`() {
        assertEquals("-05:00", formatUtcOffset(-5 * hour))
        assertEquals("-04:00", formatUtcOffset(-4 * hour))
        assertEquals("-09:30", formatUtcOffset(-9 * hour - 30 * 60_000L))
    }

    @Test
    fun `UTC and east of UTC get a plus sign`() {
        assertEquals("+00:00", formatUtcOffset(0))
        assertEquals("+01:00", formatUtcOffset(hour))
        assertEquals("+05:30", formatUtcOffset(5 * hour + 30 * 60_000L))
        assertEquals("+14:00", formatUtcOffset(14 * hour))
    }
}
