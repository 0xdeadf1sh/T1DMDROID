package com.t1dm.data

/**
 * What happened when a reconstructed span was promoted into the record, or taken back out.
 *
 * A refusal carries its reason because promotion refuses for several ordinary reasons — a slot that
 * has since been measured, a span with nothing measured before it, a band that does not bracket its
 * own median — and a silent refusal reads as a button that does nothing.
 */
sealed interface PromoteResult {
    data class Promoted(val steps: Int) : PromoteResult

    data class Refused(val why: String) : PromoteResult
}
