package ir.mza.mzor;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ir.myket.billingclient.IabHelper;

public class MainActivity extends AppCompatActivity {
    private final ApiClient api = new ApiClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String token = "";
    private int convId = 0;
    private IabHelper billingHelper;
    private boolean billingReady;
    private boolean purchaseInFlight;

    private View loginPanel;
    private View listPanel;
    private View chatPanel;
    private TextView planLine;
    private TextView convList;
    private TextView chatText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("mzor", MODE_PRIVATE);
        token = prefs.getString("token", "");
        loginPanel = findViewById(R.id.loginPanel);
        listPanel = findViewById(R.id.listPanel);
        chatPanel = findViewById(R.id.chatPanel);
        planLine = findViewById(R.id.planLine);
        convList = findViewById(R.id.convList);
        chatText = findViewById(R.id.chatText);

        findViewById(R.id.loginBtn).setOnClickListener(v -> login());
        findViewById(R.id.newChatBtn).setOnClickListener(v -> createChat());
        findViewById(R.id.logoutBtn).setOnClickListener(v -> logout());
        findViewById(R.id.backBtn).setOnClickListener(v -> showList());
        findViewById(R.id.sendBtn).setOnClickListener(v -> send());
        findViewById(R.id.buyBtn).setOnClickListener(v -> buyPup());
        setupBilling();
        if (token.isEmpty()) showLogin(); else showList();
    }

    private void login() {
        String user = ((EditText) findViewById(R.id.loginUser)).getText().toString().trim();
        String pass = ((EditText) findViewById(R.id.loginPass)).getText().toString();
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("username", user).put("password", pass);
                JSONObject res = api.call("login", body, "");
                runOnUiThread(() -> {
                    if (res.optBoolean("success")) {
                        token = res.optString("token");
                        prefs.edit().putString("token", token).apply();
                        showList();
                    } else toast(res.optString("error", "ورود نشد"));
                });
            } catch (Exception e) { runOnUiThread(() -> toast("خطای شبکه")); }
        });
    }

    private void showLogin() {
        loginPanel.setVisibility(View.VISIBLE);
        listPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.GONE);
    }

    private void showList() {
        loginPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONObject me = api.call("me", new JSONObject(), token);
                JSONObject conv = api.call("conversations", new JSONObject(), token);
                runOnUiThread(() -> {
                    JSONObject user = me.optJSONObject("user");
                    if (user != null) planLine.setText(user.optString("plan_name") + " · " + user.optInt("remaining") + " واحد");
                    JSONArray items = conv.optJSONArray("items");
                    StringBuilder text = new StringBuilder();
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        text.append(item.optInt("id")).append(" · ").append(item.optString("title", "گفتگو")).append("\n");
                    }
                    convList.setText(text.length() == 0 ? "هنوز گفتگویی نیست" : text.toString().trim());
                });
            } catch (Exception e) { runOnUiThread(() -> toast("لیست نیامد")); }
        });
    }

    private void createChat() {
        io.execute(() -> {
            try {
                JSONObject res = api.call("create_conversation", new JSONObject(), token);
                JSONObject conv = res.optJSONObject("conversation");
                if (conv != null) runOnUiThread(() -> openChat(conv.optInt("id")));
            } catch (Exception e) { runOnUiThread(() -> toast("ساخت گفتگو نشد")); }
        });
    }

    private void openChat(int id) {
        convId = id;
        loginPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.GONE);
        chatPanel.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONObject res = api.call("messages", new JSONObject().put("conv_id", id), token);
                runOnUiThread(() -> renderMessages(res.optJSONArray("items")));
            } catch (Exception e) { runOnUiThread(() -> chatText.setText("پیام‌ها نیامد")); }
        });
    }

    private void renderMessages(JSONArray items) {
        StringBuilder text = new StringBuilder();
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String role = "bot".equals(item.optString("role")) ? "Mzor" : "تو";
            text.append(role).append(": ").append(item.optString("content")).append("\n\n");
        }
        chatText.setText(text.length() == 0 ? "اولین پیام را بنویس." : text.toString().trim());
    }

    private void send() {
        EditText input = findViewById(R.id.messageInput);
        String message = input.getText().toString().trim();
        if (message.isEmpty() || convId == 0) return;
        input.setText("");
        Button sendBtn = findViewById(R.id.sendBtn);
        sendBtn.setEnabled(false);
        io.execute(() -> {
            try {
                JSONObject res = api.call("send", new JSONObject().put("conv_id", convId).put("message", message), token);
                runOnUiThread(() -> {
                    sendBtn.setEnabled(true);
                    if (res.optBoolean("success")) openChat(convId);
                    else toast(res.optString("error", "فرستاده نشد"));
                });
            } catch (Exception e) { runOnUiThread(() -> { sendBtn.setEnabled(true); toast("خطای شبکه"); }); }
        });
    }

    private void buyPup() {
        if (!isMyketInstalled()) { toast(getString(R.string.payment_myket_missing)); return; }
        if (!billingReady || billingHelper == null) { toast(getString(R.string.payment_myket_only)); return; }
        if (purchaseInFlight) return;
        purchaseInFlight = true;
        billingHelper.launchPurchaseFlow(this, getString(R.string.myket_sku_pup), (result, info) -> runOnUiThread(() -> {
            purchaseInFlight = false;
            if (!result.isSuccess() || info == null) { toast(result.getMessage()); return; }
            confirmMyket(info.getSku(), info.getToken(), info.getOrderId());
            billingHelper.consumeAsync(info, (purchase, consumeResult) -> { });
        }), "");
    }

    private void confirmMyket(String sku, String purchaseToken, String orderId) {
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("sku", sku).put("token", purchaseToken).put("order_id", orderId);
                JSONObject res = api.call("confirm_myket", body, token);
                runOnUiThread(() -> {
                    toast(res.optBoolean("success") ? "پلن فعال شد" : res.optString("error", "فعال نشد"));
                    showList();
                });
            } catch (Exception e) { runOnUiThread(() -> toast("تایید سرور نشد")); }
        });
    }

    private void logout() {
        token = "";
        prefs.edit().remove("token").apply();
        showLogin();
    }

    private void setupBilling() {
        billingHelper = new IabHelper(this, getString(R.string.myket_public_key));
        billingHelper.startSetup(result -> runOnUiThread(() -> billingReady = result.isSuccess()));
    }

    private boolean isMyketInstalled() {
        try { getPackageManager().getPackageInfo("ir.mservices.market", 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override
    public void onBackPressed() {
        if (chatPanel.getVisibility() == View.VISIBLE) { showList(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (billingHelper != null) billingHelper.dispose();
        io.shutdownNow();
        super.onDestroy();
    }
}
