package ir.mza.mzor;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;

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
    private DrawerLayout drawer;
    private TextView planLine;
    private LinearLayout convBox;
    private LinearLayout chatBox;
    private ScrollView chatScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("mzor", MODE_PRIVATE);
        token = prefs.getString("token", "");
        loginPanel = findViewById(R.id.loginPanel);
        drawer = findViewById(R.id.drawer);
        planLine = findViewById(R.id.planLine);
        convBox = findViewById(R.id.convBox);
        chatBox = findViewById(R.id.chatBox);
        chatScroll = findViewById(R.id.chatScroll);

        findViewById(R.id.loginBtn).setOnClickListener(v -> login());
        findViewById(R.id.newChatBtn).setOnClickListener(v -> createChat());
        findViewById(R.id.logoutBtn).setOnClickListener(v -> logout());
        findViewById(R.id.sendBtn).setOnClickListener(v -> send());
        findViewById(R.id.buyBtn).setOnClickListener(v -> buyPup());
        findViewById(R.id.menuBtn).setOnClickListener(v -> drawer.openDrawer(Gravity.START));
        setupBilling();
        if (token.isEmpty()) showLogin(); else showApp();
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
                        showApp();
                    } else toast(res.optString("error", "ورود نشد"));
                });
            } catch (Exception e) { runOnUiThread(() -> toast("خطای شبکه")); }
        });
    }

    private void showLogin() {
        loginPanel.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.GONE);
    }

    private void showApp() {
        loginPanel.setVisibility(View.GONE);
        drawer.setVisibility(View.VISIBLE);
        loadSidebar();
    }

    private void loadSidebar() {
        io.execute(() -> {
            try {
                JSONObject me = api.call("me", new JSONObject(), token);
                JSONObject conv = api.call("conversations", new JSONObject(), token);
                runOnUiThread(() -> {
                    JSONObject user = me.optJSONObject("user");
                    if (user != null) planLine.setText(user.optString("plan_name") + " · " + user.optInt("remaining"));
                    renderConversations(conv.optJSONArray("items"));
                });
            } catch (Exception e) { runOnUiThread(() -> toast("لیست نیامد")); }
        });
    }

    private void renderConversations(JSONArray items) {
        convBox.removeAllViews();
        if (items == null || items.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("هنوز گفتگویی نیست");
            empty.setTextColor(Color.parseColor("#A8B3A0"));
            empty.setPadding(8, 12, 8, 12);
            convBox.addView(empty);
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            int id = item.optInt("id");
            Button btn = new Button(this);
            btn.setText(item.optString("title", "گفتگو"));
            btn.setAllCaps(false);
            btn.setBackgroundColor(Color.TRANSPARENT);
            btn.setTextColor(Color.parseColor("#FBFFF8"));
            btn.setGravity(Gravity.RIGHT);
            btn.setOnClickListener(v -> {
                drawer.closeDrawer(Gravity.START);
                openChat(id);
            });
            convBox.addView(btn);
        }
    }

    private void createChat() {
        io.execute(() -> {
            try {
                JSONObject res = api.call("create_conversation", new JSONObject(), token);
                JSONObject conv = res.optJSONObject("conversation");
                if (conv != null) runOnUiThread(() -> {
                    drawer.closeDrawer(Gravity.START);
                    openChat(conv.optInt("id"));
                    loadSidebar();
                });
            } catch (Exception e) { runOnUiThread(() -> toast("ساخت گفتگو نشد")); }
        });
    }

    private void openChat(int id) {
        convId = id;
        io.execute(() -> {
            try {
                JSONObject res = api.call("messages", new JSONObject().put("conv_id", id), token);
                runOnUiThread(() -> renderMessages(res.optJSONArray("items")));
            } catch (Exception e) { runOnUiThread(() -> toast("پیام‌ها نیامد")); }
        });
    }

    private void renderMessages(JSONArray items) {
        chatBox.removeAllViews();
        if (items == null || items.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("اولین پیام را بنویس.");
            empty.setTextColor(Color.parseColor("#A8B3A0"));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 80, 0, 0);
            chatBox.addView(empty);
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            boolean mine = !"bot".equals(item.optString("role"));
            TextView bubble = new TextView(this);
            bubble.setText(item.optString("content"));
            bubble.setTextSize(15);
            bubble.setPadding(28, 22, 28, 22);
            bubble.setBackgroundResource(mine ? R.drawable.bg_user : R.drawable.bg_bot);
            bubble.setTextColor(Color.parseColor(mine ? "#C8E8A8" : "#FBFFF8"));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            box.topMargin = 12;
            box.gravity = mine ? Gravity.START : Gravity.END;
            bubble.setLayoutParams(box);
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82));
            chatBox.addView(bubble);
        }
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void send() {
        EditText input = findViewById(R.id.messageInput);
        String message = input.getText().toString().trim();
        if (message.isEmpty()) return;
        if (convId == 0) { createThenSend(message); return; }
        input.setText("");
        Button sendBtn = findViewById(R.id.sendBtn);
        sendBtn.setEnabled(false);
        io.execute(() -> {
            try {
                JSONObject res = api.call("send", new JSONObject().put("conv_id", convId).put("message", message), token);
                runOnUiThread(() -> {
                    sendBtn.setEnabled(true);
                    if (res.optBoolean("success")) { openChat(convId); loadSidebar(); }
                    else toast(res.optString("error", "فرستاده نشد"));
                });
            } catch (Exception e) { runOnUiThread(() -> { sendBtn.setEnabled(true); toast("خطای شبکه"); }); }
        });
    }

    private void createThenSend(String message) {
        io.execute(() -> {
            try {
                JSONObject res = api.call("create_conversation", new JSONObject(), token);
                JSONObject conv = res.optJSONObject("conversation");
                if (conv == null) { runOnUiThread(() -> toast("گفتگو ساخته نشد")); return; }
                convId = conv.optInt("id");
                runOnUiThread(() -> {
                    ((EditText) findViewById(R.id.messageInput)).setText(message);
                    send();
                });
            } catch (Exception e) { runOnUiThread(() -> toast("خطای شبکه")); }
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
                    loadSidebar();
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
        if (drawer.getVisibility() == View.VISIBLE && drawer.isDrawerOpen(Gravity.START)) {
            drawer.closeDrawer(Gravity.START);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (billingHelper != null) billingHelper.dispose();
        io.shutdownNow();
        super.onDestroy();
    }
}
