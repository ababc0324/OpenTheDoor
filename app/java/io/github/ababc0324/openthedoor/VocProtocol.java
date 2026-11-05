package io.github.ababc0324.openthedoor;

/**
 * VOC 门锁 BLE 协议帧构建/解析（逆向自 com.tmall.campus.doorlock.bluetooth.BluetoothHelper）
 *
 * 逐字节对齐 lmc2007/TMallKey 的 VocProtocol.kt / fktm/voc_protocol.py（两者均通过
 * 真机抓包 + 实开双重验收），并已用其黄金向量在 test/ProtocolTest.java 中验证（24/24）。
 *
 * 帧格式（两种，别混）：
 *   [明文帧] 55AA | FF | cmd(1B) | len(2B,大端) | payload | cksum(1B)
 *            cksum = (cksum 之前所有字节之和) % 256
 *   [密文帧] 55AA | len(2B,大端) | cipher | cksum(1B)
 *            cipher = AES-128/ECB/NoPadding( FF|sign|len(2B)|content|cksum 补零对齐 32hex, actualAesKey )
 *
 * 真机实测帧型：
 *   0D 请求随机数   → 明文固定帧 55AAFF0D00000B   响应=明文(含 16B 随机数)
 *   13 建立加密链路 → 明文固定帧 55AAFF13000011  响应=密文（需解密校验）
 *   0B 开锁        → 密文帧(content 空)         响应=密文或裸 ack
 *   01 电量        → 密文帧(content 空)         响应=密文
 *
 * 密钥派生（challenge-response）：
 *   randomNumber   = 0D 响应的 [14:46]（32 hex = 16 字节）
 *   actualAesKey   = randomNumber XOR activationKey 的后 16 字节
 *   开锁 content 为空 → 拿到 activationKey 即可永久离线开锁
 */
public final class VocProtocol {

    private VocProtocol() {}

    public static final String PROTOCOL_HEADER = "55AA";

    /** ASCII "VOC_BleSmartLock"，仅激活阶段使用 */
    public static final String DEFAULT_AES_KEY = "564F435F426C65536D6172744C6F636B";

    /** 开锁成功裸 ack（明文帧，7 字节） */
    public static final String UNLOCK_SUCCESS_ACK = "55AAFF0B0001000A";

    /** 固定明文帧（直接按字节下发，不经 AES） */
    public static final String REQUEST_RANDOM_NUMBER = "55AAFF0D00000B";
    public static final String ENCRYPTED_LINK_ESTABLISH = "55AAFF13000011";

    // 命令码 (sign)
    public static final String CMD_REQUEST_RANDOM_NUMBER = "0D";
    public static final String CMD_ENCRYPTED_LINK_ESTABLISH = "13";
    public static final String CMD_BATTERY_LEVEL = "01";
    public static final String CMD_UNLOCK = "0B";
    public static final String CMD_GENERATE_KEY = "0E";
    public static final String CMD_ACTIVATE = "06";
    public static final String CMD_RESET = "0C";

    public static final String CODE_OK = "00";

    /** 错误码文案（BluetoothHelper.CODE_MAP） */
    private static final java.util.Map<String, String> CODE_MAP;
    static {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("01", "格式错误"); m.put("02", "超时"); m.put("03", "ID已存在");
        m.put("04", "添加失败"); m.put("05", "删除失败"); m.put("06", "修改失败");
        m.put("07", "ID已满"); m.put("08", "手动取消"); m.put("09", "等待");
        m.put("0a", "ID不存在"); m.put("0b", "门锁未进入待激活状态");
        m.put("0c", "系统锁定中"); m.put("0d", "请先关闭安全模式");
        m.put("0e", "请先开启所有开门方式"); m.put("0f", "超出规定绑定时间");
        m.put("10", "超出规定时间内的绑定次数"); m.put("11", "密码过于简单");
        m.put("12", "锁体信息查询失败"); m.put("13", "锁体配置失败"); m.put("14", "锁体不适配");
        CODE_MAP = m;
    }

    /** 0D 响应帧模式：55AA FF 0D 0011 XX <random16B> <cksum> */
    private static final String RANDOM_PATTERN =
            "^55AA[0-9A-Fa-f]{2}0D0011[0-9A-Fa-f]{2}[0-9A-Fa-f]{32}[0-9A-Fa-f]{2}$";

    // ==================== 基础算法 ====================

    /** BluetoothHelper.computeValidateCode — 字节和 % 256，输出 2 位小写 hex。 */
    public static String computeValidateCode(String content) {
        if (content == null || content.isEmpty()) return "00";
        int total = 0;
        for (int i = 0; i + 1 < content.length(); i += 2) {
            int hi = Character.digit(content.charAt(i), 16);
            int lo = Character.digit(content.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) continue;
            total += (hi << 4) | lo;
        }
        return String.format("%02x", total & 0xFF);
    }

    /** BluetoothHelper.xor — content 逐字节 XOR key 的末 16 字节（循环）。 */
    public static String xor(String content, String hexString) {
        if (content == null || hexString == null) return null;
        String key = hexString.substring(Math.max(0, hexString.length() - 32));
        StringBuilder sb = new StringBuilder(content.length());
        int n = content.length() / 2;
        for (int i = 0; i < n; i++) {
            int b = Integer.parseInt(content.substring(i * 2, i * 2 + 2), 16);
            int k = Integer.parseInt(key.substring((i % 16) * 2, (i % 16) * 2 + 2), 16);
            sb.append(String.format("%02x", b ^ k));
        }
        return sb.toString();
    }

    /**
     * BluetoothHelper.fetchRandomNumber — 从 0D 响应帧中取出 16 字节随机数（32 hex）。
     * 兼容两种输入：完整 0D 响应帧，或已是裸 32 hex 随机数。
     */
    public static String fetchRandomNumber(String content) {
        if (content == null) return null;
        String upper = content.trim().toUpperCase();
        if (upper.matches(RANDOM_PATTERN)) return upper.substring(14, 46);
        if (upper.matches("[0-9A-F]{32}")) return upper;
        return null;
    }

    // ==================== 明文 blob 组装 ====================

    /** FF | sign | contentLen(2B) | content | cksum → 补 '0' 对齐 32 hex。 */
    public static String assembleDistributeContent(String sign, String content) {
        String core = "FF" + sign + String.format("%04x", content.length() / 2) + content;
        core += computeValidateCode(PROTOCOL_HEADER + core);
        int pad = (32 - core.length() % 32) % 32;
        StringBuilder sb = new StringBuilder(core);
        for (int i = 0; i < pad; i++) sb.append('0');
        return sb.toString();
    }

    // ==================== 组帧 / 解帧 ====================

    /**
     * 密文帧组装。对应 BluetoothHelper.build(sign, content, randomNumberProtocol, aesKey)。
     * @param randomNumberProtocol 完整的 0D 响应帧 hex（或裸 32 hex 随机数）
     * @param aesKey activationKey（开锁/电量）或 DEFAULT_AES_KEY（激活阶段）
     */
    public static String build(String sign, String content, String randomNumberProtocol, String aesKey) {
        if (randomNumberProtocol == null || randomNumberProtocol.isEmpty()) return null;
        try {
            String randomNum = fetchRandomNumber(randomNumberProtocol);
            if (randomNum == null) return null;
            String actualKey = xor(randomNum, aesKey);
            if (actualKey == null) return null;
            String cipher = AesUtils.encryptHexString(assembleDistributeContent(sign, content), actualKey);
            if (cipher == null) return null;
            String head = PROTOCOL_HEADER + String.format("%04x", cipher.length() / 2) + cipher;
            return (head + computeValidateCode(head)).toUpperCase();
        } catch (Exception e) {
            return null;
        }
    }

    /** BluetoothHelper.extractMessage — 从密文帧 55AA|len|cipher|cksum 中取 cipher。 */
    public static String extractMessage(String content) {
        try {
            String upper = content.toUpperCase();
            int n = Integer.parseInt(upper.substring(4, 8), 16);
            return upper.substring(8, 8 + n * 2);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 工作流帧构造器 ====================

    /** 0B 开锁 —— 密文帧，content 为空。 */
    public static String buildUnlockFrame(String randomNumberProtocol, String activationKey) {
        return build(CMD_UNLOCK, "", randomNumberProtocol, activationKey);
    }

    /** 01 电量 —— 密文帧，content 为空。 */
    public static String buildBatteryFrame(String randomNumberProtocol, String activationKey) {
        return build(CMD_BATTERY_LEVEL, "", randomNumberProtocol, activationKey);
    }

    // ==================== 响应解密 / 判定 ====================

    /** 解密密文帧的 cipher，返回明文 hex（小写），失败返回 null。 */
    public static String decryptResponse(String responseHex, String randomNumberProtocol, String activationKey) {
        if (responseHex == null || randomNumberProtocol == null || activationKey == null) return null;
        try {
            String randomNum = fetchRandomNumber(randomNumberProtocol);
            if (randomNum == null) return null;
            String actualKey = xor(randomNum, activationKey);
            if (actualKey == null) return null;
            String cipher = extractMessage(responseHex);
            if (cipher.isEmpty()) return null;
            return AesUtils.decryptHexString(cipher, actualKey);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从明文 hex 中取状态码（[8:10]，对应蓝牙协议 sign|len|status 的 status）。 */
    public static String getStatus(String plainHex) {
        if (plainHex == null || plainHex.length() < 10) return null;
        return plainHex.substring(8, 10).toLowerCase();
    }

    /**
     * 解析电量：解密后的明文 hex → 百分比（0-100）。
     * 真机实测（参考 fktm-BleLockClient.battery）：
     *   plain = FF | sign | len(2B) | status | content...
     *   status = plain[8:10]；电量 = content 第 2 字节 = plain[10:12]（如 0x64=100%）。
     *   content 实测 = "00 64 ff 00" → 0x64 = 100%。
     */
    public static Integer parseBattery(String plainHex) {
        if (plainHex == null || plainHex.length() < 12) return null;
        try {
            return Integer.parseInt(plainHex.substring(10, 12), 16);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解密 ack 并判定是否为 status=00 的成功响应。 */
    public static boolean isAckOk(String ackHex, String randomNumberProtocol, String activationKey) {
        String plain = decryptResponse(ackHex, randomNumberProtocol, activationKey);
        return plain != null && plain.startsWith("ff") && CODE_OK.equals(getStatus(plain));
    }

    /** VocLockHelper.isLinkEstablished（开锁流程里 key 就是 activationKey）。 */
    public static boolean isLinkEstablished(String establishAckHex, String randomNumberProtocol, String activationKey) {
        return isAckOk(establishAckHex, randomNumberProtocol, activationKey);
    }

    /**
     * 开锁成功判定：
     *   1) 裸 ack 命中（明文 55AAFF0B0001000A）
     *   2) 密文 ack 解密后 status = 00
     */
    public static boolean isUnlockSuccess(String responseHex, String randomNumberProtocol, String activationKey) {
        if (responseHex == null) return false;
        if (responseHex.toUpperCase().startsWith(UNLOCK_SUCCESS_ACK)) return true;
        return isAckOk(responseHex, randomNumberProtocol, activationKey);
    }

    /** 解密失败时给出可读错误（含错误码文案）。 */
    public static String describeAck(String ackHex, String randomNumberProtocol, String activationKey) {
        String plain = decryptResponse(ackHex, randomNumberProtocol, activationKey);
        if (plain == null) return "解密失败（activationKey 可能不匹配）";
        if (!plain.startsWith("ff")) return "密钥丢失，请联系厂家或经销商";
        String st = getStatus(plain);
        if (st == null) return "响应过短";
        if (CODE_OK.equals(st)) return "成功";
        String msg = CODE_MAP.get(st);
        return msg != null ? msg : "未知状态码 " + st;
    }
}
