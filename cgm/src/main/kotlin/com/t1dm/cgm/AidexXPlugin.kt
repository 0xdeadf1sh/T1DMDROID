package com.t1dm.cgm

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/**
 * The AiDEX X / LinX vendor adapter (PLAN.private.md §3.1). Recognition is by advertised name /
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
        val prefix = CgmConstants.NAME_PREFIXES.firstOrNull { n.startsWith(it) } ?: return null
        val serial = n.removePrefix(prefix)
        if (serial.isEmpty()) return null
        return CgmSourceId("$VENDOR_ID:$serial")
    }

    override fun createSource(id: CgmSourceId): AidexXSource {
        val serial = id.value.substringAfter(':')
        val descriptor = CgmSourceDescriptor(
            id = id,
            vendorId = VENDOR_ID,
            displayName = "AiDEX X $serial",
            serialSuffix = serial,
            warmupWindowMin = CgmConstants.WARMUP_WINDOW_MIN,
            passiveOnly = true,
        )
        return AidexXSource(descriptor, nativeCore, repository)
    }

    private companion object {
        const val VENDOR_ID = "aidexx"
    }
}
