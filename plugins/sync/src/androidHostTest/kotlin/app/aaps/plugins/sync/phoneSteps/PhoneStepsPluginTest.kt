package app.aaps.plugins.sync.phoneSteps

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class PhoneStepsPluginTest : TestBaseWithProfile() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var sensorManager: SensorManager

    private lateinit var sut: PhoneStepsPlugin
    private val start = 1_800_000_000_000L
    private val minute = 60_000L

    @BeforeEach
    fun setUp() {
        whenever(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        sut = PhoneStepsPlugin(aapsLogger, rh, context, dateUtil, persistenceLayer, mock())
    }

    @Test
    fun `asks for the physical activity permission`() {
        assertThat(sut.requiredPermissions().flatMap { it.permissions }).containsExactly(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    @Test
    fun `is not offered on a phone without a step counter`() {
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)).thenReturn(null)

        assertThat(sut.enforcedState()).isEqualTo(EnforcedState.Disabled)
        assertThat(sut.showInList()).isFalse()
    }

    @Test
    fun `is offered on a phone with a step counter`() {
        whenever(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)).thenReturn(mock<Sensor>())

        assertThat(sut.enforcedState()).isNull()
    }

    @Test
    fun `writes nothing before the first reading`() {
        assertThat(sut.buildRecords(start)).isEmpty()
    }

    @Test
    fun `writes one record per window, each with all counts`() {
        sut.onReading(start, 40_000)
        // The phone slept through a 15 minute walk; the steps arrive in one reading.
        val now = start + 60 * minute
        sut.onReading(now, 40_000L + 15 * StepCounterHistory.STEPS_PER_MINUTE)

        val records = sut.buildRecords(now)

        // TriggerStepsCount looks for a record whose duration matches the window it was set to.
        assertThat(records.map { it.duration / minute }).containsExactly(5L, 10L, 15L, 30L, 60L, 180L).inOrder()
        assertThat(records.map { it.timestamp }.distinct()).containsExactly(now)
        records.forEach {
            assertThat(it.steps5min).isEqualTo(550)
            assertThat(it.steps10min).isEqualTo(1_100)
            assertThat(it.steps15min).isEqualTo(1_650)
            assertThat(it.steps30min).isEqualTo(1_650)
            assertThat(it.steps60min).isEqualTo(1_650)
            assertThat(it.steps180min).isEqualTo(1_650)
        }
    }
}
