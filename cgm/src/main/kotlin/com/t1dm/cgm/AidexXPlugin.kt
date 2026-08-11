package com.t1dm.cgm

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/**
 * The AiDEX X / LinX vendor adapter (§3.1). Recognition is by advertised name /
 * serial suffix, NEVER by BLE address (resolvable-random rotates, CGM.md §1). The id is
 * `"aidexx:<serial>"`, e.g. `"aidexx:22222C74D9"`.
 */
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

    /** Seed a source the app has never met. [CgmConstants.WARMUP_WINDOW_MIN] here is only that seed;
     *  a source already on record carries the user's own window and arrives through the descriptor
     *  overload instead. */
    override fun createSource(id: CgmSourceId): AidexXSource = createSource(descriptorFor(id.value.substringAfter(':')))

    override fun createSource(descriptor: CgmSourceDescriptor): AidexXSource =
        AidexXSource(descriptor, nativeCore, repository)

    companion object {
        internal const val VENDOR_ID = "aidexx"

        /**
         * Seed a descriptor for a sensor the app has never met.
         *
         * Matched ONCE — the model and the brand come from the same resolution, so they cannot disagree
         * about which prefix won. The advert name is kept verbatim so a later model split can reclassify
         * a source already on record; before it was matched, used to strip the serial, and dropped, which
         * is why every source called itself "AiDEX X". A name that matched nothing cannot have come
         * through the scanner, and falls back to the only model this plugin has.
         */
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
