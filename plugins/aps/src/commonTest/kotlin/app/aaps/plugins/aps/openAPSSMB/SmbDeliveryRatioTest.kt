package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.interfaces.aps.OapsProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class SmbDeliveryRatioTest {

    private fun profile(atTarget: Double = 0.5, max: Double = 0.5, range: Double = 0.0) = OapsProfile(
        dia = 0.0, min_5m_carbimpact = 0.0, max_iob = 7.0, max_daily_basal = 1.0, max_basal = 4.0,
        min_bg = 100.0, max_bg = 100.0, target_bg = 100.0, carb_ratio = 10.0, sens = 50.0,
        autosens_adjust_targets = false, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false, resistance_lowers_target = false, adv_target_adjustments = false,
        exercise_mode = false, half_basal_exercise_target = 160, maxCOB = 120, skip_neutral_temps = false,
        remainingCarbsCap = 90, enableUAM = true, A52_risk_enable = false, SMBInterval = 3,
        enableSMB_with_COB = false, enableSMB_with_temptarget = false, allowSMB_with_high_temptarget = false,
        enableSMB_always = true, enableSMB_after_carbs = false, maxSMBBasalMinutes = 30, maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.05, carbsReqThreshold = 1, current_basal = 1.0, temptargetSet = false, autosens_max = 1.2,
        out_units = "mg/dl", lgsThreshold = null, variable_sens = 0.0, insulinDivisor = 75, TDD = 0.0,
        smb_delivery_ratio_at_target = atTarget, smb_delivery_ratio_max = max, smb_delivery_ratio_bg_range = range
    )

    private fun assertNear(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun `a range of 0 keeps the oref half at every glucose`() {
        val p = profile(atTarget = 0.3, max = 0.6, range = 0.0)
        assertNear(0.5, smbDeliveryRatio(110.0, 110.0, p))
        assertNear(0.5, smbDeliveryRatio(300.0, 110.0, p))
    }

    @Test
    fun `the defaults change nothing even with a range`() {
        val p = profile(range = 90.0)
        assertNear(0.5, smbDeliveryRatio(110.0, 110.0, p))
        assertNear(0.5, smbDeliveryRatio(250.0, 110.0, p))
    }

    @Test
    fun `the ratio grows in a straight line from the target to target plus range`() {
        val p = profile(atTarget = 0.3, max = 0.6, range = 90.0)
        assertNear(0.3, smbDeliveryRatio(110.0, 110.0, p))
        assertNear(0.4, smbDeliveryRatio(140.0, 110.0, p))
        assertNear(0.5, smbDeliveryRatio(170.0, 110.0, p))
        assertNear(0.6, smbDeliveryRatio(200.0, 110.0, p))
    }

    @Test
    fun `above the range it stays at the high value and below the target at the target value`() {
        val p = profile(atTarget = 0.3, max = 0.6, range = 90.0)
        assertNear(0.6, smbDeliveryRatio(320.0, 110.0, p))
        assertNear(0.3, smbDeliveryRatio(80.0, 110.0, p))
    }

    @Test
    fun `values entered the wrong way round still give a ratio between them`() {
        val p = profile(atTarget = 0.6, max = 0.3, range = 90.0)
        assertNear(0.3, smbDeliveryRatio(110.0, 110.0, p))
        assertNear(0.6, smbDeliveryRatio(200.0, 110.0, p))
    }
}
