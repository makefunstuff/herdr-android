package com.makefunstuff.herdr.ui.design

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * This density with its text made [scale] times larger, the rest as it was: an sp becomes what the
 * phone makes of it (its own font curve, non-linear from Android 14, where large text grows less
 * than small), then times [scale]. At 1 it is this density itself, so a chat at 100% reads as the
 * rest of the app. A plain `Density(density, fontScale * scale)` would make every size linear.
 */
fun Density.withTextScale(scale: Float): Density = if (scale == 1f) this else ScaledText(this, scale)

private class ScaledText(private val base: Density, private val scale: Float) : Density {
    override val density: Float get() = base.density
    override val fontScale: Float get() = base.fontScale * scale
    override fun TextUnit.toDp(): Dp = base.run { this@toDp.toDp() } * scale
    override fun Dp.toSp(): TextUnit = base.run { (this@toSp / scale).toSp() }
}
