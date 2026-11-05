# Third-party notices

本仓库（Open the door + sync-module）以 **MIT License** 发布（见 `LICENSE`）。
其中**协议实现与同步模块**参考/衍生自第三方项目，依据其 MIT 许可须保留原作者版权声明：

---

## lmc2007/TMallKey

<https://github.com/lmc2007/TMallKey>

`app/java/.../VocProtocol.java` / `VocCommandType.java`（协议帧与密钥派生）为 TMallKey
`VocProtocol.kt` 的**逐字节复刻**；`sync-module` 的 LSPatch 模块参考 TMallKey 的
lspatch-module 改造。两者均遵循其 **MIT License**：

```
MIT License

Copyright (c) 2026 Muleng

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
