# Open the door

> 天猫校园（Tmall Campus）宿舍 VOC 蓝牙门锁的**独立离线开锁** App。
> 协议**逐字节复刻** [lmc2007/TMallKey](https://github.com/lmc2007/TMallKey)，不 hook、不注入、不读取天猫校园进程。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84.svg)
![Build](https://img.shields.io/badge/Build-command--line-blue.svg)

**English summary:** A standalone Android app that unlocks the Tmall Campus dormitory
VOC BLE door lock fully offline. The protocol is a byte-for-byte reimplementation of
[lmc2007/TMallKey](https://github.com/lmc2007/TMallKey) (MIT). Import a credential once,
then unlock even in airplane mode. Pure Java, no Gradle, no third-party dependencies.

**仓库：** <https://github.com/ababc0324/OpenTheDoor> · **包名：** `io.github.ababc0324.openthedoor`

---

## 为什么用它

协议层的活儿一次做对，之后剩下的就是「点一下」。

- **完全离线** —— 开锁不发起任何网络请求，飞行模式下可用。门锁不联网，钥匙只在本地。
- **一次点击，亚秒级** —— 进入前台**自动预连接**；30s 内再开约 **0.4 秒**（冷启动约 1.2 秒）。
- **不碰原 App** —— 不 hook、不注入、不读取天猫校园进程，只按抓到的报文复刻 BLE 协议。
- **凭证硬件级加密** —— `activationKey` 由 Android Keystore 的 AES-256-GCM 加密后落盘，
  密钥不可导出、随卸载销毁。
- **无第三方依赖** —— 纯 Java，仅用 Android SDK，界面全自绘（无资源库/图标库）。
- **协议回归兜底** —— `test/ProtocolTest.java` 用 TMallKey 黄金向量验证，**24/24 通过**；
  另内置**设备端一键体检**（`ProtocolSelfTest`，直接跑 24 向量 + 环境检查）。
- **应用内自更新** —— 页脚「检查更新」直连 GitHub Releases，SHA-256 校验 + 同签名覆盖安装。
- **凭证加密备份** —— 用户口令 AES/GCM 导出/导入，换机/重装不丢钥匙。

## 界面与特色

**「我的门 · 健康看板」** —— 深色科技风，首页一屏读懂你的门：

- **门名**（点击可改名，默认「我的门」）+ 状态角标（**● 已预连接 / ◌ 预连接中 / ○ 未连接**）
  + 「已守护 N 次」。
- **门锁状态看板**：电量%（可一键查询）/ 信号 RSSI / 最近开门 / 协议自检结果 / 本机凭证数；
  电量 < 20% 红字提醒。
- **圆形渐变开锁按钮**：中央**自绘门**，开锁成功时**门扇向内打开 + 绿色对勾 + 触觉脉冲**，
  提示「已开门 · 欢迎回来」。
- **开锁历史**（纯本地，无遥测）：最近 6 条（时间 + 门名 + MAC）。
- **凭证 / 同步 / 运行日志**区块；**隐私中心**：明示数据仅存本机 + 一键清除。
- **页脚**：版本（点击=检查更新）+ 源码链接。

> 截图见 `docs/screenshots/`（可选，提交前可补充）。

## 三步开门

唯一的门槛是「拿凭证」：让天猫校园自己开一次锁，把 `activationKey` 取出来。

### STEP 1 · 拿凭证

| 方式 | 需要 | 说明 |
| --- | --- | --- |
| 补丁版天猫校园（推荐） | 免 root | 装补丁版 → 登录 → 门锁页**点一次开锁**，钥匙自动同步进本应用 |
| root 抓取 | 已 root | 本应用 → 「root 抓取钥匙」，再去天猫校园点一次开锁 |
| 电脑 adb | USB 调试 | 抓 `adb logcat` 读 `activationKey`（详见 TMallKey 教程） |

### STEP 2 · 导入

补丁版同步 / root 抓取会自动填入门锁 MAC + 32 位 hex `activationKey`；也可以手工填写，
或粘贴「base64 密文 + `voc_lock_secret_key`」用 AES/GCM 解密。

### STEP 3 · 开锁

点圆形开锁按钮。首次会申请蓝牙权限，允许一次即可。之后就是「一点即开」。

> 凭证保存于本机 Android Keystore 加密区（API 23+）；`activationKey` 是每把锁固定的
> 钥匙，等价于物理钥匙，请妥善保管。

## 预连接行为

连接**不是常驻**的，按两条计时规则自动回收（参考 TMallKey）：

- **进入前台先自动预连接** —— 建立 GATT 连接 + 开启通知但**不**进协议；
  **30 秒内没有开锁就自动断开**（冷启动窗口）；
- **一旦开锁则保活 60 秒** —— 期间再开锁重新计时；
- **退到后台（含息屏）立即让出** —— busy 时延后到任务结束；
- **连接复用** —— 同地址已就绪则跳过扫描/连接/发现，直接进入协议。

把门锁的并发连接让给真正要用的人，下次开锁会自动重新连上。顶部状态角标可直接看到状态。

## 从源码构建

命令行构建（**无 Gradle**），Windows + PowerShell：

1. 需要 **JDK 21**、**Android SDK**（`build-tools;android-14` + `platforms;android-34`）。
2. 按需调整 `app/build.ps1` 里的 `$tools` / `$platform` / `$jdk` 路径。
3. 运行：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File app\build.ps1
   ```

   产物：`app\build\out\doorunlock.apk`（v1.1.0，已签名 v1/v2/v3）。

> 注意：`javac` 的通配符 `*.java` 在含空格路径会失败（脚本已用显式枚举规避）；
> `aapt2`（native）无法 stat 中文路径，构建脚本会自动复制资源到工作区外的 ASCII 缓存
> （`%TEMP%\opencode\unlock_build_cache`）。

**协议回归**（独立于 Android）：

```
javac -cp android-34.jar -d test\ptout test\ProtocolTest.java java\io\github\ababc0324\openthedoor\{VocProtocol,VocCommandType,AesUtils}.java
java -cp test\ptout ProtocolTest      # -> 24 passed, 0 failed
```

## 目录结构

```
app/
  java/io/github/ababc0324/openthedoor/   # 源码（纯 Java，零第三方依赖）
    MainActivity.java        # 「我的门 · 健康看板」主界面
    DoorLockClient.java      # BLE 客户端：预连接 / 开锁 / 电量 / RSSI
    VocProtocol.java         # VOC 协议帧构建/解析（逐字节对齐 TMallKey）
    VocCommandType.java      # 命令常量
    AesUtils.java            # AES/hex 工具
    CredentialStore.java     # 凭证 Keystore AES-256-GCM 存储
    ProtocolSelfTest.java    # 设备端协议自检（24 向量 + 环境检查）
    DoorHistory.java         # 开锁历史（本地 JSON，无遥测）
    CredentialBackup.java    # 凭证加密备份（PBKDF2 + AES/GCM）
    UpdateManager.java       # 应用内自更新（GitHub Releases）
    ApkProvider.java         # content:// 安装源
    DoorGlyphView.java       # 自绘「门」+ 开门动画
    AmbientBackgroundView.java
    KeySyncHelper.java / KeySyncReceiver.java   # 补丁版钥匙同步
  res/                           # 资源（自适应/legacy 图标）
  test/ProtocolTest.java         # 协议黄金向量回归
  AndroidManifest.xml
  build.ps1                      # 命令行构建
  make_icon.ps1                  # WPF 图标光栅化（可选）
  构建说明.md / 一站式使用说明.md
```

## 协议与许可

- 协议帧（明文 `55AA|FF|cmd|len|payload|cksum`、密文 `55AA|len|cipher|cksum`）与
  密钥派生（`actualAesKey = randomNumber XOR activationKey`）**逐字节对齐**
  [lmc2007/TMallKey](https://github.com/lmc2007/TMallKey) 的 `VocProtocol`。
- 本仓库以 **MIT License** 发布（见 [LICENSE](LICENSE)）。
- 参考/派生自 TMallKey 的部分同样遵循其 **MIT License**，特此致谢。

## 免责声明

本项目与天猫校园官方无关，仅供学习研究与本人宿舍门使用。
**请勿用于开启他人门锁或任何未经授权的场景**，由此产生的一切后果由使用者自行承担。

## License

[MIT](LICENSE)
