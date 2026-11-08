package app.aaps.plugins.sync.phoneSteps

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the readings of a phone step counter into "steps in the last N minutes".
 *
 * A step counter gives one number: all steps since the phone was started. It sends a new number only
 * when the number changes. On many phones it is a non-wake-up sensor without a buffer, so while the
 * phone sleeps its readings are dropped. Nothing is lost from the total, but the steps of a whole walk
 * can arrive as one jump when the phone wakes up.
 *
 * This class keeps a short list of (time, total) points and answers from it. Between two points the
 * steps are spread evenly. A jump that covers a long silence is not spread over the whole silence:
 * the steps are put at walking pace ([STEPS_PER_MINUTE]) just before the reading that brought them.
 * Someone who walked for 15 minutes during a 2 hour silence then shows 15 minutes of steps, not 2
 * hours of slow walking, which would hide the walk from a "steps in the last 15 minutes" rule.
 *
 * A reading lower than the one before means the counter started again from 0 (the phone restarted),
 * so the new reading is added on top.
 *
 * The first reading after [clear] only sets the starting point. Steps before it are unknown and are
 * not counted.
 *
 * Not thread safe: the caller keeps all calls on one thread or behind one lock.
 */
class StepCounterHistory(private val keepMs: Long = KEEP_MS) {

    private data class Point(val timestamp: Long, val total: Long)

    private val points = ArrayDeque<Point>()
    private var lastSensorValue: Long? = null
    private var total = 0L

    /**
     * Adds one reading.
     *
     * @param timestamp when the reading was measured, milliseconds since the epoch
     * @param sensorValue the raw counter value (steps since the phone was started)
     */
    fun add(timestamp: Long, sensorValue: Long) {
        val previousValue = lastSensorValue
        lastSensorValue = sensorValue
        val newSteps = when {
            previousValue == null         -> 0L          // first reading: only a starting point
            sensorValue >= previousValue  -> sensorValue - previousValue
            else                          -> sensorValue // counter started again from 0
        }
        total += newSteps
        val last = points.lastOrNull()
        if (last == null) {
            points.addLast(Point(timestamp, total))
            return
        }
        // A late reading counts, but not before what is already known.
        val time = max(last.timestamp, timestamp)
        val walkStart = time - newSteps * MS_PER_MINUTE / STEPS_PER_MINUTE
        val beforeLast = points.getOrNull(points.size - 2)
        when {
            // A jump after a silence: the total stayed the same until the walk started.
            newSteps > 0 && walkStart - last.timestamp > MIN_POINT_GAP_MS -> {
                points.addLast(Point(walkStart, last.total))
                points.addLast(Point(time, total))
            }

            // The newest point is less than MIN_POINT_GAP_MS after the one before it: move it to this
            // reading instead of adding one. The list stays short while readings come every second, and
            // every point that is kept is still a real reading.
            time == last.timestamp ||
                (beforeLast != null && time - beforeLast.timestamp < MIN_POINT_GAP_MS) -> {
                points.removeLast()
                points.addLast(Point(time, total))
            }

            else                                                           -> points.addLast(Point(time, total))
        }
        prune(time)
    }

    /**
     * Steps taken in the [windowMs] milliseconds before [now].
     * Steps after the newest reading are not known yet and are not counted.
     */
    fun stepsIn(now: Long, windowMs: Long): Int {
        if (points.isEmpty()) return 0
        val steps = totalAt(now) - totalAt(now - windowMs)
        return max(0, steps.roundToInt())
    }

    /** True when there has been at least one reading since the start or since [clear]. */
    fun hasData(): Boolean = points.isNotEmpty()

    /** Forgets everything. The next reading only sets a new starting point. */
    fun clear() {
        points.clear()
        lastSensorValue = null
        total = 0L
    }

    /** The running total at [time]: a straight line between points, flat outside them. */
    private fun totalAt(time: Long): Double {
        val first = points.first()
        if (time <= first.timestamp) return first.total.toDouble()
        val last = points.last()
        if (time >= last.timestamp) return last.total.toDouble()
        var before = first
        for (point in points) {
            if (point.timestamp >= time) {
                val span = point.timestamp - before.timestamp
                if (span <= 0L) return point.total.toDouble()
                return before.total + (point.total - before.total) * (time - before.timestamp).toDouble() / span
            }
            before = point
        }
        return last.total.toDouble()
    }

    /** Drops points older than [keepMs], but keeps one older point so the oldest window still has a start. */
    private fun prune(now: Long) {
        val limit = now - keepMs
        while (points.size > 1 && points[1].timestamp <= limit) points.removeFirst()
    }

    companion object {

        /** Long enough for the longest window the automation condition offers (180 minutes). */
        const val KEEP_MS = 4 * 60 * 60 * 1000L

        /** A normal walking pace. Used only to place the steps of a jump in time. */
        const val STEPS_PER_MINUTE = 110L

        /** Readings closer than this to the newest point are merged into it. */
        const val MIN_POINT_GAP_MS = 10 * 1000L

        private const val MS_PER_MINUTE = 60 * 1000L
    }
}
