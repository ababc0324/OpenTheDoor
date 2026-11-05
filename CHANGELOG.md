# Changelog

## 1.1.0 (2026-11-05)

- **差异化 · 门锁状态看板**：电量%（可一键查询）/ 信号 RSSI / 最近开门 / 协议自检 /
  本机凭证数；电量 < 20% 红字提醒。
- **差异化 · 仪式化「我的门」**：门名（点击可改名，默认「我的门」）+「已守护 N 次」；
  圆形开锁按钮内**自绘门**（`DoorGlyphView`），开锁成功时门扇向内打开 + 绿色对勾 + 触觉脉冲。
- **差异化 · 设备端协议自检**：`ProtocolSelfTest` 直接跑 24 个黄金向量 + 环境检查
  （BLE 适配器 / 凭证），与 JVM 参考回归互为交叉验证。
- **差异化 · 开锁历史**：纯本地（无遥测），最近 6 条展示（时间 + 门名 + MAC）。
- **差异化 · 凭证加密备份**：用户口令 `PBKDF2WithHmacSHA1`（100k 次）→ AES/GCM
  加密导出/导入，换机/重装不丢钥匙。
- **差异化 · 隐私中心**：明示数据（钥匙 / 历史 / 门名）仅存本机 + 一键清除全部 / 清除历史。
- **应用内自更新**：页脚「检查更新」+ `onStart` 静默检查（>24h 一次）；GitHub Releases →
  版本比对 → 下载 + **SHA-256 校验** → **同签名覆盖安装**（`ApkProvider` content:// +
  `ACTION_VIEW`，零第三方依赖）。
- **版本**：v1.0.1 → **v1.1.0**（versionCode 4 → 5）；页脚移除
  「协议复刻自 lmc2007/TMallKey」行（README/LICENSE 保留致谢，MIT 合规）。
- **闪退加固**：minSdk 21 → **23**（对齐 README「Android 6.0+」，消除 API 21-22
  `checkSelfPermission` NoSuchMethodError）；`java.util.function.Consumer`（API 24+）
  换成自定义 `PassCallback`；更新检查/安装回调加 `isFinishing/isDestroyed` 守卫
  （防 `BadTokenException`）；全部 GATT 调用包 `try-catch`（防蓝牙权限拒绝
  `SecurityException`）；新增 `CrashLogger`（未捕获异常写 `crash.log`，无 logcat 可定位）。
- **看板启动崩溃修复**：`dashCell` 原返回「已加进 cell 的值 TextView」，`buildDashboard`
  又把它 `row.addView` → `IllegalStateException: child already has a parent`（onCreate 即崩）。
  改为返回 cell（值 TextView 经出参带出），启动不再闪退。
- **一键体检闪退修复**：`onSelfTestClick` 在后台线程打印 24 向量触发 `log()`，
  非主线程触碰视图 → `CalledFromWrongThreadException`。`log()` 改为**线程安全**
  （非主线程自动 `runOnUiThread` 切回）。
- **手动凭证持久化**：`startUnlock` / `onBatteryClick` 现会 `store.save(mac, key)`，
  手动/解密填入的 MAC + activationKey 重启后由 `prefillFromStore` 回填。
- **崩溃日志优化**：`CrashLogger` 头部附设备型号/Android 版本/应用版本；
  128KB 上限轮转（crash.log → crash.log.1）；外部 App 目录优先、内部回退；
  用 `getApplicationContext()` 防 Activity 泄漏。
- **电量查询卡住影响开门**：`DoorLockClient` 单飞行状态机——电量无响应时
  `protocolStarted` 卡住，开锁 `startProtocol` 直接返回（开门不动）。修复：
  **开锁优先级最高**（开锁抢占在途电量→干净重连；电量绝不打断开锁）；
  **复用已预连接时重置协议会话**（`resetProtocolSession`，电量成功后也能发新 0D）；
  **8s 命令总超时**（`scheduleCommandTimeout`，卡死自动释放连接）。

## 1.0.1 (2026-11-03)

- **预连接**：进入前台自动预连接，30s 无开锁自动断开；开锁后保活 60s；退后台立即让出；
  同地址已就绪则连接复用（再开 ~0.4s）。
- **UI 重做**：深色科技风；圆形渐变开锁按钮 + 自绘挂锁（无第三方资源）；
  状态角标（● 已预连接 / ◌ 预连接中 / ○ 未连接）。
- **去品牌化**：移除工作室（AurByte）商标、版权卡片与官网链接，改为通用深色主题 +
  TMallKey（MIT）协议归属 + GitHub 链接。
- **更名**：`芝麻开门` → **Open the door**（versionCode 3 → 4）。
- **构建**：改为显式枚举源文件，规避 javac 通配符在含空格路径失败；ASCII 缓存迁至工作区外。

## 1.0.0Alpha2 (2026-11-02)

- 修复 status 133（GATT_ERROR）：请求大 MTU(247) 单包写入 + 写失败重试 3 次 +
  writeType 按特征属性 + 高优先级连接 + 2s 写回调兜底 + CCCD 按 INDICATE/NOTIFY。
- 图标渲染修复：`PushTransform(MatrixTransform)` 画布变换（避开 WPF 惰性求值 bug）。
- 品牌 Aurora 深色界面（AuroraBackgroundView，后续已移除）。

## 1.0.0Alpha1 (2026-10-25)

- 首个可构建版本：逐字节对齐 TMallKey 协议的独立开锁应用；补丁版/root 同步钥匙；
  凭证 Keystore AES-256-GCM 加密存储。
