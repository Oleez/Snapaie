package com.snapaie.android.billing

import android.app.Application
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.snapaie.android.BuildConfig
import com.snapaie.android.data.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BillingBridge(
    private val app: Application,
    private val preferencesRepository: AppPreferencesRepository,
    private val appScope: CoroutineScope,
) {

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _lifetimePrice = MutableStateFlow<String?>(null)
    val lifetimePrice: StateFlow<String?> = _lifetimePrice.asStateFlow()

    private var lifetimeDetailsCache: ProductDetails? = null
    private var reconnectAttempts = 0

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (!purchases.isNullOrEmpty() && result.responseCode == BillingResponseCode.OK) {
            updateEntitlement(purchases)
            purchases.forEach { ackIfNeeded(it) }
        } else {
            appScope.launch { refreshPurchases() }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    init {
        appScope.launch(Dispatchers.IO) {
            if (preferencesRepository.storedProFallback.first()) {
                _isPro.value = true
            }
        }
    }

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    reconnectAttempts = 0
                    appScope.launch {
                        queryProductDetails()
                        refreshPurchases()
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                val attempt = ++reconnectAttempts
                if (attempt > MAX_RECONNECT_ATTEMPTS) return
                appScope.launch {
                    delay(RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1)))
                    start()
                }
            }
        })
    }

    private suspend fun queryProductDetails() = suspendCancellableCoroutine { cont ->
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BuildConfig.BILLING_PRODUCT_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode == BillingResponseCode.OK) {
                lifetimeDetailsCache = productDetailsResult.productDetailsList.firstOrNull()
                _lifetimePrice.value =
                    lifetimeDetailsCache?.oneTimePurchaseOfferDetails?.formattedPrice
            }
            if (cont.isActive) cont.resume(Unit)
        }
    }

    suspend fun refreshPurchases() {
        val all = mutableListOf<Purchase>()
        all += queryPurchasesForType(BillingClient.ProductType.INAPP)
        // Legacy: honor subscriptions bought before the one-time-unlock switch.
        all += queryPurchasesForType(BillingClient.ProductType.SUBS)
        updateEntitlement(all)
    }

    private suspend fun queryPurchasesForType(type: String): List<Purchase> =
        suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build(),
            ) { result, purchases ->
                if (result.responseCode == BillingResponseCode.OK) {
                    cont.resume(purchases)
                } else {
                    cont.resume(emptyList())
                }
            }
        }

    fun launchLifetimePurchase(activity: android.app.Activity) {
        if (!client.isReady) return
        val details = lifetimeDetailsCache ?: return
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            .build()
        client.launchBillingFlow(activity, flowParams)
    }

    fun restorePurchases() {
        appScope.launch { refreshPurchases() }
    }

    private fun updateEntitlement(purchases: List<Purchase>) {
        val lifetimeId = BuildConfig.BILLING_PRODUCT_LIFETIME
        val unlocked = purchases.any { purchase ->
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return@any false
            purchase.products.any { it == lifetimeId || it == LEGACY_SUBSCRIPTION_ID }
        }
        _isPro.value = unlocked
        appScope.launch(Dispatchers.IO) {
            preferencesRepository.setCachedIsPro(unlocked)
        }
    }

    private fun ackIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { }
    }

    private companion object {
        const val LEGACY_SUBSCRIPTION_ID = "snapaie_pro_monthly"
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RECONNECT_BASE_DELAY_MS = 1_000L
    }
}
