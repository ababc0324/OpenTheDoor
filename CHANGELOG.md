# Changelog

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
