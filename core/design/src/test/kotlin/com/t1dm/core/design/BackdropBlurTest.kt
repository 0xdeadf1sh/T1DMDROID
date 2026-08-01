package com.t1dm.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom bar's blur gate. The property under test is not "the bar looks nice" but the one thing
 * a translucent bar can get wrong: how far the thing behind it may move the bar's luminance, and
 * therefore how much contrast any label on it can lose.
 *
 * Every case re-derives the bound independently of the solver rather than asserting a number the
 * solver produced — a solver that returned a scrim satisfying nothing would still pass a golden test.
 */
class BackdropBlurTest {

    /** Every bundled palette has a motif painter, so the flat-backdrop door is not what the cases
     *  below exercise — the arithmetic is. That door has its own case at the foot of the file. */
    private fun scrimOf(p: T1dmPalette, alphaPct: Int): Float =
        backdropBlurScrim(p.surface, p.background, alphaPct, hasMotif = true)

    /** The luminance the bar actually ends up at, for one motif colour behind it. */
    private fun barLuminance(p: T1dmPalette, alphaPct: Int, scrim: Float, motif: Color): Float {
        val behind = compositeArgb(argbWithAlpha(motif.toArgb(), alphaPct / 100f), p.background.toArgb())
        return relativeLuminanceArgb(compositeArgb(argbWithAlpha(p.surface.toArgb(), scrim), behind))
    }

    /** The invariant [backdropBlurScrim] exists to hold, restated from its definition. */
    private fun holdsBound(p: T1dmPalette, alphaPct: Int, scrim: Float): Boolean {
        val flat = relativeLuminanceArgb(p.surface.toArgb()) + 0.05f
        return listOf(Color.Black, Color.White).all { motif ->
            val l = barLuminance(p, alphaPct, scrim, motif) + 0.05f
            l <= flat * BACKDROP_BLUR_TOLERANCE && l >= flat / BACKDROP_BLUR_TOLERANCE
        }
    }

    // ── the gate ────────────────────────────────────────────────────────────────────────────────

    @Test fun `a backdrop turned off is refused — a flat colour blurred is itself`() {
        for (p in BundledPalettes) {
            assertEquals(p.id, 1f, scrimOf(p, 0), 0f)
        }
    }

    @Test fun `whatever it returns below 1 actually satisfies the bound`() {
        for (p in BundledPalettes) for (pct in 0..100 step 5) {
            val scrim = scrimOf(p, pct)
            if (scrim < 1f) {
                assertTrue("${p.id} @ $pct%: scrim $scrim breaks the bound", holdsBound(p, pct, scrim))
            }
        }
    }

    @Test fun `and it is the SMALLEST such scrim — one step more transparent breaks the bound`() {
        for (p in BundledPalettes) for (pct in 5..100 step 5) {
            val scrim = scrimOf(p, pct)
            if (scrim > BACKDROP_BLUR_SCRIM_MIN && scrim < 1f) {
                assertTrue(
                    "${p.id} @ $pct%: $scrim was not minimal",
                    !holdsBound(p, pct, scrim - 0.01f),
                )
            }
        }
    }

    @Test fun `a refusal is a real refusal — no scrim in range would have held`() {
        for (p in BundledPalettes) for (pct in 5..100 step 5) {
            if (scrimOf(p, pct) != 1f) continue
            var s = BACKDROP_BLUR_SCRIM_MIN
            while (s <= BACKDROP_BLUR_SCRIM_MAX + 1e-4f) {
                assertTrue("${p.id} @ $pct%: refused, yet $s holds", !holdsBound(p, pct, s))
                s += 0.01f
            }
        }
    }

    // ── how it degrades ─────────────────────────────────────────────────────────────────────────

    @Test fun `a louder backdrop never buys a more transparent bar`() {
        // Monotone, so the effect fades out as the thing behind it gets louder — it never snaps back.
        for (p in BundledPalettes) {
            var previous = BACKDROP_BLUR_SCRIM_MIN
            for (pct in 1..100) {
                val scrim = scrimOf(p, pct)
                assertTrue("${p.id} @ $pct%: $scrim < $previous", scrim >= previous - 1e-4f)
                previous = scrim
            }
        }
    }

    @Test fun `every bundled palette gets a blur at the settings a user starts on`() {
        // The default backdrop opacity is low; the gate must not be refusing the feature out of the box
        // on any shipped theme. Stated as a band, not as the one constant, so moving the default does
        // not silently move the test.
        for (p in BundledPalettes) for (pct in 5..20) {
            val scrim = scrimOf(p, pct)
            assertTrue("${p.id} @ $pct%: refused", scrim < 1f)
            assertTrue("${p.id} @ $pct%: $scrim out of range", scrim >= BACKDROP_BLUR_SCRIM_MIN)
        }
    }

    @Test fun `a backdrop at full strength is refused where it would wash the bar out`() {
        // Tron at 100 % puts its lit horizon straight behind the bar. Nothing translucent survives it.
        assertEquals(1f, scrimOf(TronPalette, 100), 0f)
    }

    // ── the hostile import ──────────────────────────────────────────────────────────────────────

    @Test fun `a palette whose surface is an extreme is handled at both ends`() {
        // surface == background is what a minimal imported theme resolves to. Nothing here divides by
        // zero or runs off the end of the scan; given something behind the bar to blur, at a quiet
        // backdrop both extremes get the full effect.
        assertEquals(BACKDROP_BLUR_SCRIM_MIN, backdropBlurScrim(Color.White, Color.White, 5, hasMotif = true), 1e-4f)
        assertEquals(BACKDROP_BLUR_SCRIM_MIN, backdropBlurScrim(Color.Black, Color.Black, 5, hasMotif = true), 1e-4f)
    }

    @Test fun `a theme with no motif is refused at every opacity — a flat wash blurred is itself`() {
        // The reachable flat-backdrop case, and the one the alphaPct door does not cover: an imported
        // theme has no painter and ships no raster, so ThemeBackdrop paints `background` and nothing
        // a gaussian does to it can move a pixel. Granting the blur would buy an offscreen render
        // target and the loss of the bar's tonal lift for an identity.
        for (p in BundledPalettes) for (pct in 0..100 step 5) {
            assertEquals("${p.id} @ $pct%", 1f, backdropBlurScrim(p.surface, p.background, pct, hasMotif = false), 0f)
        }
    }

    @Test fun `a translucent surface is solved against the bar it actually paints`() {
        // `surface` is a free role in an imported palette and may carry alpha. Read raw, 12.5 % white
        // is taken for opaque white — a reference bar at L = 1 — where what is painted over a
        // near-black page is ~#252930 at L = 0.02. The bound has to be held against the second.
        val page = Color(0xFF060A12)
        val translucent = Color(0x20FFFFFF)
        val painted = translucent.compositeOn(page)
        assertTrue(
            "the two readings must differ, or this case proves nothing",
            relativeLuminanceArgb(translucent.toArgb()) - relativeLuminanceArgb(painted.toArgb()) > 0.5f,
        )
        for (pct in 1..60) {
            val scrim = backdropBlurScrim(translucent, page, pct, hasMotif = true)
            if (scrim >= 1f) continue
            val flat = relativeLuminanceArgb(painted.toArgb()) + 0.05f
            val held = listOf(Color.Black, Color.White).all { motif ->
                val behind = compositeArgb(argbWithAlpha(motif.toArgb(), pct / 100f), page.toArgb())
                val bar = compositeArgb(argbWithAlpha(translucent.toArgb(), scrim), behind)
                val l = relativeLuminanceArgb(bar) + 0.05f
                l <= flat * BACKDROP_BLUR_TOLERANCE && l >= flat / BACKDROP_BLUR_TOLERANCE
            }
            assertTrue("@ $pct%: scrim $scrim breaks the painted bound", held)
        }
    }

    @Test fun `a near-black surface is the strictest case and is refused early`() {
        // At L ~ 0, the tolerance is a 15 % move on 0.05 — a few units of white behind the bar spend
        // it. That is correct: those are exactly the surfaces where a little stray light costs the
        // most contrast.
        val nearBlack = Color(0xFF000000)
        val scrim = backdropBlurScrim(nearBlack, nearBlack, 60, hasMotif = true)
        assertTrue("expected a refusal or a heavy scrim, got $scrim", scrim >= 0.8f)
    }
}
