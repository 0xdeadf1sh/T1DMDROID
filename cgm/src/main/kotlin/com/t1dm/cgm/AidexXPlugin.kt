package com.t1dm.cgm

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/** Recognition is by advertised name / serial suffix, NEVER by BLE address — a resolvable-random
 *  address rotates (CGM.md §1). */
class AidexXPlugin(
    private val nativeCore: NativeCore,
    private val repository: CgmRepository,
) : CgmVendorPlugin {

    override val vendorId: String = VENDOR_ID

    override fun recognize(
        name: String?,
        manufacturerId: Int,
        manufacturerData: ByteArray,
    ): CgmSourceId? {
        if (manufacturerId != CgmConstants.MANUFACTURER_ID) return null
        val n = name ?: return null
        val match = CgmConstants.matchAdvertName(n) ?: return null
        return CgmSourceId("$VENDOR_ID:${match.serial}")
    }

    /** Seeds a source never met; one already on record carries the user's own window and arrives
     *  through the descriptor overload instead. */
    override fun createSource(id: CgmSourceId): AidexXSource = createSource(descriptorFor(id.value.substringAfter(':')))

    override fun createSource(descriptor: CgmSourceDescriptor): AidexXSource =
        AidexXSource(descriptor, nativeCore, repository)

    companion object {
        internal const val VENDOR_ID = "aidexx"

        /** The advert name is kept verbatim so a later model split can reclassify a source already on
         *  record. A name that matched nothing falls back to this plugin's only model. */
        fun descriptorFor(serial: String, advertName: String? = null): CgmSourceDescriptor {
            val match = advertName?.let(CgmConstants::matchAdvertName)
            return CgmSourceDescriptor(
                id = CgmSourceId("$VENDOR_ID:$serial"),
                vendorId = VENDOR_ID,
                sensorModelId = match?.sensorModelId ?: CgmSensorModelId.AIDEX_X,
                advertName = advertName,
                displayName = "${match?.brand ?: "AiDEX X"} $serial",
                serialSuffix = serial,
                warmupWindowMin = CgmConstants.WARMUP_WINDOW_MIN,
                passiveOnly = true,
            )
        }
    }
}
