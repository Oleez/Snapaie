package com.snapaie.android.monetization

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.snapaie.android.BuildConfig

@Composable
fun ScanHubBannerAd(modifier: Modifier = Modifier.fillMaxWidth().wrapContentHeight(), isVisible: Boolean) {
    if (!isVisible) return

    AndroidView(
        modifier = modifier,
        factory = { context ->
            AdView(context).apply {
                val adWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt().coerceAtLeast(320)
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth))
                adUnitId = BuildConfig.ADMOB_BANNER_AD_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { ad ->
            ad.destroy()
        },
    )
}
