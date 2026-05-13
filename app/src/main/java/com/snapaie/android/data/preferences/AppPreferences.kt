package com.snapaie.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore by preferencesDataStore(name = "snapaie_prefs")

class AppPreferencesRepository(private val context: Context) {

    private val onboardingDone = booleanPreferencesKey("onboarding_done")
    private val cachedIsPro = booleanPreferencesKey("cached_is_pro")

    val onboardingCompleted: Flow<Boolean> = context.preferencesDataStore.data.map {
        it[onboardingDone] == true
    }

    val storedProFallback: Flow<Boolean> = context.preferencesDataStore.data.map {
        it[cachedIsPro] == true
    }

    suspend fun setOnboardingCompleted() {
        context.preferencesDataStore.edit { prefs ->
            prefs[onboardingDone] = true
        }
    }

    suspend fun setCachedIsPro(isPro: Boolean) {
        context.preferencesDataStore.edit { prefs ->
            prefs[cachedIsPro] = isPro
        }
    }
}
