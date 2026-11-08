package app.aaps.plugins.sync.phoneSteps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SC
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.icons.IcActivity
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Counts steps with the phone's own step counter, so automation can use steps without a watch.
 *
 * It writes the same step records ([SC]) the Wear OS app writes, so the existing "Steps count"
 * automation condition (`TriggerStepsCount`) and the steps graph work with the phone alone. The
 * condition only accepts a record from the last 5 minutes, with a record for each of the 5, 10, 15,
 * 30, 60 and 180 minute windows. Unlike the watch, every record here carries all six counts.
 *
 * When records are written:
 * - when a new glucose value is stored. Automation looks at steps a few seconds after that (the new
 *   value goes through a 5 s debounce and the calculation first), so the record it reads is fresh.
 * - from a timer, when no record was written for [MIN_WRITE_GAP_MS] - for example when glucose values
 *   stop arriving. The timer does not wake the phone; it runs whenever the phone is awake.
 *
 * The math, including how readings dropped while the phone slept are handled, is in
 * [StepCounterHistory].
 *
 * Steps are counted only while the plugin is on. They start from zero each time it is switched on.
 * The phone has to be on the body, in a pocket or a bag, for the count to mean anything.
 *
 * Needs the "physical activity" permission. Enabling the plugin makes the permissions sheet ask for
 * it. Until it is granted nothing is counted, and the timer keeps checking, so counting starts by
 * itself once it is granted. On a phone without a step counter the plugin is not offered at all.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(380)
@SingleIn(AppScope::class)
@Inject
class PhoneStepsPlugin(
    aapsLogger: AAPSLogger,
    override val rh: ResourceHelper,
    private val context: Context,
    private val dateUtil: DateUtil,
    private val persistenceLayer: PersistenceLayer,
    notificationManager: NotificationManager
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(IcActivity)
        .pluginName(SyncStrings.phone_steps)
        .description(SyncStrings.phone_steps_description)
        .enforce(EnforcedState.Disabled, SyncStrings.phone_steps_no_sensor) { !hasStepCounter(context) },
    aapsLogger, rh, notificationManager
) {

    private val history = StepCounterHistory()

    /** Guards [history] and [listening]: sensor readings come on the main thread, writes on IO. */
    private val lock = Any()
    private val writeMutex = Mutex()
    private var listening = false
    private var lastWrite = 0L
    private var scope: CoroutineScope? = null

    private val device = "${Build.MANUFACTURER} ${Build.MODEL}"

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor?.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
            // event.timestamp counts nanoseconds from the phone start, like elapsedRealtimeNanos.
            val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / NANOS_PER_MS
            onReading(dateUtil.now() - ageMs.coerceAtLeast(0L), event.values[0].toLong())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun requiredPermissions(): List<PermissionGroup> = listOf(
        PermissionGroup(
            permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
            rationaleTitle = SyncStrings.permission_activity_title,
            rationaleDescription = SyncStrings.permission_activity_description
        )
    )

    override suspend fun onStart() {
        super.onStart()
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        synchronized(lock) { history.clear() }
        startListening()
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(newScope, aapsLogger, LTag.CORE) { writeSteps(force = true) }
        newScope.launch {
            while (isActive) {
                delay(TICK)
                startListening()
                writeSteps(force = false)
            }
        }
    }

    override suspend fun onStop() {
        scope?.cancel()
        scope = null
        sensorManager()?.unregisterListener(sensorListener)
        synchronized(lock) {
            listening = false
            history.clear()
        }
        super.onStop()
    }

    /** One reading of the counter, already converted to wall clock time. */
    internal fun onReading(measuredAt: Long, sensorValue: Long) {
        synchronized(lock) { history.add(measuredAt, sensorValue) }
    }

    /** The six records for [now], or nothing when there has been no reading yet. */
    internal fun buildRecords(now: Long): List<SC> {
        val steps = synchronized(lock) {
            if (!history.hasData()) return emptyList()
            WINDOWS_MINUTES.associateWith { history.stepsIn(now, it * MS_PER_MINUTE) }
        }
        return WINDOWS_MINUTES.map { minutes ->
            SC(
                duration = minutes * MS_PER_MINUTE,
                timestamp = now,
                steps5min = steps.getValue(5),
                steps10min = steps.getValue(10),
                steps15min = steps.getValue(15),
                steps30min = steps.getValue(30),
                steps60min = steps.getValue(60),
                steps180min = steps.getValue(180),
                device = device
            )
        }
    }

    private suspend fun writeSteps(force: Boolean) = writeMutex.withLock {
        if (!synchronized(lock) { listening }) return@withLock
        // A burst of glucose changes (a backfill) must not write a set of records for each one.
        val minGap = if (force) MIN_FORCED_WRITE_GAP_MS else MIN_WRITE_GAP_MS
        if (dateUtil.now() - lastWrite < minGap) return@withLock
        // Asks for readings the sensor still holds. A phone whose counter holds none ignores it, but
        // if someone is walking right now a fresh reading comes with the next step, so wait for it.
        sensorManager()?.flush(sensorListener)
        delay(READING_WAIT)
        val now = dateUtil.now()
        val records = buildRecords(now)
        if (records.isEmpty()) return@withLock
        persistenceLayer.insertOrUpdateStepsCounts(records)
        lastWrite = now
        aapsLogger.debug(LTag.CORE, "Phone steps: 15 min ${records.first().steps15min}, 60 min ${records.first().steps60min}")
    }

    private fun startListening() {
        synchronized(lock) {
            if (listening) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
            val manager = sensorManager() ?: return
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
            // No handler: readings arrive on the main thread, which only adds them to the history.
            listening = manager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            aapsLogger.info(LTag.CORE, "Phone steps: listening $listening")
        }
    }

    private fun sensorManager(): SensorManager? = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    companion object {

        /** The windows the "Steps count" automation condition offers. */
        val WINDOWS_MINUTES = listOf(5L, 10L, 15L, 30L, 60L, 180L)

        /** The automation condition needs a record younger than 5 minutes; write before that runs out. */
        const val MIN_WRITE_GAP_MS = 4 * 60 * 1000L
        const val MIN_FORCED_WRITE_GAP_MS = 30 * 1000L
        val TICK = 1.minutes
        val READING_WAIT = 3.seconds
        private const val MS_PER_MINUTE = 60 * 1000L
        private const val NANOS_PER_MS = 1_000_000L

        fun hasStepCounter(context: Context): Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
}
