package ir.mza.mzor;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    public static final String BASE = "https://mzaai.ir/api/app.php";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(130, TimeUnit.SECONDS)
            .build();

    public JSONObject call(String action, JSONObject body, String token) throws IOException {
        JSONObject payload = body == null ? new JSONObject() : body;
        Request.Builder req = new Request.Builder()
                .url(BASE + "?action=" + action)
                .post(RequestBody.create(payload.toString(), JSON));
        if (token != null && !token.isEmpty()) req.header("Authorization", "Bearer " + token);
        try (Response res = http.newCall(req.build()).execute()) {
            String text = res.body() != null ? res.body().string() : "";
            JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (!res.isSuccessful() && !json.has("error")) json.put("error", "خطای سرور " + res.code());
            return json;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage());
        }
    }
}
