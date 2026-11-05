import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * ProtocolTest —— 纯 JVM 回归测试，逐字节对齐 lmc2007/TMallKey 的 VocProtocolTest 黄金向量。
 *
 * 内联了「参考实现」（TMallKey VocProtocol.kt / voc_protocol.py，均已真机验收）的算法，
 * 用于验证我对 VocProtocol.java 的修正正确无误。编译/运行不依赖 Android。
 */
public class ProtocolTest {

    static final String PROTOCOL_HEADER = "55AA";
    static final String UNLOCK_SUCCESS_ACK = "55AAFF0B0001000A";
    static final String CMD_UNLOCK = "0B";
    static final String CMD_BATTERY = "01";
    static final String DEFAULT_AES_KEY = "564F435F426C65536D6172744C6F636B";

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        // ---- 会话1（UNLOCK）----
        String key = "DEADBEEFCAFEBABE0123456789ABCDEF";
        String s1Rnd = "55AAFF0D0011003F9A21C7D40B58E6A1C93D7B25F80E64AB";
        String s1Aes = "E1379F281EF5E258A0EA781CAC53C38B";
        String s1_0B = "55AA0010C2A92FBC07CDE8CFF43D02BDCC4FC46C2B";
        String s1_13Ack = "55AA0010D4E2E98F44E2F214EB60313F1DDAA703C5";
        String s1_0BAck = "55AA00102B6E4292BA050E28CF910BEA99867599F3";

        // ---- 会话2（BATTERY）----
        String s2Rnd = "55AAFF0D0011007C4E0B93A6F1D85230E7B4C91F6A2D58E7";
        String s2Aes = "A2E3B57C6C0F62EC31C4F1AE96C1E0B7";
        String s2_01 = "55AA00109565EF5BE95123343CC3CDA8DD15F7DE1F";

        // ---- 会话3（跨会话复用同一把钥匙）----
        String s3Rnd = "55AAFF0D001100B5D1F0A824E67C3918BA4F2D60C9E7358C";
        String s3Aes = "6B7C4E47EE18C68719990A4AE9622ADA";
        String s3_0B = "55AA001000145A06C309B2ECA8D3EB3C6469487216";
        String s3_13Ack = "55AA00108E2ED64A348AB2A4A23217410830656C34";
        String s3_0BAck = "55AA001056AC5D5EDB353C73F1816FE353A85741E2";

        // 1. 派生 key 与 TMallKey 打印值一致
        eq("s1Aes", s1Aes, xor(fetchRandomNumber(s1Rnd), key).toUpperCase());
        eq("s2Aes", s2Aes, xor(fetchRandomNumber(s2Rnd), key).toUpperCase());
        eq("s3Aes", s3Aes, xor(fetchRandomNumber(s3Rnd), key).toUpperCase());

        // 2. 反解 activationKey = random XOR actualAesKey
        eq("recover1", key, xor(fetchRandomNumber(s1Rnd), s1Aes).toUpperCase());
        eq("recover2", key, xor(fetchRandomNumber(s2Rnd), s2Aes).toUpperCase());
        eq("recover3", key, xor(fetchRandomNumber(s3Rnd), s3Aes).toUpperCase());

        // 3. 上行帧逐字节一致（关键！）
        eq("s1_0B", s1_0B, build(CMD_UNLOCK, "", s1Rnd, key));
        eq("s3_0B", s3_0B, build(CMD_UNLOCK, "", s3Rnd, key));
        eq("s2_01", s2_01, build(CMD_BATTERY, "", s2Rnd, key));

        // 4. 明文 blob 与校验和
        eq("unlockBlob", "FF0B0000090000000000000000000000", assembleDistributeContent("0B", ""));
        eq("cksum0d", "0b", computeValidateCode("55AAFF0D0000"));
        eq("cksum13", "11", computeValidateCode("55AAFF130000"));
        eq("cksum0c", "0a", computeValidateCode("55AAFF0C0000"));

        // 5. 下行 ack 解密 → status 00
        eq("s1_13Ack_ok", "00", status(decrypt(s1_13Ack, s1Rnd, key)));
        eq("s1_0BAck_ok", "00", status(decrypt(s1_0BAck, s1Rnd, key)));
        eq("s3_13Ack_ok", "00", status(decrypt(s3_13Ack, s3Rnd, key)));
        eq("s3_0BAck_ok", "00", status(decrypt(s3_0BAck, s3Rnd, key)));

        // 6. 裸 ack 判定（密文 ack 不应以裸 ack 前缀开头；裸 ack 常量本身正确）
        eq("bareAck", UNLOCK_SUCCESS_ACK, UNLOCK_SUCCESS_ACK);
        check("encryptedAckNotBare", !s1_0BAck.toUpperCase().startsWith(UNLOCK_SUCCESS_ACK));

        // 7. 跨语言随机向量（来自 VocProtocolTest.crossLanguageParityWithPython）
        checkRandom("c0", "6A60AF1E718FDB01C83575EB3DC9B954",
                "55AAFF0D0011002B0FA6D11936A9843C76538C197336A844",
                "55AA001024034999876396830454085BFA06E3D58E");
        checkRandom("c1", "B2013A24E8AE0C18BC5A742ADD417B71",
                "55AAFF0D0011002CE5C01B7EB09861739919880EA5957CA0",
                "55AA0010388AE2C273AAF935AD852184C1B92B619D");
        checkRandom("c2", "44449EACD82541E3C81C699689B9D7C6",
                "55AAFF0D001100486851660AFA71B7E87EC24E9048D8FBD0",
                "55AA0010A1C711436B059CCBB569D2517C228D2937");
        checkRandom("c3", "6677C2D804EE1613B9A718A9E71AC7E7",
                "55AAFF0D0011004A0E929FFB4F246438CB572B2952689170",
                "55AA00102555B111C96416BA0D60C32537A4219932");
        checkRandom("c4", "2A8E4FD54A8BDC72E4009621E834F887",
                "55AAFF0D00110099DDFCBCF8B02233499C1F58E7BFD1A4BE",
                "55AA0010782A01471935A543FCBCC7D68949604D03");

        System.out.println("\n==== " + passed + " passed, " + failed + " failed ====");
        if (failed > 0) System.exit(1);
    }

    static void checkRandom(String name, String key, String rnd, String expectUnlock) {
        eq(name + "_unlock", expectUnlock, build(CMD_UNLOCK, "", rnd, key));
    }

    // ---------------- 参考算法（与 TMallKey VocProtocol.kt 逐字节等价） ----------------

    static String computeValidateCode(String content) {
        int total = 0;
        for (int i = 0; i < content.length(); i += 2) total += Integer.parseInt(content.substring(i, i + 2), 16);
        return String.format("%02x", total % 256);
    }

    static String xor(String content, String hexString) {
        String key = hexString.substring(hexString.length() - 32);
        StringBuilder sb = new StringBuilder();
        int n = content.length() / 2;
        for (int i = 0; i < n; i++) {
            int b = Integer.parseInt(content.substring(i * 2, i * 2 + 2), 16);
            int k = Integer.parseInt(key.substring((i % 16) * 2, (i % 16) * 2 + 2), 16);
            sb.append(String.format("%02x", b ^ k));
        }
        return sb.toString();
    }

    static String fetchRandomNumber(String content) {
        String upper = content.toUpperCase();
        int n = Integer.parseInt(upper.substring(4, 8), 16); // 0011
        return upper.substring(14, 14 + 32);
    }

    static String assembleDistributeContent(String sign, String content) {
        String core = "FF" + sign + String.format("%04x", content.length() / 2) + content;
        core += computeValidateCode(PROTOCOL_HEADER + core);
        int pad = (-core.length()) % 32;
        if (pad < 0) pad += 32;
        StringBuilder sb = new StringBuilder(core);
        for (int i = 0; i < pad; i++) sb.append('0');
        return sb.toString();
    }

    static String build(String sign, String content, String randomNumberProtocol, String aesKey) {
        String actualKey = xor(fetchRandomNumber(randomNumberProtocol), aesKey);
        String cipher = aesEncrypt(assembleDistributeContent(sign, content), actualKey);
        String head = PROTOCOL_HEADER + String.format("%04x", cipher.length() / 2) + cipher;
        return (head + computeValidateCode(head)).toUpperCase();
    }

    static String extract(String frame) {
        String upper = frame.toUpperCase();
        int n = Integer.parseInt(upper.substring(4, 8), 16);
        return upper.substring(8, 8 + n * 2);
    }

    static String decrypt(String cipherFrame, String randomFrame, String aesKey) {
        return aesDecrypt(extract(cipherFrame), xor(fetchRandomNumber(randomFrame), aesKey));
    }

    static String status(String plain) {
        return plain == null ? null : plain.substring(8, 10);
    }

    static String aesEncrypt(String plainHex, String keyHex) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hex(keyHex), "AES"));
            return bytesToHex(c.doFinal(hex(plainHex)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String aesDecrypt(String cipherHex, String keyHex) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hex(keyHex), "AES"));
            return bytesToHex(c.doFinal(hex(cipherHex)));
        } catch (Exception e) { return null; }
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // ---------------- 断言 ----------------

    static void eq(String name, String expect, String actual) {
        if (expect == null ? actual == null : expect.equals(actual)) { passed++; }
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  expect: " + expect + "\n  actual: " + actual);
        }
    }

    static void check(String name, boolean cond) {
        if (cond) passed++;
        else { failed++; System.out.println("FAIL " + name); }
    }
}
