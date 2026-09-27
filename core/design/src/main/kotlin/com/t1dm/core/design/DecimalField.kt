package com.t1dm.core.design

/** A decimal comma reads as a point; two separators survive, so the parse fails, not guesses. */
fun decimalFieldText(raw: String): String =
    raw.replace(',', '.').filter { it.isDigit() || it == '.' }
