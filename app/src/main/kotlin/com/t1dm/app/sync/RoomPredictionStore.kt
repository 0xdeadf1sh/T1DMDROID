package com.t1dm.app.sync

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.T1dmRepository
import com.t1dm.inference.PredictionStore

class RoomPredictionStore(private val repository: T1dmRepository) : PredictionStore {

    override suspend fun persist(cycleTsMs: Long, predictions: List<ModelPrediction>) {
        repository.upsertPredictions(predictions, System.currentTimeMillis())
    }

    override suspend fun loadLast(): List<ModelPrediction>? = repository.latestCyclePredictions()
}
