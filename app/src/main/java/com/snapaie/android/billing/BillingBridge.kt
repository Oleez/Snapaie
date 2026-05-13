package com.snapaie.android.billing

import android.app.Application
import androidx.annotation.MainThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.snapaie.android.BuildConfig
import com.snapaie.android.data.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private var subscriptionOfferToken: String? = null

    private var subscriptionDetailsCache: ProductDetails? = null
    private var lifetimeDetailsCache: ProductDetails? = null

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
        .enablePendingPurchases()
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
                    appScope.launch {
                        queryProductDetails()
                        refreshPurchases()
                    }
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private suspend fun queryProductDetails() = suspendCancellableCoroutine { cont ->
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BuildConfig.BILLING_PRODUCT_SUBSCRIPTION)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BuildConfig.BILLING_PRODUCT_LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingResponseCode.OK && list != null) {
                for (pd in list) {
                    when (pd.productType) {
                        BillingClient.ProductType.SUBS -> {
                            subscriptionDetailsCache = pd
                            subscriptionOfferToken =
                                pd.subscriptionOfferDetails?.firstOrNull()?.offerToken
                        }
                        BillingClient.ProductType.INAPP -> lifetimeDetailsCache = pd
                    }
                }
            }
            if (cont.isActive) cont.resume(Unit)
        }
    }

    suspend fun refreshPurchases() {
        val all = mutableListOf<Purchase>()
        all += queryPurchasesForType(BillingClient.ProductType.INAPP)
        all += queryPurchasesForType(BillingClient.ProductType.SUBS)
        updateEntitlement(all)
    }

    private suspend fun queryPurchasesForType(@BillingClient.ProductType type: Int): List<Purchase> =
        suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build(),
            ) { result, purchases ->
                if (result.responseCode == BillingResponseCode.OK && purchases != null) {
                    cont.resume(purchases)
                } else {
                    cont.resume(emptyList())
                }
            }
        }

    fun launchMonthlyPurchase(activity: android.app.Activity) {
        launchIfReady(activity) {
            val details = subscriptionDetailsCache ?: return@launchIfReady
            val token = subscriptionOfferToken ?: return@launchIfReady
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(token)
                .build()
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
            client.launchBillingFlow(activity, flowParams)
        }
    }

    fun launchLifetimePurchase(activity: android.app.Activity) {
        launchIfReady(activity) {
            val details = lifetimeDetailsCache ?: return@launchIfReady
            val params = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(params))
                .build()
            client.launchBillingFlow(activity, flowParams)
        }
    }

    @MainThread
    private inline fun launchIfReady(activity: android.app.Activity, block: () -> Unit) {
        if (!client.isReady) return
        block()
    }

    fun restorePurchases() {
        appScope.launch { refreshPurchases() }
    }

    private fun updateEntitlement(purchases: List<Purchase>) {
        val lifetimeId = BuildConfig.BILLING_PRODUCT_LIFETIME
        val subscriptionId = BuildConfig.BILLING_PRODUCT_SUBSCRIPTION
        val unlocked = purchases.any { purchase ->
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return@any false
            purchase.products.any { it == lifetimeId || it == subscriptionId }
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
}
