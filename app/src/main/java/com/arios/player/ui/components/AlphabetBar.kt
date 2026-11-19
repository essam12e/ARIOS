package com.arios.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arios.player.ui.theme.AriosColors
import com.arios.player.ui.theme.PlexArabic

/**
 * Vertical alphabet strip (Latin + Arabic). Tap or drag to jump; shows a bubble with the letter.
 */
@Composable
fun AlphabetBar(letters: List<String>, modifier: Modifier = Modifier, onLetter: (String) -> Unit) {
    if (letters.size < 2) return
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<String?>(null) }
    var activeIdx by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    fun pick(y: Float) {
        val i = ((y / height) * letters.size).toInt().coerceIn(0, letters.size - 1)
        if (letters[i] != active) {
            active = letters[i]; activeIdx = i
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onLetter(letters[i])
        }
    }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (active != null) AriosColors.SurfaceHigh else AriosColors.Surface.copy(alpha = 0.6f))
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.y)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            pick(c.position.y); c.consume()
                        }
                        active = null
                    }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            letters.forEach { l ->
                Text(
                    l, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = PlexArabic,
                    color = if (l == active) AriosColors.Mint else AriosColors.SilverDim,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                )
            }
        }
        val a = active
        if (a != null) {
            val y = (height.toFloat() * (activeIdx + 0.5f) / letters.size).toInt()
            Box(
                Modifier
                    .offset { IntOffset(36.dp.roundToPx(), y - 28.dp.roundToPx()) }
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(AriosColors.Silver),
                contentAlignment = Alignment.Center,
            ) {
                Text(a, color = AriosColors.Black, style = MaterialTheme.typography.headlineSmall, fontFamily = PlexArabic)
            }
        }
    }
}
