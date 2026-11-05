package io.github.ababc0324.openthedoor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 开锁历史 —— 纯本地存储（SharedPreferences + JSON），**永不外传/无遥测**。
 * 最近在前，上限 {@link #MAX} 条，用于「我的门 · 已守护 N 次」与状态看板。
 */
public class DoorHistory {

    private static final String PREFS = "doorunlock_history";
    private static final String KEY = "entries";
    private static final int MAX = 50;

    private final SharedPreferences prefs;

    public DoorHistory(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Entry {
        public final long ts;
        public final String name;
        public final String mac;
        public Entry(long ts, String name, String mac) { this.ts = ts; this.name = name; this.mac = mac; }
    }

    /** 追加一条开门记录（最近在前）。 */
    public void add(String name, String mac) {
        List<Entry> all = loadAll();
        all.add(0, new Entry(System.currentTimeMillis(), name == null ? "我的门" : name, mac == null ? "" : mac));
        while (all.size() > MAX) all.remove(all.size() - 1);
        save(all);
    }

    public List<Entry> loadAll() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optLong("ts"), o.optString("name"), o.optString("mac")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public int count() { return loadAll().size(); }

    public void clear() { prefs.edit().remove(KEY).apply(); }

    private void save(List<Entry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : all) {
                JSONObject o = new JSONObject();
                o.put("ts", e.ts);
                o.put("name", e.name);
                o.put("mac", e.mac);
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) { }
    }
}
