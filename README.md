# 没心机 · 羽溪修改版

桌面风格的 **角色扮演 / 聊天 PWA**：主屏应用、密谈 IM、晤面、论坛、关系网、世界书、生图与语音等。  
**对话与档案数据保存在浏览器本机**（IndexedDB），需自行配置大模型 API Key（仅存本设备）。

本仓库为 **开源版**：可直接使用主功能；**不含云账号登录**（QQ / Discord / 激活码等仅正式版提供）。

**作者：Ruiya**

---

## 许可与二改

本项目采用 **[GNU AGPL-3.0](LICENSE)**。

- 你可以使用、修改、再发布本软件。
- **任何修改版、衍生版、基于本代码提供在线服务的版本，都必须以相同许可证开源**，并向用户提供对应源代码（AGPL 第 13 条等对网络服务有要求）。
- 若不同意上述义务，请勿分发或公开部署修改后的版本。

---

## 快速开始

### 1. 获取代码

在 GitHub 仓库页点击 **Code**，复制 HTTPS 地址后：

```bash
git clone <仓库 HTTPS 地址>
cd meixinji
```

### 2. 生成开源版入口脚本（首次必做）

仓库内不含正式版登录文件。在项目根目录执行：

```powershell
.\scripts\setup-public.ps1
```

会生成 `auth.js`、`oauth-bootstrap.js`（本地模式，无云登录）。

### 3. 运行

- 用任意 **静态服务器** 打开本目录（不要只靠 `file://` 打开，以免 PWA / 模块异常）。
- 浏览器访问 `index.html`。
- 在设置中填写你的 **OpenAI 兼容 API**（或其它已支持的接口）后即可使用。

**部署示例**：GitHub Pages、Cloudflare Pages、Vercel 静态托管等，发布目录即本项目根目录（无 `dist` 构建步骤）。  
若对外提供可访问的部署实例，请遵守 AGPL 提供源代码获取方式。

---

## 开源版与正式版

| | 开源版（本仓库） | 正式版 |
|--|------------------|--------|
| 密谈 / 角色 / 论坛等 | ✅ | ✅ |
| 云账号登录 | ❌ | ✅ |
| ST 验证卡导入解锁 | ❌ | ✅（需登录） |

---

## 开发与安全

- 发布前可运行：`node scripts/check-publish.js --staged`
- **切勿**将含 Supabase 配置、真实 `auth.js`、`.env` 等提交到公开仓库。


## 本修改版

基于 https://github.com/huaizhi123/meixinji ，原作者 Ruiya，沿用 AGPL-3.0。

- 设置 → 外观：日间、夜间、跟随系统；首次打开默认跟随系统，选择会保存在本机。系统切换或应用回到前台时同步刷新。
- 左右滑动 72 像素可返回上一层，调用原有返回按钮，保留原页面的表单检查。输入框、按钮、横向滚动区域及选中文字时不触发。
- 安装为 PWA 后，对浏览器提供的系统返回事件逐次处理，桌面层停留在应用内。网页不能覆盖手机操作系统保留的边缘手势，请从屏幕内侧起滑；vivo S17 的系统手势仍需真机验证。
- 已包含仓库提供的免登录入口，无需运行 PowerShell 初始化；可从根目录直接静态托管。
- 修改版源代码：https://github.com/yuxi35177-ux/meixinji

检查：`node --test tests/mobile.test.cjs`。不需要构建步骤。


## 本地 APK 增强版

Android 工程见 [android-app/README.md](android-app/README.md)。保持本地版应用名、包名和图标；感知入口位于「我的」中表情包正下方，包含各项权限和底部逐项测试。旧 HTML 聊天自动显示卡片，原消息保留；默认启用新密谈自动 HTML 插卡。字号、日夜间与滑动返回沿用原设置。

回归检查：`node --test tests/mobile.test.cjs`；安装 Playwright 后可运行 `node tests/html-reality.browser.cjs` 验证 HTML、感知入口、设置及隐私开关。浏览器二进制可由 `PLAYWRIGHT_CHROMIUM_EXECUTABLE` 指定。
