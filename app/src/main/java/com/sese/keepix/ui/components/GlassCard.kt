package com.sese.keepix.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sese.keepix.ui.theme.*

/**
 * A beautiful animated background for glassmorphism effects.
 * Provides the visual depth and color needed to make glass cards pop.
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glass_bg")
    
    val color1Anim by infiniteTransition.animateColor(
        initialValue = Color(0xFF6200EE).copy(alpha = 0.15f),
        targetValue = Color(0xFF3700B3).copy(alpha = 0.15f),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color1"
    )
    
    val color2Anim by infiniteTransition.animateColor(
        initialValue = Color(0xFF03DAC6).copy(alpha = 0.1f),
        targetValue = Color(0xFF018786).copy(alpha = 0.1f),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color2"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Background Mesh/Blobs
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color1Anim, Color.Transparent),
                    center = Offset(size.width * 0.2f, size.height * 0.2f),
                    radius = size.width * 0.6f
                ),
                center = Offset(size.width * 0.2f, size.height * 0.2f),
                radius = size.width * 0.6f
            )
            
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color2Anim, Color.Transparent),
                    center = Offset(size.width * 0.8f, size.height * 0.7f),
                    radius = size.width * 0.5f
                ),
                center = Offset(size.width * 0.8f, size.height * 0.7f),
                radius = size.width * 0.5f
            )
            
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AccentPurple.copy(alpha = 0.1f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.4f),
                    radius = size.width * 0.4f
                ),
                center = Offset(size.width * 0.5f, size.height * 0.4f),
                radius = size.width * 0.4f
            )
        }
        
        content()
    }
}

fun Modifier.glassmorphism(
    cornerRadius: Dp = 20.dp,
    tintColor: Color = Color.White,
    tintAlpha: Float = 0.05f,
    blurRadius: Dp = 12.dp
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .drawBehind {
            // Shadow (Using native canvas for better performance and look)
            drawIntoCanvas { canvas ->
                val paint = Paint().asFrameworkPaint().apply {
                    color = Color.Transparent.toArgb()
                    setShadowLayer(
                        24.dp.toPx(),
                        0f, 8.dp.toPx(),
                        Color.Black.copy(alpha = 0.25f).toArgb()
                    )
                }
                canvas.nativeCanvas.drawRoundRect(
                    0f, 0f, size.width, size.height,
                    cornerRadius.toPx(), cornerRadius.toPx(),
                    paint
                )
            }

            // Glass Fill
            drawRoundRect(
                color = tintColor.copy(alpha = tintAlpha),
                size = size,
                cornerRadius = CornerRadius(cornerRadius.toPx())
            )
            
            // Top and Left Edge Highlights
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.2f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                size = size,
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
        }
        .clip(shape)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    tintAlpha: Float = 0.05f,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.glassmorphism(
            cornerRadius = cornerRadius,
            tintAlpha = tintAlpha
        )
    ) {
        content()
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    tintColor: Color = Color.White,
    tintAlpha: Float = 0.15f,
    content: @Composable RowScope.() -> Unit
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.glassmorphism(cornerRadius, tintColor, tintAlpha),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        shape = RoundedCornerShape(cornerRadius)
    ) {
        content()
    }
}
