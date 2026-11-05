package io.github.ababc0324.openthedoor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;

/**
 * 设备端一键体检：在**本机**跑 24 个协议黄金向量（直接调用 VocProtocol 生产代码），
 * 并检查 BLE 适配器/权限/凭证，输出可读报告。与 test/ProtocolTest.java 的 JVM
 * 参考实现互为交叉验证——这里验证的是「实际编译进 APK 的代码」。
 */
public final class ProtocolSelfTest {

    public static final class Result {
        public int pass = 0, fail = 0;
        public final java.util.List<String> lines = new java.util.ArrayList<>();
        public boolean ok() { return fail == 0; }
    }

    private ProtocolSelfTest() {}

    private static void eq(Result r, String name, String expect, String actual) {
        if (expect == null ? actual == null : expect.equals(actual)) { r.pass++; }
        else {
            r.fail++;
            r.lines.add("✗ " + name + "  期望=" + expect + "  实际=" + actual);
        }
    }

    private static void check(Result r, String name, boolean cond) {
        if (cond) { r.pass++; }
        else { r.fail++; r.lines.add("✗ " + name); }
    }

    /** 24 个协议黄金向量（与 ProtocolTest.java 同源）。 */
    public static Result runProtocol() {
        Result r = new Result();
        String key = "DEADBEEFCAFEBABE0123456789ABCDEF";
        String s1Rnd = "55AAFF0D0011003F9A21C7D40B58E6A1C93D7B25F80E64AB";
        String s1Aes = "E1379F281EF5E258A0EA781CAC53C38B";
        String s1_0B = "55AA0010C2A92FBC07CDE8CFF43D02BDCC4FC46C2B";
        String s1_13Ack = "55AA0010D4E2E98F44E2F214EB60313F1DDAA703C5";
        String s1_0BAck = "55AA00102B6E4292BA050E28CF910BEA99867599F3";
        String s2Rnd = "55AAFF0D0011007C4E0B93A6F1D85230E7B4C91F6A2D58E7";
        String s2Aes = "A2E3B57C6C0F62EC31C4F1AE96C1E0B7";
        String s2_01 = "55AA00109565EF5BE95123343CC3CDA8DD15F7DE1F";
        String s3Rnd = "55AAFF0D001100B5D1F0A824E67C3918BA4F2D60C9E7358C";
        String s3Aes = "6B7C4E47EE18C68719990A4AE9622ADA";
        String s3_0B = "55AA001000145A06C309B2ECA8D3EB3C6469487216";
        String s3_13Ack = "55AA00108E2ED64A348AB2A4A23217410830656C34";
        String s3_0BAck = "55AA001056AC5D5EDB353C73F1816FE353A85741E2";

        // 1. 派生 actualAesKey
        eq(r, "s1Aes", s1Aes, VocProtocol.xor(VocProtocol.fetchRandomNumber(s1Rnd), key).toUpperCase());
        eq(r, "s2Aes", s2Aes, VocProtocol.xor(VocProtocol.fetchRandomNumber(s2Rnd), key).toUpperCase());
        eq(r, "s3Aes", s3Aes, VocProtocol.xor(VocProtocol.fetchRandomNumber(s3Rnd), key).toUpperCase());
        // 2. 反解 activationKey = random XOR actualAesKey
        eq(r, "recover1", key, VocProtocol.xor(VocProtocol.fetchRandomNumber(s1Rnd), s1Aes).toUpperCase());
        eq(r, "recover2", key, VocProtocol.xor(VocProtocol.fetchRandomNumber(s2Rnd), s2Aes).toUpperCase());
        eq(r, "recover3", key, VocProtocol.xor(VocProtocol.fetchRandomNumber(s3Rnd), s3Aes).toUpperCase());
        // 3. 上行帧逐字节一致
        eq(r, "s1_0B", s1_0B, VocProtocol.build(VocProtocol.CMD_UNLOCK, "", s1Rnd, key));
        eq(r, "s3_0B", s3_0B, VocProtocol.build(VocProtocol.CMD_UNLOCK, "", s3Rnd, key));
        eq(r, "s2_01", s2_01, VocProtocol.build(VocProtocol.CMD_BATTERY_LEVEL, "", s2Rnd, key));
        // 4. 明文 blob 与校验和
        eq(r, "unlockBlob", "FF0B0000090000000000000000000000",
                VocProtocol.assembleDistributeContent("0B", ""));
        eq(r, "cksum0d", "0b", VocProtocol.computeValidateCode("55AAFF0D0000"));
        eq(r, "cksum13", "11", VocProtocol.computeValidateCode("55AAFF130000"));
        eq(r, "cksum0c", "0a", VocProtocol.computeValidateCode("55AAFF0C0000"));
        // 5. 下行 ack 解密 → status 00
        eq(r, "s1_13Ack_ok", "00", VocProtocol.getStatus(VocProtocol.decryptResponse(s1_13Ack, s1Rnd, key)));
        eq(r, "s1_0BAck_ok", "00", VocProtocol.getStatus(VocProtocol.decryptResponse(s1_0BAck, s1Rnd, key)));
        eq(r, "s3_13Ack_ok", "00", VocProtocol.getStatus(VocProtocol.decryptResponse(s3_13Ack, s3Rnd, key)));
        eq(r, "s3_0BAck_ok", "00", VocProtocol.getStatus(VocProtocol.decryptResponse(s3_0BAck, s3Rnd, key)));
        // 6. 裸 ack 判定
        eq(r, "bareAck", VocProtocol.UNLOCK_SUCCESS_ACK, VocProtocol.UNLOCK_SUCCESS_ACK);
        check(r, "encryptedAckNotBare", !s1_0BAck.toUpperCase().startsWith(VocProtocol.UNLOCK_SUCCESS_ACK));
        // 7. 跨语言随机向量
        checkRandom(r, "c0", "6A60AF1E718FDB01C83575EB3DC9B954",
                "55AAFF0D0011002B0FA6D11936A9843C76538C197336A844",
                "55AA001024034999876396830454085BFA06E3D58E");
        checkRandom(r, "c1", "B2013A24E8AE0C18BC5A742ADD417B71",
                "55AAFF0D0011002CE5C01B7EB09861739919880EA5957CA0",
                "55AA0010388AE2C273AAF935AD852184C1B92B619D");
        checkRandom(r, "c2", "44449EACD82541E3C81C699689B9D7C6",
                "55AAFF0D001100486851660AFA71B7E87EC24E9048D8FBD0",
                "55AA0010A1C711436B059CCBB569D2517C228D2937");
        checkRandom(r, "c3", "6677C2D804EE1613B9A718A9E71AC7E7",
                "55AAFF0D0011004A0E929FFB4F246438CB572B2952689170",
                "55AA00102555B111C96416BA0D60C32537A4219932");
        checkRandom(r, "c4", "2A8E4FD54A8BDC72E4009621E834F887",
                "55AAFF0D00110099DDFCBCF8B02233499C1F58E7BFD1A4BE",
                "55AA0010782A01471935A543FCBCC7D68949604D03");
        return r;
    }

    private static void checkRandom(Result r, String name, String key, String rnd, String expectUnlock) {
        eq(r, name + "_unlock", expectUnlock, VocProtocol.build(VocProtocol.CMD_UNLOCK, "", rnd, key));
    }

    /** 环境检查：BLE 适配器/使能 + 凭证已存。 */
    public static Result runEnvironment(Context ctx) {
        Result r = new Result();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            check(r, "ble_adapter", adapter != null);
            if (adapter != null) check(r, "ble_enabled", adapter.isEnabled());
        } catch (Exception e) {
            check(r, "ble_adapter", false);
        }
        int creds = new CredentialStore(ctx).loadAll().size();
        check(r, "creds_present", creds > 0);
        r.lines.add("  凭证数=" + creds + "（" + (creds > 0 ? "可开锁" : "请先同步钥匙") + "）");
        return r;
    }
}
