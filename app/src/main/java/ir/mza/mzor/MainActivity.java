package ir.mza.mzor;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import ir.myket.billingclient.IabHelper;

public class MainActivity extends AppCompatActivity {
    private final ApiClient api = new ApiClient();
    private WebView webView;
    private SharedPreferences prefs;
    private IabHelper billingHelper;
    private boolean billingReady;
    private boolean purchaseInFlight;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("mzor", MODE_PRIVATE);
        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.addJavascriptInterface(new StoreBridge(), "MzaStore");
        webView.addJavascriptInterface(new ApiBridge(), "MzaApi");
        webView.addJavascriptInterface(new BillingBridge(), "MzaAndroid");
        setupBilling();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupBilling() {
        billingHelper = new IabHelper(this, getString(R.string.myket_public_key));
        billingHelper.startSetup(result -> runOnUiThread(() -> billingReady = result.isSuccess()));
    }

    private boolean isMyketInstalled() {
        try { getPackageManager().getPackageInfo("ir.mservices.market", 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void startMyket(String sku) {
        if (!isMyketInstalled()) { toast(getString(R.string.payment_myket_missing)); return; }
        if (!billingReady || billingHelper == null) { toast(getString(R.string.payment_myket_only)); return; }
        if (purchaseInFlight) return;
        purchaseInFlight = true;
        billingHelper.launchPurchaseFlow(this, sku, (result, info) -> runOnUiThread(() -> {
            purchaseInFlight = false;
            if (!result.isSuccess() || info == null) { toast(result.getMessage()); return; }
            confirm(info.getSku(), info.getToken(), info.getOrderId());
            billingHelper.consumeAsync(info, (purchase, consumeResult) -> { });
        }), "");
    }

    private void confirm(String sku, String purchaseToken, String orderId) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("sku", sku).put("token", purchaseToken).put("order_id", orderId);
                JSONObject res = api.call("confirm_myket", body, prefs.getString("token", ""));
                runOnUiThread(() -> {
                    toast(res.optBoolean("success") ? "پلن فعال شد" : res.optString("error", "فعال نشد"));
                    webView.evaluateJavascript("refresh()", null);
                });
            } catch (Exception e) { runOnUiThread(() -> toast("تایید سرور نشد")); }
        }).start();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (billingHelper != null) billingHelper.dispose();
        super.onDestroy();
    }

    private class StoreBridge {
        @JavascriptInterface public String token() { return prefs.getString("token", ""); }
        @JavascriptInterface public void save(String token) { prefs.edit().putString("token", token).apply(); }
        @JavascriptInterface public void clear() { prefs.edit().remove("token").apply(); }
    }

    private class ApiBridge {
        @JavascriptInterface
        public String call(String action, String body, String token) {
            try {
                JSONObject json = body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
                return api.call(action, json, token).toString();
            } catch (Exception e) {
                return "{\"error\":\"\u062eطای شبکه\"}";
            }
        }
    }

    private class BillingBridge {
        @JavascriptInterface public void purchase(String sku) { runOnUiThread(() -> startMyket(sku)); }
    }
}
