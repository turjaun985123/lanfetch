package com.lanfetch.app.ui.brand

import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lanfetch.app.ui.theme.LanFetchFontFamily
import com.lanfetch.app.ui.theme.LanesPalette

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

@Composable
fun LanFetchHeaderBrand(modifier: Modifier = Modifier) {
    Row(modifier = modifier.semantics { contentDescription = "LanFetch" }) {
        Text(
            text = "lanfetch",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = LanFetchFontFamily,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                color = LanesPalette.InkPrimary,
            ),
            maxLines = 1,
        )
    }
}
