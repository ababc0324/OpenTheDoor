package io.github.ababc0324.openthedoor;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * VOC 门锁 BLE 客户端（协议逆向自 com.tmall.campus.doorlock.VocLockHelperV2 + BluetoothHelper，
 * 逐字节对齐 lmc2007/TMallKey 的 VocProtocol）。
 *
 * 工作流：扫描/直连 → GATT 发现 → 开启通知 → 协商大 MTU → REQUEST_RANDOM_NUMBER(0D)
 *   → 提取随机数 → ENCRYPTED_LINK_ESTABLISH(13) → UNLOCK(0B)
 *   → 成功判定：裸 ack 55AAFF0B0001000A 或密文 ack 解密 status=00
 *
 * 分包：20 字节/包写入；响应按 NOTIFY 聚合。
 *
 * 预连接（参考 TMallKey BleLockClient）：
 *   - 进入前台（setForeground(true)）自动预连接，30s 内无开锁则自动断开；
 *   - 一旦开锁则保活 60s，期间再开锁重新计时；
 *   - 退到后台（含息屏）立即让出（busy 时延后到任务结束）。
 *   连接复用：同地址已就绪则跳过扫描/连接/发现，直接进入协议。
 */
public class DoorLockClient {

    public interface Listener {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String message);
    }

    /** 连接状态变化（供状态角标/状态卡刷新）。 */
    public interface ConnectionListener {
        void onConnectionChanged(boolean ready, boolean connecting, String address);
    }

    private static final UUID SERVICE_UUID =
            UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e1001");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int PACKET_SIZE = 20;

    /** 进入前台先预连接，若 idleMs 内无开锁则自动断开（让出并发连接）。 */
    private static final int COLD_START_HOLD_MS = 30_000;
    /** 开锁后保活时长；期间再开锁重新计时。 */
    private static final int DEFAULT_IDLE_CLOSE_MS = 60_000;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;

    private Listener listener;
    private ConnectionListener connListener;
    private String activationKeyHex;
    private String randomNumberHex;
    // 当前工作流阶段（决定如何解释响应）
    private int state = 0; // 0=待随机数, 1=待加密链路确认, 2=待UNLOCK响应

    // 写入队列
    private final List<byte[]> writeQueue = new ArrayList<>();
    private boolean writing = false;
    private Runnable writeTimeout;       // 写回调兜底超时（no-response 写可能不回调）
    private volatile boolean writeAcked = false; // 当前包是否已被推进（防迟到回调双发）
    private int mtu = 23;                // 协商后的 ATT MTU（默认 23 -> 载荷 20）
    private Runnable mtuFallback;        // MTU 协商兜底超时（锁不支持 MTU 时按默认继续）
    private boolean protocolStarted = false; // 0D 已发送（防 onMtuChanged + 兜底双触发）
    private byte[] currentPacket;        // 当前写入包（status 133 重试用）
    private int writeAttempts;           // 当前包写入尝试次数

    // 响应聚合
    private final StringBuilder messageBuffer = new StringBuilder();

    private volatile boolean cancelled = false;

    // ==================== 预连接状态 ====================

    private volatile boolean connected = false;
    private volatile boolean connecting = false;
    private volatile String currentAddress = null;
    private volatile String connectTarget = null;
    private volatile boolean foreground = false;

    private final Object flightLock = new Object();
    private int inFlight = 0;            // 进行中的开锁任务数（>0 时不因后台释放连接）

    private final Object connectLock = new Object();
    private Runnable idleCloseRunnable = null;

    public DoorLockClient(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) this.adapter = bm.getAdapter();
    }

    // ==================== 预连接公共 API ====================

    /** 连接已完全就绪（GATT 连接 + 写/通知特征 + 通知已开）。 */
    public boolean isReady() {
        return connected && gatt != null && writeChar != null && notifyChar != null;
    }

    /** 是否正在建立连接（供 UI 显示「预连接中」）。 */
    public boolean isConnecting() { return connecting; }

    /** 是否正在执行开锁任务（busy 时后台不释放连接）。 */
    public boolean isBusy() { synchronized (flightLock) { return inFlight > 0; } }

    /** 当前已就绪连接的目标地址（null = 未连接）。 */
    public String getReadyAddress() { return isReady() ? currentAddress : null; }

    public void setConnectionListener(ConnectionListener l) { this.connListener = l; }

    /**
     * 前台预连接（最佳努力）：对已知 MAC 建立 GATT 连接并开启通知，但**不**进入协议。
     * 成功后进入冷启动待命窗口（COLD_START_HOLD_MS），期间用户点开锁可直接复用。
     */
    public void preconnect(final String mac) {
        if (cancelled || mac == null || adapter == null || !adapter.isEnabled()) return;
        mainHandler.post(() -> {
            synchronized (connectLock) {
                if (cancelled || isReady() || connecting) return;
                connectByMac(mac);
            }
        });
    }

    /**
     * 前台/后台生命周期（MainActivity onStart / onStop）。
     * 前台：保持当前连接并重新计时冷启动窗口；若未连接则等待 preconnect 完成后的调度。
     * 后台：无任务时立即释放连接；busy 时由 endTask() 收尾释放。
     */
    public void setForeground(final boolean fg) {
        mainHandler.post(() -> {
            foreground = fg;
            if (fg) {
                if (isReady()) scheduleIdleClose(COLD_START_HOLD_MS);
            } else if (!isBusy()) {
                cancelIdleClose();
                closeGatt();
            }
        });
    }

    /** 开锁任务开始：加计数、取消待命断开。 */
    public void beginTask() {
        synchronized (flightLock) { inFlight++; }
        mainHandler.post(this::cancelIdleClose);
    }

    /** 开锁任务结束：减计数；前台保活 60s，后台立即释放。 */
    public void endTask() {
        synchronized (flightLock) { if (inFlight > 0) inFlight--; }
        mainHandler.post(() -> {
            if (isBusy()) return;
            cancelIdleClose();
            if (foreground) {
                if (isReady()) scheduleIdleClose(DEFAULT_IDLE_CLOSE_MS);
            } else {
                closeGatt();
            }
        });
    }

    /** 定时自动断开：到点且无任务且前台就绪时让出连接。 */
    private void scheduleIdleClose(long idleMs) {
        cancelIdleClose();
        idleCloseRunnable = () -> {
            idleCloseRunnable = null;
            if (isBusy()) return;
            if (isReady()) closeGatt();
        };
        mainHandler.postDelayed(idleCloseRunnable, idleMs);
    }

    private void cancelIdleClose() {
        if (idleCloseRunnable != null) mainHandler.removeCallbacks(idleCloseRunnable);
        idleCloseRunnable = null;
    }

    private void notifyConn() {
        mainHandler.post(() -> {
            if (connListener != null)
                connListener.onConnectionChanged(isReady(), connecting, currentAddress);
        });
    }

    // ==================== 开锁入口 ====================

    public void unlock(String macAddress, String activationKey, Listener l) {
        this.listener = l;
        this.activationKeyHex = activationKey;
        this.randomNumberHex = null;
        if (adapter == null) { error("蓝牙不可用"); return; }
        if (!adapter.isEnabled()) { error("请先打开蓝牙"); return; }
        beginTask();
        progress("连接 " + macAddress);
        synchronized (connectLock) {
            if (cancelled) { endTask(); return; }
            if (isReady() && macAddress.equalsIgnoreCase(currentAddress)) {
                // 复用已预连接：跳过扫描/连接/发现，直接进入协议
                progress("复用已预连接（跳过扫描/连接）");
                requestMtuThenStart();
                return;
            }
            if (connecting && macAddress.equalsIgnoreCase(connectTarget)) {
                // 预连接进行中：等待 onReady（isBusy 会使它进入协议），不重复 connectGatt
                progress("预连接进行中，等待就绪...");
                return;
            }
            if (isReady()) closeGatt();
            connectByMac(macAddress);
        }
    }

    public void cancel() {
        cancelled = true;
        mainHandler.post(() -> {
            cancelIdleClose();
            closeGatt();
        });
    }

    // ---------------- 连接 ----------------

    private void connectByMac(String mac) {
        connecting = true;
        connectTarget = mac;
        notifyConn();
        try {
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            connecting = false;
            connectTarget = null;
            notifyConn();
            error("连接失败: " + e.getMessage());
            endTask();
        }
    }

    // ---------------- GATT 回调 ----------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (cancelled) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                progress("已连接，发现服务...");
                // 请求高优先级连接：更短连接间隔，加快 GATT 往返、减少超时断连
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); }
                catch (Throwable ignored) { }
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasReady = isReady();
                connected = false;
                connecting = false;
                currentAddress = null;
                connectTarget = null;
                notifyConn();
                if (wasReady) progress("连接已断开 (status=" + status + ")");
                else error("连接断开 (status=" + status + ")");
                if (isBusy()) endTask();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (cancelled) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false;
                notifyConn();
                error("服务发现失败");
                endTask();
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                for (BluetoothGattService s : g.getServices()) {
                    if (s.getUuid().equals(SERVICE_UUID)) { service = s; break; }
                }
            }
            if (service == null) {
                connecting = false;
                notifyConn();
                error("未找到 VOC 服务 " + SERVICE_UUID);
                endTask();
                return;
            }
            writeChar = null;
            notifyChar = null;
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                int props = c.getProperties();
                if (writeChar == null && (props & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                    writeChar = c;
                }
                if (notifyChar == null && (props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                    notifyChar = c;
                }
            }
            if (writeChar == null) { error("未找到写特征"); endTask(); return; }
            if (notifyChar == null) { error("未找到通知特征"); endTask(); return; }
            progress("开启通知...");
            enableNotification();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (cancelled) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false;
                notifyConn();
                error("通知描述符写入失败");
                endTask();
                return;
            }
            // 通知已开启：连接就绪
            connecting = false;
            currentAddress = connectTarget;
            notifyConn();
            if (foreground && !isBusy()) scheduleIdleClose(COLD_START_HOLD_MS);
            // 先协商大 MTU（21 字节帧单包写入，消除分包导致的 133），再进入协议
            requestMtuThenStart();
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (cancelled) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                DoorLockClient.this.mtu = Math.max(23, mtu);
                progress("MTU 协商 = " + DoorLockClient.this.mtu);
            } else {
                progress("MTU 协商失败（默认 " + DoorLockClient.this.mtu + " 继续）");
            }
            if (mtuFallback != null) mainHandler.removeCallbacks(mtuFallback);
            mtuFallback = null;
            if (isBusy()) startProtocol();
            else if (foreground) scheduleIdleClose(COLD_START_HOLD_MS);
            else closeGatt();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (cancelled) return;
            onWriteComplete(status == BluetoothGatt.GATT_SUCCESS);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (cancelled) return;
            byte[] value = c.getValue();
            if (value == null) return;
            synchronized (messageBuffer) {
                messageBuffer.append(AesUtils.byteArrayToHex(value));
            }
            String buf;
            synchronized (messageBuffer) { buf = messageBuffer.toString(); }
            int expected = expectedFrameLength(buf);
            if (expected > 0 && buf.length() >= expected) {
                processResponse(buf.substring(0, expected));
            }
        }

        /**
         * 计算完整帧长度（hex 字符数）。0 表示暂不完整/无法判断。
         * 协议帧：55AA FF <cmd> <len4> <content> <cs>（握手帧），
         * 或 55AA <len4> <encrypted> <cs>（UNLOCK 加密响应）。
         */
        private int expectedFrameLength(String buf) {
            if (buf == null || !buf.startsWith("55aa") || buf.length() < 8) return 0;
            if (buf.substring(4, 6).equals("ff")) {
                if (buf.length() < 12) return 0;
                int len;
                try { len = Integer.parseInt(buf.substring(8, 12), 16); }
                catch (Exception e) { return 0; }
                return 12 + len * 2 + 2;
            } else {
                int len;
                try { len = Integer.parseInt(buf.substring(4, 8), 16); }
                catch (Exception e) { return 0; }
                return 8 + len * 2 + 2;
            }
        }
    };

    // ---------------- 通知 ----------------

    @SuppressLint("MissingPermission")
    private void enableNotification() {
        if (notifyChar == null) { error("通知特征为空"); endTask(); return; }
        boolean ok = gatt.setCharacteristicNotification(notifyChar, true);
        if (!ok) { error("开启通知失败"); endTask(); return; }
        BluetoothGattDescriptor desc = notifyChar.getDescriptor(CCCD_UUID);
        if (desc == null) {
            desc = notifyChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        }
        if (desc != null) {
            // 按特征属性选择 CCCD 值：INDICATE -> 使能指示，否则使能通知
            if ((notifyChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            } else {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            }
            gatt.writeDescriptor(desc);
        } else {
            requestMtuThenStart();
        }
    }

    // ---------------- MTU 协商 + 协议启动 ----------------

    /**
     * 请求协商大 MTU（247），成功/失败/超时后按需进入协议或调度待命断开。
     * 大 MTU 使 UNLOCK 帧 21 字节可单包写入（载荷 244），消除默认 MTU(23)
     * 下 20+1 两包分包导致的 status 133 / 断连。1200ms 兜底。
     */
    @SuppressLint("MissingPermission")
    private void requestMtuThenStart() {
        if (cancelled) return;
        if (gatt == null) { afterMtu(); return; }
        try {
            if (gatt.requestMtu(247)) {
                if (mtuFallback != null) mainHandler.removeCallbacks(mtuFallback);
                mtuFallback = () -> { if (!protocolStarted) afterMtu(); };
                mainHandler.postDelayed(mtuFallback, 1200);
                return;
            }
        } catch (Throwable ignored) { }
        afterMtu();
    }

    /** MTU 已定：有开锁任务则进协议；前台则进入冷启动待命；否则释放。 */
    private void afterMtu() {
        if (cancelled) return;
        if (isBusy()) startProtocol();
        else if (foreground) scheduleIdleClose(COLD_START_HOLD_MS);
        else closeGatt();
    }

    /** 启动协议：发送 REQUEST_RANDOM_NUMBER(0D)。防 onMtuChanged + 兜底双触发。 */
    private void startProtocol() {
        if (cancelled || protocolStarted) return;
        protocolStarted = true;
        if (mtuFallback != null) mainHandler.removeCallbacks(mtuFallback);
        mtuFallback = null;
        progress("发送 REQUEST_RANDOM_NUMBER ...");
        state = 0; // 等待随机数
        sendCommand(VocProtocol.REQUEST_RANDOM_NUMBER, "REQUEST_RANDOM_NUMBER");
    }

    // ---------------- 写入 ----------------

    @SuppressLint("MissingPermission")
    private void sendCommand(String hex, String label) {
        byte[] data = AesUtils.hexStringToByteArray(hex);
        if (data == null) { error("命令解析失败: " + label); return; }
        int chunkSize = Math.max(PACKET_SIZE, mtu - 3);
        synchronized (writeQueue) { writeQueue.clear(); }
        for (int i = 0; i < data.length; i += chunkSize) {
            byte[] chunk = new byte[Math.min(chunkSize, data.length - i)];
            System.arraycopy(data, i, chunk, 0, chunk.length);
            synchronized (writeQueue) { writeQueue.add(chunk); }
        }
        sendNextPacket();
    }

    /**
     * 按特征属性选择写入类型（与 TMallKey BleLockClient.writeType 一致）：
     * 特征支持 WRITE -> 默认（带响应）；仅支持 WRITE_NO_RESPONSE -> 无响应写。
     */
    private int writeType() {
        int props = writeChar.getProperties();
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        }
        return BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    }

    @SuppressLint("MissingPermission")
    private void sendNextPacket() {
        byte[] packet;
        synchronized (writeQueue) {
            if (writeQueue.isEmpty()) return;
            packet = writeQueue.remove(0);
        }
        writing = true;
        currentPacket = packet;
        writeAttempts = 0;
        writeAcked = false;
        doWrite();
    }

    /**
     * 写当前包。设好 writeType 后写入；成功后启动 2s 兜底超时：
     * 若 onCharacteristicWrite 不触发（no-response 写可能不回调），超时后仍推进下一包。
     */
    @SuppressLint("MissingPermission")
    private void doWrite() {
        if (cancelled) return;
        try { writeChar.setWriteType(writeType()); } catch (Throwable ignored) { }
        writeChar.setValue(currentPacket);
        if (gatt.writeCharacteristic(writeChar)) {
            if (writeTimeout != null) mainHandler.removeCallbacks(writeTimeout);
            writeTimeout = () -> { if (!writeAcked) onWriteComplete(true); };
            mainHandler.postDelayed(writeTimeout, 2000);
        } else {
            retryWrite();
        }
    }

    /** 写失败（含 status 133 / GATT_ERROR）：重试最多 3 次，120ms 间隔。 */
    private void retryWrite() {
        if (writeTimeout != null) mainHandler.removeCallbacks(writeTimeout);
        writeTimeout = null;
        if (writeAttempts < 3) {
            writeAttempts++;
            writeAcked = false;
            mainHandler.postDelayed(this::doWrite, 120);
        } else {
            error("写失败（status 133 / 门锁忙，多次重试未果）");
        }
    }

    /** 一包写完成（回调或兜底超时触发），80ms 间隔后发下一包。 */
    private void onWriteComplete(boolean success) {
        if (writeAcked) return;
        writeAcked = true;
        if (writeTimeout != null) mainHandler.removeCallbacks(writeTimeout);
        writeTimeout = null;
        if (cancelled) return;
        if (!success) { retryWrite(); return; }
        mainHandler.postDelayed(() -> {
            synchronized (writeQueue) {
                if (!writeQueue.isEmpty()) sendNextPacket();
                else writing = false;
            }
        }, 80);
    }

    // ---------------- 响应处理 ----------------

    private void processResponse(String frame) {
        if (state == 0) {
            randomNumberHex = VocProtocol.fetchRandomNumber(frame);
            if (randomNumberHex == null) {
                error("随机数解析失败: " + frame);
                return;
            }
            progress("收到随机数: " + randomNumberHex);
            progress("发送 ENCRYPTED_LINK_ESTABLISH ...");
            synchronized (messageBuffer) { messageBuffer.setLength(0); }
            state = 1;
            sendCommand(VocProtocol.ENCRYPTED_LINK_ESTABLISH, "ENCRYPTED_LINK_ESTABLISH");
            return;
        }
        if (state == 1) {
            if (!VocProtocol.isLinkEstablished(frame, randomNumberHex, activationKeyHex)) {
                error("建立加密链路失败: " + VocProtocol.describeAck(frame, randomNumberHex, activationKeyHex));
                return;
            }
            progress("加密链路已建立，发送 UNLOCK ...");
            String unlockFrame = VocProtocol.buildUnlockFrame(randomNumberHex, activationKeyHex);
            if (unlockFrame == null) { error("UNLOCK 帧构建失败"); return; }
            synchronized (messageBuffer) { messageBuffer.setLength(0); }
            state = 2;
            sendCommand(unlockFrame, "UNLOCK");
            return;
        }
        if (state == 2) {
            if (VocProtocol.isUnlockSuccess(frame, randomNumberHex, activationKeyHex)) {
                success("开锁成功！");
            } else {
                error("开锁失败: " + VocProtocol.describeAck(frame, randomNumberHex, activationKeyHex));
            }
            return;
        }
    }

    // ---------------- 工具 ----------------

    private void progress(final String msg) {
        mainHandler.post(() -> { if (listener != null) listener.onProgress(msg); });
    }

    private void success(final String msg) {
        mainHandler.post(() -> {
            if (listener != null) listener.onSuccess(msg);
            endTask();
        });
    }

    private void error(final String msg) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(msg);
            endTask();
        });
        closeGatt();
    }

    private void closeGatt() {
        cancelIdleClose();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        writeChar = null;
        notifyChar = null;
        connected = false;
        connecting = false;
        currentAddress = null;
        connectTarget = null;
        protocolStarted = false;
        notifyConn();
    }
}
