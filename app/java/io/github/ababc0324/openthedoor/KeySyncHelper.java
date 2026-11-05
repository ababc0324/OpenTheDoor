package io.github.ababc0324.openthedoor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 从补丁版天猫校园（LSPatch 模块）的本地回环 Socket 拉取已抓到的门锁钥匙。
 *
 * 与模块约定：127.0.0.1:42742，先发 token 一行，再读一行 JSON：
 *   {"doorlocks":[{"mac":"AA:BB:...","activationKey":"<32hex>"}]}
 * 回环连接不经过权限/包可见性检查，最稳健。
 */
public class KeySyncHelper {

    public static final int PORT = 42742;
    public static final String TOKEN = "doorunlock-sync-v1";
    public static final String ACTION_KEY = "io.github.ababc0324.openthedoor.ACTION_KEY";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_KEY = "activationKey";

    public interface Callback {
        void onResult(List<String[]> locks); // [mac, activationKey][]
        void onError(String message);
    }

    public static void pullAsync(final Callback cb) {
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
                s.setSoTimeout(3000);
                OutputStream os = s.getOutputStream();
                os.write((TOKEN + "\n").getBytes("UTF-8"));
                os.flush();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line = r.readLine();
                s.close();
                if (line == null) {
                    cb.onError("补丁版无响应");
                    return;
                }
                if ("no token".equals(line.trim())) {
                    cb.onError("token 不匹配");
                    return;
                }
                List<String[]> locks = parse(line);
                cb.onResult(locks);
            } catch (Exception e) {
                cb.onError("连接失败（请先打开补丁版天猫校园并点一次开锁）: " + e.getMessage());
            }
        }).start();
    }

    private static List<String[]> parse(String json) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("doorlocks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String mac = o.optString("mac");
                    String key = o.optString("activationKey");
                    if (mac.length() > 0 && key.length() > 0) out.add(new String[]{mac, key});
                }
            }
        } catch (Exception ignore) {
            // 解析失败返回空
        }
        return out;
    }
}
