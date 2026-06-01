package com.sese.keepix.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sese.keepix.ui.components.*
import com.sese.keepix.ui.theme.*

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = currentStep == 0,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OnboardingStep1()
            }

            AnimatedVisibility(
                visible = currentStep == 1,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OnboardingStep2()
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Page indicators
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                repeat(2) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == currentStep) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) AccentPurple
                                else TextMuted
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            com.sese.keepix.ui.components.GlassButton(
                onClick = {
                    if (currentStep == 0) {
                        currentStep = 1
                    } else {
                        onComplete()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                cornerRadius = 16.dp,
                tintColor = AccentPurple,
                tintAlpha = 0.2f
            ) {
                Text(
                    text = if (currentStep == 0) "Next" else "Start Cleaning",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun OnboardingStep1() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Swipe demo illustration
        GlassCard(
            modifier = Modifier
                .size(200.dp),
            cornerRadius = 24.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    ) {
                        Text("← DELETE", color = DeleteRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("KEEP →", color = KeepGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("📸", fontSize = 48.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Swipe to Decide",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Swipe right to keep.\nSwipe left to delete.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp
        )
    }
}

@Composable
private fun OnboardingStep2() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GlassCard(
            modifier = Modifier.size(200.dp),
            cornerRadius = 24.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🗑️", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("⏱️ 10 days", color = BadgeOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Your Bin Has Your Back",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Deleted photos go to your bin.\nNothing is permanent until the timer runs out.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp
        )
    }
}
