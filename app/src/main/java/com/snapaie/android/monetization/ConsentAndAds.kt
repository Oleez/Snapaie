package com.snapaie.android.monetization

import android.app.Activity
import androidx.annotation.MainThread
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

object ConsentAndAds {
    @MainThread
    fun requestConsentFormThenInitAds(activity: Activity, onFinished: () -> Unit) {
        val params = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)
            .build()

        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                MobileAds.initialize(activity.applicationContext)
                onFinished()
            }
        }) { _: com.google.android.ump.FormError ->
            MobileAds.initialize(activity.applicationContext)
            onFinished()
        }
    }

    fun canRequestAds(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
}
