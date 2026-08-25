package com.t1dm.alerts

interface AlarmNotifier {
    fun emit(state: AlarmState)

    /** Re-actuate a persisting alarm without changing its text. */
    fun reAlert(state: AlarmState)

    fun clear()
}

object NoopAlarmNotifier : AlarmNotifier {
    override fun emit(state: AlarmState) {}
    override fun reAlert(state: AlarmState) {}
    override fun clear() {}
}
