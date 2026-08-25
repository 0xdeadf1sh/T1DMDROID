package com.t1dm.data

sealed interface PromoteResult {
    data class Promoted(val steps: Int) : PromoteResult

    data class Refused(val why: String) : PromoteResult
}
