package app.aaps.plugins.aps.openAPSSMB

import app.aaps.core.interfaces.aps.OapsProfile
import kotlin.math.max
import kotlin.math.min

/** What oref gives as one SMB: half of insulinReq. */
const val DEFAULT_SMB_DELIVERY_RATIO = 0.5

/**
 * The part of insulinReq that is given as one SMB.
 *
 * oref always gives half. With [OapsProfile.smb_delivery_ratio_bg_range] above 0 the part grows in a
 * straight line from [OapsProfile.smb_delivery_ratio_at_target] at the target to
 * [OapsProfile.smb_delivery_ratio_max] at target + range, and stays there above that. Below the target
 * it stays at the value for the target.
 *
 * Why: a small rise near the target - a snack, or a rise that ends by itself - got half of a large
 * UAM insulinReq every 5 minutes, and 2-5 U arrived before the rise was over. A smaller part near the
 * target lets the loop see whether the rise goes on before it gives the rest. Real meals that keep BG
 * rising move it up the line and get the larger part.
 *
 * The same idea, with its own settings, is in AutoISF (`determine_varSMBratio`). The settings are not
 * shared on purpose: an AutoISF set-up allows up to 1.0, and switching the APS plugin must not carry
 * that over without the user seeing it.
 *
 * @param bg current glucose, mg/dL
 * @param targetBg target in use (after temp target and autosens), mg/dL
 */
fun smbDeliveryRatio(bg: Double, targetBg: Double, profile: OapsProfile): Double {
    val range = profile.smb_delivery_ratio_bg_range
    if (range <= 0.0) return DEFAULT_SMB_DELIVERY_RATIO
    val low = min(profile.smb_delivery_ratio_at_target, profile.smb_delivery_ratio_max)
    val high = max(profile.smb_delivery_ratio_at_target, profile.smb_delivery_ratio_max)
    val ratio = low + (high - low) * (bg - targetBg) / range
    return ratio.coerceIn(low, high)
}
