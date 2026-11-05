package io.github.ababc0324.openthedoor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 接收补丁版天猫校园（LSPatch 模块）广播的钥匙，落盘并转交给前台界面。
 * 声明在 AndroidManifest（exported），即使应用在后台也会被拉起落盘。
 */
public class KeySyncReceiver extends BroadcastReceiver {

    public interface Handler {
        void onKey(String mac, String activationKey);
    }

    private static volatile Handler handler;

    public static void setHandler(Handler h) {
        handler = h;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String mac = intent.getStringExtra(KeySyncHelper.EXTRA_MAC);
        String key = intent.getStringExtra(KeySyncHelper.EXTRA_KEY);
        if (mac == null || key == null || mac.isEmpty() || key.isEmpty()) return;
        new CredentialStore(context).save(mac, key);
        Handler h = handler;
        if (h != null) h.onKey(mac, key);
    }
}
