package app.aaps.plugins.sync.phoneSteps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepCounterHistoryTest {

    private val start = 1_800_000_000_000L
    private fun min(m: Int): Long = m * 60_000L
    private fun sec(s: Int): Long = s * 1_000L

    @Test
    fun `the first reading only sets the starting point`() {
        val history = StepCounterHistory()
        history.add(start, 52_000)

        assertTrue(history.hasData())
        assertEquals(0, history.stepsIn(start, min(15)))
    }

    @Test
    fun `no readings means no steps`() {
        val history = StepCounterHistory()

        assertFalse(history.hasData())
        assertEquals(0, history.stepsIn(start, min(15)))
    }

    @Test
    fun `steady readings give the steps of each window`() {
        val history = StepCounterHistory()
        // 100 steps every minute for 60 minutes
        for (m in 0..60) history.add(start + min(m), 10_000L + 100L * m)
        val now = start + min(60)

        assertEquals(500, history.stepsIn(now, min(5)))
        assertEquals(1_000, history.stepsIn(now, min(10)))
        assertEquals(1_500, history.stepsIn(now, min(15)))
        assertEquals(3_000, history.stepsIn(now, min(30)))
        assertEquals(6_000, history.stepsIn(now, min(60)))
        // The window reaches back past the first reading: only what is known is counted.
        assertEquals(6_000, history.stepsIn(now, min(180)))
    }

    @Test
    fun `a jump after a long silence is put at walking pace before the reading`() {
        val history = StepCounterHistory()
        history.add(start, 20_000)
        // Two hours without a reading while the phone slept, then the steps of a 15 minute walk at once.
        val now = start + min(120)
        history.add(now, 20_000L + 15 * StepCounterHistory.STEPS_PER_MINUTE)

        assertEquals(1_650, history.stepsIn(now, min(15)))
        assertEquals(550, history.stepsIn(now, min(5)))
        assertEquals(1_650, history.stepsIn(now, min(60)))
        // Before the walk the total did not change.
        assertEquals(0, history.stepsIn(now - min(16), min(60)))
    }

    @Test
    fun `a jump that is longer than the silence is spread over the silence`() {
        val history = StepCounterHistory()
        history.add(start, 0)
        // 5 minutes of silence but 1100 steps: more than walking pace can fit, so all 5 minutes were walking.
        history.add(start + min(5), 1_100)

        assertEquals(1_100, history.stepsIn(start + min(5), min(5)))
        assertEquals(660, history.stepsIn(start + min(5), min(3)))
    }

    @Test
    fun `a counter that starts again from zero adds on top`() {
        val history = StepCounterHistory()
        history.add(start, 1_000)
        history.add(start + min(1), 1_100)
        // The phone restarted: the counter is small again.
        history.add(start + min(2), 50)

        assertEquals(150, history.stepsIn(start + min(2), min(5)))
    }

    @Test
    fun `a late reading still counts`() {
        val history = StepCounterHistory()
        history.add(start, 0)
        history.add(start + min(2), 200)
        // Arrives after the reading above, measured before it.
        history.add(start + min(1), 300)

        assertEquals(300, history.stepsIn(start + min(2), min(5)))
    }

    @Test
    fun `readings every second are counted in full`() {
        val history = StepCounterHistory()
        history.add(start, 0)
        // 2 steps per second for 10 minutes, the screen on, a reading for every change.
        for (s in 1..600) history.add(start + min(1) + sec(s), 2L * s)
        val now = start + min(11)

        assertEquals(1_200, history.stepsIn(now, min(15)))
        assertEquals(600, history.stepsIn(now, min(5)))
    }

    @Test
    fun `steps after the newest reading are not counted yet`() {
        val history = StepCounterHistory()
        history.add(start, 0)
        history.add(start + min(1), 100)

        assertEquals(100, history.stepsIn(start + min(30), min(60)))
        assertEquals(0, history.stepsIn(start + min(30), min(15)))
    }

    @Test
    fun `old points are dropped but the 180 minute window still has its start`() {
        val history = StepCounterHistory()
        // 10 steps a minute for 6 hours
        for (m in 0..360) history.add(start + min(m), 10L * m)
        val now = start + min(360)

        assertEquals(1_800, history.stepsIn(now, min(180)))
        assertEquals(50, history.stepsIn(now, min(5)))
    }

    @Test
    fun `clear forgets everything`() {
        val history = StepCounterHistory()
        history.add(start, 0)
        history.add(start + min(5), 500)
        history.clear()

        assertFalse(history.hasData())
        // After clear the next reading is only a new starting point again.
        history.add(start + min(6), 900)
        assertEquals(0, history.stepsIn(start + min(6), min(15)))
    }
}
