package com.saad.snake

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

enum class Screen { AUTH, HOME, PLAYING }

// ---------------------------------------------------------------------------
// Home screen: title + START + SETTINGS in the middle
// ---------------------------------------------------------------------------
@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onSettings: () -> Unit,
    highScore: Int,
    userEmail: String?,
    onSignOut: () -> Unit
) {
    val ctx = LocalContext.current
    val name = remember { appName(ctx) }
    val version = remember { appVersion(ctx) }

    Box(Modifier.fillMaxSize()) {
        HomeBackground()

        // Dark overlay so the buttons stay readable on any background image.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
        )

        // ── High score badge (top-left) ──────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(
                text = "🏆 Best: $highScore",
                color = Color(0xFFFFD54F),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (userEmail != null) {
                Text(
                    text = userEmail,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            } else {
                Text(
                    text = "Playing as guest",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }

        // ── Sign-out button (top-right) ──────────────────────────────────
        androidx.compose.material3.TextButton(
            onClick = onSignOut,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Text(
                text = if (userEmail != null) "Sign Out" else "Log In",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp
            )
        }

        // ── Centre buttons ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(280.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = name.uppercase(),
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) { Text("START", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.85f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("SETTINGS", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }

        Text(
            text = "v$version",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Background
//
// TO USE YOUR OWN IMAGE: put a picture named  home_bg.png  (or .jpg / .webp)
// into  app/src/main/res/drawable/  - no code change needed.
// If that file exists it is shown automatically, otherwise the animated
// placeholder below is used.
// ---------------------------------------------------------------------------
@Composable
fun HomeBackground() {
    val ctx = LocalContext.current
    val resId = remember { ctx.resources.getIdentifier("home_bg", "drawable", ctx.packageName) }
    if (resId != 0) {
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        PlaceholderBackground()
    }
}

private class Drifter(
    val x: Float, val y: Float,      // start position (0..1 of the screen)
    val size: Float,                 // radius in dp
    val color: Color,
    val shape: Int,                  // 0 circle, 1 square, 2 triangle, 3 diamond
    val kx: Int, val ky: Int         // drift speed (whole numbers so the loop is seamless)
)

private val HOME_COLORS = listOf(
    Color(0xFFFF5252), Color(0xFFFFB74D), Color(0xFFFFEE58), Color(0xFF66BB6A),
    Color(0xFF26C6DA), Color(0xFF42A5F5), Color(0xFFAB47BC), Color(0xFFEC407A)
)

/** Gradient + slowly drifting colored shapes (same look as the food in the game). */
@Composable
private fun PlaceholderBackground() {
    val items = remember {
        val r = Random(7)
        List(36) {
            Drifter(
                x = r.nextFloat(),
                y = r.nextFloat(),
                size = 5f + r.nextFloat() * 9f,
                color = HOME_COLORS[r.nextInt(HOME_COLORS.size)],
                shape = r.nextInt(4),
                kx = 1 + r.nextInt(2),
                ky = r.nextInt(3) - 1
            )
        }
    }
    val path = remember { Path() }
    val t = rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(40000, easing = LinearEasing))
    )

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF241446))))

        val tv = t.value
        for (d in items) {
            val cx = ((d.x + tv * d.kx) % 1f) * w
            val cy = ((((d.y + tv * d.ky) % 1f) + 1f) % 1f) * h
            val s = d.size * density
            drawCircle(d.color.copy(alpha = 0.18f), s * 2f, Offset(cx, cy))
            when (d.shape) {
                0 -> drawCircle(d.color, s, Offset(cx, cy))
                1 -> drawRect(d.color, Offset(cx - s * 0.9f, cy - s * 0.9f), Size(s * 1.8f, s * 1.8f))
                2 -> {
                    path.reset()
                    path.moveTo(cx, cy - s * 1.3f)
                    path.lineTo(cx + s * 1.2f, cy + s * 0.9f)
                    path.lineTo(cx - s * 1.2f, cy + s * 0.9f)
                    path.close()
                    drawPath(path, d.color)
                }
                else -> {
                    path.reset()
                    path.moveTo(cx, cy - s * 1.3f)
                    path.lineTo(cx + s * 1.3f, cy)
                    path.lineTo(cx, cy + s * 1.3f)
                    path.lineTo(cx - s * 1.3f, cy)
                    path.close()
                    drawPath(path, d.color)
                }
            }
        }
    }
}