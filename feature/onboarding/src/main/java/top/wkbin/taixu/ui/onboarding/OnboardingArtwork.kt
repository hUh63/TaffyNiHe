package top.wkbin.taixu.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.wkbin.taixu.ui.components.RuntimeIllustration
import top.wkbin.taixu.ui.components.RuntimeIllustrationScene

@Composable
internal fun OnboardingArtwork() {
    RuntimeIllustration(RuntimeIllustrationScene.Welcome, Modifier.height(208.dp))
}
