package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.brandWordmarkResource

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    drawableOverride: Int? = null
) {
    val wordmarkResource = drawableOverride ?: NuvioTheme.currentTheme.brandWordmarkResource
    val edgeColor = when (wordmarkResource) {
        R.drawable.app_logo_wordmark_gold -> Color(0xFFE8A91C)
        R.drawable.app_logo_wordmark_jade -> Color(0xFF22D37C)
        R.drawable.app_logo_wordmark_rose_gold -> Color(0xFFEC70A9)
        R.drawable.app_logo_wordmark_arctic_blue -> Color(0xFF3185F5)
        R.drawable.app_logo_wordmark_graphite -> Color(0xFFAAB2BE)
        else -> NuvioTheme.colors.TextPrimary
    }

    BoxWithConstraints(
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription ?: "NuvioEdge"
        }
    ) {
        val wordmarkHeight = maxHeight
        val suffixFontSize = (wordmarkHeight.value * 0.46f).sp
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = wordmarkResource),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(344f / 132f),
                contentScale = contentScale,
                alpha = alpha
            )
            Spacer(modifier = Modifier.width(wordmarkHeight * 0.08f))
            Text(
                text = "Edge",
                color = edgeColor.copy(alpha = alpha),
                fontSize = suffixFontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}
