package io.github.ababc0324.openthedoor;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import android.util.Base64;

/**
 * AES 工具（逆向自 com.tmall.campus.utils.AESUtils + com.tmall.campus.doorlock.VocSecretKeyDecryptor）
 * <p>
 * 开门帧加密：AES/ECB/NoPadding，密钥 = key 前 16 字节（hex）
 * activationKey 解密：AES/GCM/NoPadding，密钥 = voc_lock_secret_key（32字节），IV = 密文前 12 字节
 */
public class AesUtils {

    /**
     * hex 字符串 -> byte[]
     */
    public static byte[] hexStringToByteArray(String s) {
        if (s == null) return null;
        String hex = s.replace(" ", "");
        if (hex.length() % 2 != 0) return null;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int h = Character.digit(hex.charAt(i * 2), 16);
            int l = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (h < 0 || l < 0) return null;
            out[i] = (byte) ((h << 4) | l);
        }
        return out;
    }

    /**
     * byte[] -> hex 字符串（小写）
     */
    public static String byteArrayToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * AES/ECB/NoPadding 加密（hex 输入输出）
     * key: hex 字符串，取前 16 字节作为 AES 密钥
     */
    public static String encryptHexString(String data, String key) {
        try {
            byte[] keyBytes = hexStringToByteArray(key);
            if (keyBytes == null) return null;
            byte[] aesKey = new byte[16];
            System.arraycopy(keyBytes, 0, aesKey, 0, Math.min(16, keyBytes.length));
            byte[] dataBytes = hexStringToByteArray(data);
            if (dataBytes == null) return null;
            SecretKeySpec skey = new SecretKeySpec(aesKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, skey);
            return byteArrayToHex(cipher.doFinal(dataBytes));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * AES/ECB/NoPadding 解密（hex 输入输出）
     */
    public static String decryptHexString(String hexData, String key) {
        try {
            byte[] keyBytes = hexStringToByteArray(key);
            if (keyBytes == null) return null;
            byte[] aesKey = new byte[16];
            System.arraycopy(keyBytes, 0, aesKey, 0, Math.min(16, keyBytes.length));
            byte[] dataBytes = hexStringToByteArray(hexData);
            if (dataBytes == null) return null;
            SecretKeySpec skey = new SecretKeySpec(aesKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, skey);
            return byteArrayToHex(cipher.doFinal(dataBytes));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 规范化 activationKey：接受 32 或 64 位 hex，返回末 32 位大写 hex；非法返回 null。
     * （activationKey 为 16 字节 = 32 位 hex；64 位时取末 32 位，与 XOR 派生一致）
     */
    public static String normalizeActivationKey(String key) {
        if (key == null) return null;
        String t = key.trim().replace(" ", "");
        if (t.matches("(?i)^[0-9a-f]{32}$")) return t.toUpperCase();
        if (t.matches("(?i)^[0-9a-f]{64}$")) return t.substring(32).toUpperCase();
        return null;
    }

    /**
     * 将服务器下发的 activationKey 解密为可直接使用的 32 位 hex 密钥。
     * 支持两种输入：
     * 1) 已经是 hex（32/64 位）-> 规范化返回（视为已解密的 activationKey）
     * 2) Base64 密文 -> 用 vocLockSecretKey（32字节 hex 或 UTF-8 字符串）AES/GCM/NoPadding 解密
     */
    public static String decryptActivationKey(String activationKey, String vocLockSecretKey) {
        if (activationKey == null) return null;
        String trimmed = activationKey.trim();
        // 已是 hex 且长度合理 -> 直接使用（已解密的 activationKey）
        String norm = normalizeActivationKey(trimmed);
        if (norm != null) return norm;
        // Base64 密文 -> AES/GCM 解密
        if (vocLockSecretKey == null || vocLockSecretKey.isEmpty()) {
            return null;
        }
        try {
            byte[] keyBytes = vocLockSecretKey.matches("(?i)^[0-9a-f]{64}$")
                    ? hexStringToByteArray(vocLockSecretKey)
                    : vocLockSecretKey.getBytes(StandardCharsets.UTF_8);
            if (keyBytes == null || keyBytes.length != 32) return null;
            byte[] cipherBytes = Base64.decode(trimmed, Base64.DEFAULT);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, cipherBytes, 0, 12);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), spec);
            byte[] plain = cipher.doFinal(cipherBytes, 12, cipherBytes.length - 12);
            // 明文可能是 16 字节原始密钥，或 32 位 hex ASCII 字符串
            if (plain.length == 16) return byteArrayToHex(plain).toUpperCase();
            String ascii = new String(plain, StandardCharsets.UTF_8).trim();
            norm = normalizeActivationKey(ascii);
            return norm;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
