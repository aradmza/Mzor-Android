package ir.mza.mzor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class OfflineStore {
    private static final String PREF = "mzor_offline";
    private static final String KEY = "notes";
    private final SharedPreferences prefs;

    public OfflineStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<String> notes() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void add(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return;
        List<String> all = notes();
        all.add(0, clean);
        if (all.size() > 40) all = all.subList(0, 40);
        JSONArray arr = new JSONArray();
        for (String item : all) arr.put(item);
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
