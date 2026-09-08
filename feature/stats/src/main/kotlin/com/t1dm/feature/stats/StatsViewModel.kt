package com.t1dm.feature.stats

import com.t1dm.core.model.StatsComposite
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Fetch/recompute run on [source]'s dispatchers. Long-lived [scope] survives Activity churn. */
class StatsViewModel(
    private val source: StatsSource,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val window: StatsWindow = StatsWindow.D7,
        val unitSpace: UnitSpace = UnitSpace.MgDl,
        val targetRange: TargetRange = TargetRange.DEFAULT,
        val loading: Boolean = false,
        val recomputing: Boolean = false,
        val composite: StatsComposite? = null,
        val emptyReason: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        // Unit-space changes are display-only — repaint the current composite, no recompute.
        scope.launch {
            source.unitSpace.collect { u ->
                _state.update { it.copy(unitSpace = u, composite = it.composite?.copy(unitSpace = u)) }
            }
        }
        // First emission triggers initial load; TIR/TBR/TAR depend on range, so later ones reload.
        scope.launch {
            source.targetRange.collect { t ->
                _state.update { it.copy(targetRange = t) }
                load(_state.value.window, refresh = false)
            }
        }
    }

    fun selectWindow(window: StatsWindow) {
        if (window == _state.value.window && _state.value.composite != null) return
        load(window, refresh = false)
    }

    fun recompute() = load(_state.value.window, refresh = true)

    fun setUnitSpace(space: UnitSpace) {
        scope.launch { source.setUnitSpace(space) } // re-emits via unitSpace → repaint
    }

    fun setTargetRange(lowMgdl: Int, highMgdl: Int) {
        scope.launch { source.setTargetRange(lowMgdl, highMgdl) } // re-emits targetRange → reload
    }

    private fun load(window: StatsWindow, refresh: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update { it.copy(window = window, loading = true, recomputing = refresh) }
            val server = source.serverStats(window, refresh)
            val local = source.localStats(window, refresh)
            val unit = _state.value.unitSpace
            val target = _state.value.targetRange
            val serverStats = (server as? ServerStatsResult.Ok)?.stats
            val composite = StatsComposite(
                window = window,
                targetRange = target,
                unitSpace = unit,
                server = serverStats,
                serverReason = (server as? ServerStatsResult.Unavailable)?.reason,
                local = local,
                recomputed = refresh,
            )
            val bothEmpty = local.isEmpty && (serverStats == null || serverStats.nSamples == 0)
            _state.update {
                it.copy(
                    loading = false,
                    recomputing = false,
                    composite = composite,
                    emptyReason = if (bothEmpty) {
                        "Not enough history for ${window.wire} yet — still collecting readings"
                    } else {
                        null
                    },
                )
            }
        }
    }
}
