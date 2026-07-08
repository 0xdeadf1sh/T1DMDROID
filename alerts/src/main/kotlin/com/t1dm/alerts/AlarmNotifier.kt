package com.t1dm.alerts

/**
 * The emission sink for the deterministic alarm path. Kept behind an interface so the pure engine
 * and the [AlarmController] wiring are unit-testable with a fake, and so the Android surface
 * ([AndroidAlarmNotifier]) is the only Android-coupled piece.
 */
interface AlarmNotifier {
    /** Post/update notifications for the currently-active alarms and vibrate for the primary one. */
    fun emit(state: AlarmState)

    /** Re-vibrate a persisting CRITICAL alarm on the repeat cadence, without changing its text. */
    fun reAlert(state: AlarmState)

    /** Dismiss all alarm notifications (state went clear). */
    fun clear()
}

/** No-op sink (tests, headless contexts). */
object NoopAlarmNotifier : AlarmNotifier {
    override fun emit(state: AlarmState) {}
    override fun reAlert(state: AlarmState) {}
    override fun clear() {}
}
