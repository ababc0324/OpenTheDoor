package io.github.ababc0324.openthedoor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * activationKey 凭证存储（按 MAC 去重）。
 *
 * 与 TMallKey 一致：用 Android Keystore 的 AES-256-GCM 加密后落盘，密钥不可导出、
 * 随卸载销毁。API < 23 或 Keystore 不可用时回退到明文 SharedPreferences
 * （activationKey 本就会经 logcat 暴露，非强机密，仅为良好实践）。
 */
public class CredentialStore {

    private static final String PREFS = "doorunlock_creds";
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "doorunlock_cred";
    private static final String PREFIX = "cred_";

    private final SharedPreferences prefs;

    public CredentialStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String mac, String activationKey) {
        if (mac == null || activationKey == null) return;
        String k = mac.toUpperCase().replace(":", "");
        prefs.edit().putString(PREFIX + k, encrypt(activationKey)).apply();
    }

    public String load(String mac) {
        if (mac == null) return null;
        String k = mac.toUpperCase().replace(":", "");
        String stored = prefs.getString(PREFIX + k, null);
        return stored == null ? null : decrypt(stored);
    }

    /** 返回 无冒号MAC -> activationKey。 */
    public Map<String, String> loadAll() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith(PREFIX)) continue;
            String mac = e.getKey().substring(PREFIX.length());
            String key = decrypt(String.valueOf(e.getValue()));
            if (key != null) out.put(mac, key);
        }
        return out;
    }

    public void remove(String mac) {
        if (mac == null) return;
        prefs.edit().remove(PREFIX + mac.toUpperCase().replace(":", "")).apply();
    }

    // ---------------- Keystore AES-256-GCM ----------------

    private String encrypt(String plain) {
        try {
            SecretKey sk = getOrCreateKey();
            if (sk == null) return "plain:" + plain;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, sk);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "enc:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return "plain:" + plain;
        }
    }

    private String decrypt(String stored) {
        try {
            if (stored.startsWith("plain:")) return stored.substring(6);
            if (!stored.startsWith("enc:")) return null;
            byte[] all = Base64.decode(stored.substring(4), Base64.NO_WRAP);
            SecretKey sk = getOrCreateKey();
            if (sk == null) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, sk, new GCMParameterSpec(128, all, 0, 12));
            byte[] pt = c.doFinal(all, 12, all.length - 12);
            return new String(pt, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private SecretKey getOrCreateKey() {
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        } catch (Exception e) {
            return null;
        }
    }
}
