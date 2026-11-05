package io.github.ababc0324.openthedoor;

/**
 * VOC 门锁 BLE 命令常量（逆向自 com.tmall.campus.doorlock.bluetooth.VocCommandType）
 *
 * 说明：原实现为 enum，但手动构建所用的 R8 8.2.2-dev 对 enum 存在 bug，
 * 故改为 final class + 静态常量（等价语义，避免 d8 崩溃）。
 */
public final class VocCommandType {

    public static final VocCommandType REQUEST_RANDOM_NUMBER = new VocCommandType("0D", "55AAFF0D00000B");
    public static final VocCommandType ENCRYPTED_LINK_ESTABLISH = new VocCommandType("13", "55AAFF13000011");
    public static final VocCommandType BATTERY_LEVEL = new VocCommandType("01", "55AAFF010000FF");
    public static final VocCommandType UNLOCK = new VocCommandType("0B", "");
    public static final VocCommandType GENERATE_KEY = new VocCommandType("0E", "");
    public static final VocCommandType ACTIVATE = new VocCommandType("06", "");
    public static final VocCommandType RESET = new VocCommandType("0C", "55AAFF0C00000A");
    public static final VocCommandType ADD_SIM_CARD = new VocCommandType("08", "");
    public static final VocCommandType DELETE_SIM_CARD = new VocCommandType("09", "");

    private final String sigh;       // 命令 code
    private final String commandHex; // 固定帧前缀（无 payload 的命令直接发送）

    private VocCommandType(String sigh, String commandHex) {
        this.sigh = sigh;
        this.commandHex = commandHex;
    }

    public String getSigh() {
        return sigh;
    }

    public String getCommandHex() {
        return commandHex;
    }
}
