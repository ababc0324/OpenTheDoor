# Contributing

感谢关注 Open the door！欢迎提交 issue、PR 与改进建议。

## 开发环境

- **JDK 21**
- **Android SDK**：`build-tools;android-14` + `platforms;android-34`
- 命令行构建（无 Gradle）：`powershell -File app\build.ps1`

## 流程

1. Fork 本仓库，创建特性分支：`git checkout -b feat/your-change`。
2. 修改代码（纯 Java，保持无第三方依赖、minSdk 21 兼容）。
3. 构建 + 跑协议回归：

   ```powershell
   powershell -File app\build.ps1
   javac -cp android-34.jar -d test\ptout test\ProtocolTest.java java\com\example\doorunlock\{VocProtocol,VocCommandType,AesUtils}.java
   java -cp test\ptout ProtocolTest   # 须 24/24 通过
   ```

4. 提交（遵循 [Conventional Commits](https://www.conventionalcommits.org/)）：
   `feat: ...` / `fix: ...` / `docs: ...`。
5. 推送并创建 Pull Request。

## 注意

- **协议层**：任何帧/密钥派生改动须保持与 TMallKey 黄金向量一致，并更新 `test/ProtocolTest.java`。
- **不引入**：第三方库、资源图标库、网络请求（开锁全程离线）。
- **凭证**：不提交任何真实 `activationKey` / `macAddress` 到仓库。
- **免责**：仅用于本人有权开启的门锁；请勿用于任何未经授权场景。
