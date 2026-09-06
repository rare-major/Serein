package com.serein.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serein.reader.R

@Composable
fun SplashScreen() {
    val palette = LocalSereinPalette.current
    Surface(color = palette.paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.serein_logo_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(128.dp),
                )
                Text(
                    "SEREIN",
                    color = palette.ink,
                    fontFamily = LiterataFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    letterSpacing = 5.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    "A quiet place to read.",
                    color = palette.mutedInk,
                    fontFamily = LiterataFamily,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            CreatorCredit(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
        }
    }
}

@Composable
private fun CreatorCredit(modifier: Modifier = Modifier) {
    val palette = LocalSereinPalette.current
    val context = LocalContext.current
    val profileUrl = stringResource(R.string.creator_github_url)
    Text(
        text = buildAnnotatedString {
            append("Built by ")
            withStyle(SpanStyle(color = palette.sage, fontWeight = FontWeight.SemiBold)) {
                append("rare-major")
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = "Open rare-major on GitHub",
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl)))
                    }
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = palette.mutedInk,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
    )
}
