package se.lublin.mumla.app

import android.app.Activity
import android.content.SharedPreferences
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode.OK
import com.android.billingclient.api.BillingClient.ProductType.INAPP
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.R
import java.util.concurrent.TimeUnit

/** Shows news, or else now and then asks for an in-app donation, and re-checks a donation made. */
class StartupAction : IStartupAction {
    private var billingClient: BillingClient? = null

    override fun execute(activity: Activity) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)

        val oldStartupCount = preferences.getInt(PREF_STARTUP_COUNT, 0)
        val startupCount = if (oldStartupCount == Int.MAX_VALUE) 1 else oldStartupCount + 1
        preferences.edit { putInt(PREF_STARTUP_COUNT, startupCount) }

        // News shown: nothing else this time.
        if (maybeShowNewsDialog(activity)) return

        if (preferences.getBoolean(PREF_HAS_DONATED, false)) {
            val lastVerification = preferences.getLong(PREF_LAST_DONATION_VERIFY_TIMESTAMP, 0)
            if (System.currentTimeMillis() - lastVerification > VERIFY_INTERVAL) {
                verifyPurchase(activity, preferences)
            }
            return
        }

        val onDonated = {
            preferences.edit { putBoolean(PREF_HAS_DONATED, true) }
            showToast(activity, activity.getString(R.string.donate_thanks_goog))
        }
        val client = BillingClient.newBuilder(activity)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .setListener { result, purchases ->
                // A new purchase.
                if (result.responseCode != OK || purchases == null) return@setListener
                purchases.filter { DONATION_PRODUCT_ID in it.products }
                    .forEach { handleAndAckPurchase(activity, it, onDonated) }
            }
            .build()
        billingClient = client

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != OK) {
                    showToast(activity, failure("setup billing", result))
                    return
                }
                // Already purchased? Checked before possibly showing the dialog.
                client.queryPurchasesAsync(inAppPurchases()) { queryResult, purchases ->
                    if (queryResult.responseCode != OK) {
                        showToast(activity, failure("query purchases", queryResult))
                        return@queryPurchasesAsync
                    }
                    val donation = purchases.firstOrNull { DONATION_PRODUCT_ID in it.products }
                    if (donation != null) {
                        handleAndAckPurchase(activity, donation, onDonated)
                    } else if (startupCount % PROMPT_CYCLE in PROMPTED_STARTS) {
                        showDonationDialog(activity)
                    }
                }
            }

            // Retried on the next app start.
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun verifyPurchase(activity: Activity, preferences: SharedPreferences) {
        val client = BillingClient.newBuilder(activity)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .setListener { _, _ -> }
            .build()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != OK) return
                client.queryPurchasesAsync(inAppPurchases()) { queryResult, purchases ->
                    if (queryResult.responseCode != OK) return@queryPurchasesAsync
                    val stillFound = purchases.any { DONATION_PRODUCT_ID in it.products && it.isAcknowledged }
                    if (!stillFound) {
                        showToast(activity, "Your donation was refunded or revoked")
                        preferences.edit { putBoolean(PREF_HAS_DONATED, false) }
                    }
                    preferences.edit { putLong(PREF_LAST_DONATION_VERIFY_TIMESTAMP, System.currentTimeMillis()) }
                    client.endConnection()
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun showDonationDialog(activity: Activity) = activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.donate_dialog_title_goog)
            .setMessage(R.string.donate_dialog_message_goog)
            .setIcon(DONATION_ICONS.random())
            .setCancelable(false)
            .setPositiveButton(R.string.donate_dialog_positivebutton_goog) { _, _ -> launchPurchaseFlow(activity) }
            .setNegativeButton(R.string.donate_dialog_negativebutton_goog) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun launchPurchaseFlow(activity: Activity) {
        val client = billingClient
        if (client == null || !client.isReady) {
            showToast(activity, "Billing client is not ready")
            return
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(DONATION_PRODUCT_ID)
            .setProductType(INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { queryResult, detailsResult ->
            val productDetails = detailsResult.productDetailsList
            if (queryResult.responseCode != OK || productDetails.isEmpty()) {
                showToast(activity, failure("query product details", queryResult))
                return@queryProductDetailsAsync
            }
            activity.runOnUiThread {
                val details = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails[0])
                    .build()
                val flowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(details)).build()
                client.launchBillingFlow(activity, flowParams)
            }
        }
    }

    private fun handleAndAckPurchase(activity: Activity, purchase: Purchase, onAckSuccess: () -> Unit) {
        if (purchase.isAcknowledged) {
            onAckSuccess()
            return
        }
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            showToast(activity, activity.getString(R.string.donate_purchase_pending_goog))
            return
        }
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient?.acknowledgePurchase(params) { result ->
            if (result.responseCode != OK) {
                showToast(activity, failure("acknowledge purchase", result))
                return@acknowledgePurchase
            }
            onAckSuccess()
        }
    }

    private fun showToast(activity: Activity, text: String) = activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed) {
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val DONATION_PRODUCT_ID = "mumla_donation_1"
        const val PREF_STARTUP_COUNT = "startupCount"
        const val PREF_HAS_DONATED = "hasDonated"
        const val PREF_LAST_DONATION_VERIFY_TIMESTAMP = "lastDonationVerifyTimestamp"
        /** Of every [PROMPT_CYCLE] starts, those whose number modulo it is in [PROMPTED_STARTS] ask. */
        const val PROMPT_CYCLE = 5
        val PROMPTED_STARTS = setOf(1, 3)
        val VERIFY_INTERVAL = TimeUnit.DAYS.toMillis(2)
        val DONATION_ICONS = listOf(
            R.drawable.ic_donate_heart_goog,
            R.drawable.ic_donate_tag_faces_goog,
            R.drawable.ic_donate_handshake_goog,
            R.drawable.ic_donate_heart_smile_goog,
            R.drawable.ic_donate_waving_hand_goog,
        )

        fun failure(what: String, result: BillingResult) =
            "Failed to $what: ${result.debugMessage} (code ${result.responseCode})"

        fun inAppPurchases(): QueryPurchasesParams =
            QueryPurchasesParams.newBuilder().setProductType(INAPP).build()
    }
}
