package io.github.ababc0324.openthedoor;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭证加密备份/恢复（换机/重装不丢钥匙）。
 *
 * 用户自设口令 → PBKDF2WithHmacSHA1(口令, salt, 100k 次) 派生 AES-256 密钥 →
 * AES/GCM 加密 JSON 列表（[{mac, key}]）。格式自描述：
 *   "OTDB1|salt_b64|iv_b64|cipher_b64"
 * 口令错误/文件被篡改 → GCM 认证失败 → 抛异常。
 *
 * 注意：与 Keystore 存储无关，这是一次性的可迁移快照；恢复后仍走 Keystore 加密落盘。
 */
public final class CredentialBackup {

    private static final String MAGIC = "OTDB1";
    private static final int ITER = 100_000;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;

    private CredentialBackup() {}

    /** 导出全部凭证为自描述加密字符串。 */
    public static String export(CredentialStore store, String passphrase) throws Exception {
        Map<String, String> all = store.loadAll();
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : all.entrySet()) {
            JSONObject o = new JSONObject();
            o.put("mac", e.getKey());
            o.put("key", e.getValue());
            arr.put(o);
        }
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(passphrase, salt), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(arr.toString().getBytes(StandardCharsets.UTF_8));
        return MAGIC + "|" + Base64.encodeToString(salt, Base64.NO_WRAP)
                + "|" + Base64.encodeToString(iv, Base64.NO_WRAP)
                + "|" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    /** 解析并导入凭证，返回导入条数；口令错误/格式非法抛异常。 */
    public static int importTo(CredentialStore store, String data, String passphrase) throws Exception {
        String[] parts = data.split("\\|");
        if (parts.length != 4 || !MAGIC.equals(parts[0])) {
            throw new IllegalArgumentException("备份文件格式不正确");
        }
        byte[] salt = Base64.decode(parts[1], Base64.NO_WRAP);
        byte[] iv = Base64.decode(parts[2], Base64.NO_WRAP);
        byte[] ct = Base64.decode(parts[3], Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(passphrase, salt), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] pt = c.doFinal(ct);
        JSONArray arr = new JSONArray(new String(pt, StandardCharsets.UTF_8));
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String mac = o.optString("mac");
            String k = o.optString("key");
            if (mac.length() > 0 && k.length() > 0) { store.save(mac, k); n++; }
        }
        return n;
    }

    private static byte[] deriveKey(String pass, byte[] salt) throws Exception {
        // PBKDF2WithHmacSHA1 自 API 10 可用（SHA256 需 API 26，minSdk 21 下不可用）
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, ITER, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).getEncoded();
    }
}
