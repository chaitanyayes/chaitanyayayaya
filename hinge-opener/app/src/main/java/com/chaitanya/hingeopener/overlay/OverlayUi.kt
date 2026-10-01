package com.chaitanya.hingeopener.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaitanya.hingeopener.OpenerResult
import com.chaitanya.hingeopener.Reply

private val Purple = Color(0xFF7B2CBF)
private val Pink = Color(0xFFE0457B)
private val CardBg = Color(0xFF1C1A22)
private val CardText = Color(0xFFF4F1F8)
private val Muted = Color(0xFFB3ADBF)

/** Visual only; touch handling (tap / long-press / drag) is a raw View touch listener in BubbleService. */
@Composable
fun Bubble(state: OverlayState) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(state.captureTick) {
        if (state.captureTick > 0) {
            scale.animateTo(1.2f, tween(90))
            scale.animateTo(1f, spring(dampingRatio = 0.4f))
        }
    }

    Box(Modifier.size(70.dp)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Purple, Pink))),
            contentAlignment = Alignment.Center,
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(30.dp),
                )
            } else {
                Text("✦", color = Color.White, fontSize = 24.sp)
            }
        }
        if (state.captureCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Purple, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${state.captureCount}",
                    color = Purple,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun ResultCard(
    state: OverlayState,
    maxHeightDp: Int,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val drag by rememberUpdatedState(onDrag)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .heightIn(max = maxHeightDp.dp),
    ) {
        // Header doubles as the drag handle.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Purple, Pink)))
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        drag(amount.x, amount.y)
                    }
                }
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    state.error != null -> "Something went wrong"
                    else -> "Best opener"
                },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("⠿", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 18.sp) }
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val error = state.error
            val result = state.result
            if (error != null) {
                Text(error, color = CardText, fontSize = 16.sp)
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.captureCount > 0) {
                        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                            Text("Retry")
                        }
                    }
                    OutlinedButton(onClick = onClear) { Text("Clear", color = CardText) }
                }
            } else if (result != null) {
                BestReply(result, onCopy)
                if (result.runnerUps.isNotEmpty()) {
                    Spacer(Modifier.size(14.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Spacer(Modifier.size(10.dp))
                    Text("Runner-ups", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    result.runnerUps.forEach { RunnerUp(it, onCopy) }
                }
                Spacer(Modifier.size(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onClear) { Text("Clear captures", color = CardText) }
                }
            }
        }
    }
}

@Composable
private fun BestReply(result: OpenerResult, onCopy: (String) -> Unit) {
    TargetChip(result.best.target)
    Spacer(Modifier.size(8.dp))
    Text(
        result.best.text,
        color = CardText,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (result.reason.isNotBlank()) {
        Spacer(Modifier.size(8.dp))
        Text(result.reason, color = Muted, fontSize = 13.sp, fontStyle = FontStyle.Italic)
    }
    Spacer(Modifier.size(12.dp))
    Button(
        onClick = { onCopy(result.best.text) },
        colors = ButtonDefaults.buttonColors(containerColor = Purple),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Copy") }
}

@Composable
private fun RunnerUp(reply: Reply, onCopy: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TargetChip(reply.target)
            Spacer(Modifier.size(4.dp))
            Text(reply.text, color = CardText, fontSize = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onCopy(reply.text) }) { Text("Copy", color = CardText) }
    }
}

@Composable
private fun TargetChip(target: String) {
    if (target.isBlank()) return
    Text(
        target,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Purple.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
