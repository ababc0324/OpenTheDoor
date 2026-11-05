# 门锁钥匙同步（sync-module）

> **天猫校园修改版** —— 一个 LSPatch / Xposed 模块，在**天猫校园 App 自身开锁时**把
> `macAddress + activationKey` 抓取并**同步**给本仓库的独立开锁应用
> `io.github.ababc0324.openthedoor`（「Open the door」）。
>
> 原理：天猫校园 App 在 H5 页面点「开锁」时，WindVane 桥会调用 native 的
> `VocLockHelperV2.unlock(Context, JSBParams, UnlockCallback)`，`JSBParams` 里已解析出
> **明文**的 `macAddress` 与 `activationKey`（32 位 hex，每把门锁固定等值密钥）。本模块
> hook 该调用，在**门锁 BLE 使用之前**截获明文，即可让独立 App 无需 root / 无需反编译
> 即可复用同一把门锁的密钥。
>
> 与 [lmc2007/TMallKey](https://github.com/lmc2007/TMallKey) 的 lspatch-module 同源，
> 仅同步方式不同：本模块用**广播**（App 前台时即时收到）+ **本地 Socket 127.0.0.1:42742**
> （App 后台时主动拉取，绕过 Android 11+ 包可见性限制）。

## 它能做什么

- 你在**天猫校园 App** 里正常开门，本模块静默记录该门锁的 `mac + activationKey`。
- 独立开锁应用收到后**自动回填**凭证（广播即时 / Socket 拉取兜底），无需手动复制粘贴。
- 凭证持久化到天猫校园 App 的 `files/doorunlock_sync.txt`，重启后自动恢复。

> 本模块**不改动、不重打包**天猫校园 App，也不上传任何数据；只在内存中截获开锁参数并
> 同步给本机同签名族的独立应用。

## 安装（LSPatch）

1. 构建或下载 `doorunlock-sync-module.apk`。
2. 用 [LSPatch](https://github.com/LSPosed/LSPatch)（或 LSPosed 环境）将该模块
   **注入天猫校园 App**（作用域 `com.tmall.campus.and`，已在 `res/values/arrays.xml` 声明）。
3. 安装「Open the door」独立开锁应用（本仓库主应用）。
4. 在天猫校园 App 里正常开门一次 → 独立应用自动收到凭证并回填。

## 构建

无 Gradle，纯命令行。需外部工具链（**不入库**）：

- **JDK 21+** → `$env:JAVA_HOME`
- **Android SDK** → `$env:ANDROID_SDK_ROOT`（含 `build-tools;android-14` + `platforms;android-34`）
- **Xposed API 82**（仅编译期；运行时由框架提供）→ `$env:XPOSED_API` 或放到 `lib\xposed-api-82.jar`

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:ANDROID_SDK_ROOT = "C:\path\to\android-sdk"
$env:XPOSED_API = "C:\path\to\xposed-api-82.jar"
powershell -NoProfile -ExecutionPolicy Bypass -File .\build_module.ps1
```

产物：`build\out\doorunlock-sync-module.apk`（`zipalign` + 自签名 `build\syncmodule.jks`，
口令 `doorunlock`，**自行保管、勿上传**）。

> 注意：`aapt2`（原生 C++）无法 stat 中文路径，构建脚本会把 res/assets 复制到
> `%TEMP%\opencode\unlock_build_cache` 的 ASCII 目录再编译。

## 目录结构

```
sync-module/
  AndroidManifest.xml                 # 模块声明 + 作用域 com.tmall.campus.and
  build_module.ps1                    # 命令行构建（6 步：javac→jar→d8→aapt2→dex→sign）
  assets/xposed_init                  # 入口：com.example.doorunlock.sync.HookEntry
  res/values/arrays.xml               # xposedscope
  src/com/example/doorunlock/sync/
    HookEntry.java                    # 核心：hook unlock + 持久化 + 广播 + Socket
  build/out/doorunlock-sync-module.apk  # 签名产物（Release 附件，git 不追踪）
```

## 免责声明

- 本模块**与天猫校园 / 阿里巴巴官方无任何隶属、背书或合作关系**；天猫校园为**其各自权利人**的商标，本模块仅用于互操作说明。
- 本模块**仅供个人学习、研究与本地自用**；**禁止**用于商业用途或任何未经授权的场景。
- 本模块通过 LSPatch/Xposed **在运行时注入**目标 App 行为；由此产生的一切后果（含目标 App 失效、账号风险等）**由使用者自行承担**。
- **无任何明示或默示担保**；使用即视为同意上述条款。
- 参考/衍生自 [lmc2007/TMallKey](https://github.com/lmc2007/TMallKey)（MIT），保留其版权声明。
- 第三方版权声明见 [../NOTICE.md](../NOTICE.md)（TMallKey，© 2026 Muleng，MIT）。

## License

[MIT](../LICENSE)
