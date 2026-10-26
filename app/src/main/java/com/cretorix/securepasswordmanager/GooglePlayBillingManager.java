package com.cretorix.securepasswordmanager;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Connects Play's one-time ad-removal product to the local offline entitlement cache. */
public final class GooglePlayBillingManager {
    public static final String AD_FREE_PRODUCT_ID = "pro_unlock";

    public interface Listener {
        void onProductAvailable(String formattedPrice);

        void onProductUnavailable();

        void onEntitlementChanged();

        void onRestoreCompleted(boolean hasAdFreePurchase);

        void onPurchasePending();

        void onPurchaseCanceled();

        void onBillingUnavailable();
    }

    private final PremiumManager premiumManager;
    private final Listener listener;
    private final BillingClient billingClient;
    private final Set<String> acknowledgementsInFlight = new HashSet<>();
    private final Set<String> acknowledgedTokens = new HashSet<>();
    private ProductDetails productDetails;
    private boolean connectionStarted;
    private boolean restoreRequested;
    private boolean closed;

    public GooglePlayBillingManager(Context context, PremiumManager premiumManager,
                                    Listener listener) {
        this.premiumManager = premiumManager;
        this.listener = listener;
        billingClient = BillingClient.newBuilder(context.getApplicationContext())
                .setListener(this::onPurchasesUpdated)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
    }

    public void start() {
        if (closed || connectionStarted) {
            return;
        }
        connectionStarted = true;
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (closed) {
                    return;
                }
                if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    connectionStarted = false;
                    listener.onBillingUnavailable();
                    return;
                }
                queryProductDetails();
                boolean restore = restoreRequested;
                restoreRequested = false;
                queryOwnedPurchases(restore);
            }

            @Override
            public void onBillingServiceDisconnected() {
                connectionStarted = false;
            }
        });
    }

    public void launchPurchase(Activity activity) {
        if (closed) {
            return;
        }
        if (!billingClient.isReady()) {
            if (!connectionStarted) {
                start();
            }
            listener.onBillingUnavailable();
            return;
        }
        if (productDetails == null) {
            listener.onProductUnavailable();
            return;
        }
        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                productDetails.getOneTimePurchaseOfferDetailsList();
        if (offers == null || offers.isEmpty()) {
            listener.onProductUnavailable();
            return;
        }

        ProductDetails.OneTimePurchaseOfferDetails offer = offers.get(0);
        BillingFlowParams.ProductDetailsParams detailsParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offer.getOfferToken())
                        .build();
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(detailsParams))
                .build();
        BillingResult result = billingClient.launchBillingFlow(activity, flowParams);
        if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            queryOwnedPurchases(true);
        } else if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            listener.onBillingUnavailable();
        }
    }

    public void restorePurchases() {
        if (closed) {
            return;
        }
        if (!billingClient.isReady()) {
            restoreRequested = true;
            if (!connectionStarted) {
                start();
            }
            return;
        }
        queryOwnedPurchases(true);
    }

    public void close() {
        closed = true;
        billingClient.endConnection();
    }

    private void queryProductDetails() {
        QueryProductDetailsParams.Product product =
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(AD_FREE_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build();
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product))
                .build();
        billingClient.queryProductDetailsAsync(params, (billingResult, result) -> {
            if (closed) {
                return;
            }
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                productDetails = null;
                listener.onProductUnavailable();
                return;
            }
            productDetails = null;
            for (ProductDetails details : result.getProductDetailsList()) {
                if (AD_FREE_PRODUCT_ID.equals(details.getProductId())) {
                    productDetails = details;
                    break;
                }
            }
            List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                    productDetails == null ? null
                            : productDetails.getOneTimePurchaseOfferDetailsList();
            if (offers == null || offers.isEmpty()) {
                productDetails = null;
                listener.onProductUnavailable();
                return;
            }
            listener.onProductAvailable(offers.get(0).getFormattedPrice());
        });
    }

    private void queryOwnedPurchases(boolean isRestore) {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (closed) {
                return;
            }
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                listener.onBillingUnavailable();
                return;
            }
            processPurchases(purchases, true);
            if (isRestore) {
                listener.onRestoreCompleted(premiumManager.isPlayAdFree());
            }
        });
    }

    private void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (closed) {
            return;
        }
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null) {
            boolean hasAdFreePurchase = false;
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(AD_FREE_PRODUCT_ID)) {
                    hasAdFreePurchase = true;
                    break;
                }
            }
            if (hasAdFreePurchase) {
                processPurchases(purchases, false);
            } else {
                listener.onBillingUnavailable();
            }
        } else if (billingResult.getResponseCode()
                == BillingClient.BillingResponseCode.USER_CANCELED) {
            listener.onPurchaseCanceled();
        } else {
            listener.onBillingUnavailable();
        }
    }

    private void processPurchases(List<Purchase> purchases, boolean authoritativeQuery) {
        boolean hasAdFreePurchase = false;
        boolean hasPendingAdFreePurchase = false;
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(AD_FREE_PRODUCT_ID)) {
                continue;
            }
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                hasPendingAdFreePurchase = true;
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                hasAdFreePurchase = true;
                String token = purchase.getPurchaseToken();
                if (purchase.isAcknowledged() || acknowledgedTokens.contains(token)) {
                    premiumManager.setPlayAdFree(true);
                    listener.onEntitlementChanged();
                } else {
                    acknowledge(purchase);
                }
            }
        }

        if (!hasAdFreePurchase && !hasPendingAdFreePurchase && authoritativeQuery) {
            premiumManager.setPlayAdFree(false);
            listener.onEntitlementChanged();
        } else if (hasPendingAdFreePurchase) {
            listener.onPurchasePending();
        }
    }

    private void acknowledge(Purchase purchase) {
        String token = purchase.getPurchaseToken();
        if (!acknowledgementsInFlight.add(token)) {
            return;
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(token)
                .build();
        billingClient.acknowledgePurchase(params, billingResult -> {
            acknowledgementsInFlight.remove(token);
            if (closed) {
                return;
            }
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                acknowledgedTokens.add(token);
                premiumManager.setPlayAdFree(true);
                listener.onEntitlementChanged();
            } else {
                listener.onBillingUnavailable();
            }
        });
    }
}
