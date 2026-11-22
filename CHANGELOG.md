# Changelog

## 1.2.0 (2026-11-22) — 性能与安全优化

**聚焦「亚秒级开锁」热路径与本地凭证 I/O 的削峰**，并提升构建脚本可移植性。

### 性能

- **HEX 查找表取代 `String.format` 循环**：`AesUtils.byteArrayToHex`、`VocProtocol.xor/computeValidateCode/assembleDistributeContent/build`、`UpdateManager.hex` 全部改为
  `char[]` 查找表 + 半字节位运算，消除开锁/电量链路（BLE NOTIFY 聚合、密钥派生、帧构建/校验）上逐字节的
  `String.format` 分配，减少 GC 抖动、加快响应。
- **`CredentialStore` 缓存**：`SecretKey` 懒加载缓存（避免每次加解密都重载 Keystore 的 JNI/I/O），
  `loadAll` 结果缓存（save/remove 时失效），显著降低主线程 `refreshDashboard` 的 Keystore/JSON 开销。
- **`refreshDashboard` 去重**：复用一次 `history.loadAll()` 结果计算守护次数，去掉重复全量 JSON 解析。
- **`log()` 用 `append`**：追加式写入日志，免每次全量 `getText+setText` 触发布局重排；仅超长时裁剪。
- **`DoorGlyphView` 复用 Path**：开门动画期间不再每帧 `new Path()`，消除内存抖动。
- **正则预编译**：MAC 校验 `Pattern` 静态预编译（原来每次点击开锁/查电量都重新编译）。
- **`CredentialBackup` 复用 `SecureRandom`**：导出备份只初始化一次随机源。

### 安全

- **日志脱敏 `activationKey`**：等价门锁物理钥匙，运行日志仅显示头 4 位 + `****` + 尾 4 位。

### 构建

- **`build.ps1` 自动探测 build-tools**：优先 `android-14` 约定，否则取最高数字版本；R8/D8 jar 增加
  `d8.jar` 回退（不再硬编码 `r8-8.2.47.jar`），提升跨环境可移植性。

## 1.1.0 (2026-11-05) — 首个公开发布

**Open the door** 正式公开发布。独立开锁 App + 天猫校园修改版同步模块，全程离线，
纯 Java、无 Gradle、无第三方依赖。

### 差异化特色

- **看板「我的门」**：桌面级仪表盘，圆角「开门」大按钮 + 仪式化动画（成功时震动手感 + 金色光晕 + 呼吸字）+ 状态指示（● 已预连接 / ? 预连接中 / ○ 未连接）+ 守护次数。
- **A · 电量/状态**：电量 % + 最近一次查询时间 / 信号 RSSI / 协议自检 / 凭证状态；电量 < 20% 红色提示。
- **B · 设备端协议自检**：`ProtocolSelfTest` 24 个协议向量 + 环境检查，真机上直接自检（JVM 参考回归 24/24）。
- **H · 开锁历史**：最近 6 条（时间 + 结果 + MAC），一键清空。
- **G · 凭证加密备份**：用户口令 PBKDF2（100k 次）+ AES/GCM 导出/导入，跨设备迁移密钥。
- **④ · 隐私中心**：明示本地数据（密钥 / 历史 / 崩溃日志 / 版本）+ 一键清除全部 / 清空历史。
- **① · 仪式化「我的门」**：点门名可改名，开门成功有动画与提示「已开门，欢迎回家」。
- **应用内自更新**：页脚「检查更新」+ `onStart` 每 24h 自动检查；`GET /releases/latest` → 版本比对 → 下载 → **SHA-256 校验** → **同签名覆盖安装**（`ApkProvider` content:// + `ACTION_VIEW`，Android 8+ 处理未知来源授权）。

### 修复（真实日志定位）

- **看板启动崩溃**：`dashCell` 返回已加父级的 TextView，`buildDashboard` 再次 `addView` → `IllegalStateException: child already has a parent`。改为返回 cell、值经出参带出。
- **一键体检闪退**：后台线程打印 24 向量触发 `log()` 非主线程触碰视图 → `CalledFromWrongThreadException`。`log()` 改为线程安全（自动 `runOnUiThread`）。
- **手动凭证不保存**：`startUnlock` / `onBatteryClick` 从不 `store.save(mac, key)`，重启后 `prefillFromStore` 回填不上。现开锁/查电量前持久化（Keystore AES-256-GCM 落盘）。
- **电量查询卡住影响开门**：单飞行状态机，电量无响应时 `protocolStarted` 卡住、开锁 `startProtocol` 直接返回。修复：**开锁优先级最高**（抢占在途电量→干净重连；电量绝不打断开锁）、**复用连接时重置协议会话**、**8s 命令总超时**自动释放连接。
- **崩溃日志优化**：`CrashLogger` 头部附设备型号 / Android 版本 / 应用版本；128KB 上限轮转（crash.log → crash.log.1）；外部 App 目录优先、内部回退；用 `getApplicationContext()` 防泄漏。

### 兼容加固

- **minSdk 21 → 23**（对齐 README「Android 6.0+」）：避免 API 21-22 `checkSelfPermission` `NoSuchMethodError`。
- **移除 `java.util.function.Consumer`**（API 24+）→ 自定义 `PassCallback`；全部 GATT 调用包 `try-catch`；`isFinishing/isDestroyed` 守卫防 `BadTokenException`；权限拒绝兜底。
- **版本**：v1.0.1 → **v1.1.0**（versionCode 4 → 5）；README/LICENSE 保留署名与致谢（MIT 合规）。
