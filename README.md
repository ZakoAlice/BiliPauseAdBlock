# B站暂停广告屏蔽（BiliPauseAdBlock）

一个 LSPosed 模块：让哔哩哔哩在**暂停视频时不再弹出广告**，连左下角那条
「3秒后将展示广告 / 取消」的倒计时一起消失。

- 适配样本：`tv.danmaku.bili` **9.17.0**（versionCode 9170300，`base.apk`）
- 作用域：`tv.danmaku.bili`（竖屏非全屏、横屏全屏走的是同一套开关，一起生效）
- 模块包名：`io.github.zakoalice.bilipauseadblock`，版本 1.0
- 无界面、无联网、无数据收集；不装 LSPosed / 无 root 用不了

## 原理（不是硬藏 UI，是从总开关关掉）

逆向 `base.apk` 的 35 个 dex 后确认，暂停广告由两代播放器架构各自读**同一个云控开关**
`dd_ad_pausedpage_enable`（默认 `true`）：

| 层 | 类 / 方法 | 作用 |
| --- | --- | --- |
| 旧架构 `ship.theseus` | `com.bilibili.adcommon.config.DDConfig.getPausePageEnable():Z` | 暂停页总开关（Lazy 缓存的 Boolean） |
| 旧架构 | `DDConfig.boolean(String,Z):Z` | 通用布尔配置读取，被传 `dd_ad_pausedpage_enable` |
| 新架构 `ship.theseus2` / KMP | `kntr.app.ad.biz.videodetail.pausedpage.h.getEnabled():Z` | KMP 暂停广告配置类，内部调 `KConfig.ff("dd_ad_pausedpage_enable", true)` |
| 新架构 | `kntr.base.config.KConfig.ff(String,Z):Z` | KMP 通用布尔配置读取（委托 `IPlatformConfig.ff`） |
| UI 兜底 | `com.bilibili.ad.adview.videodetail.pausedpage.VDPausedPage.getCountDownView(...)` | 返回「N秒后将展示广告」的 `ComposeView`，隐藏它 |

模块把前四层的返回值改成 `false`，所以 `PausedPageService` / `PausedPageServiceImpl` /
`PausedPageAdFlowImpl` 整条流程根本不会启动：不请求广告、不倒计时、不弹面板。
最后一层只在**前四层都失效**时才起作用，且不替换返回对象（保留原 `ComposeView` 类型、
只设 `GONE`），不会让宿主 `ClassCastException`。

所有 hook 都是「按类名 + 方法名查找，找不到就跳过、单独 try/catch」，任一层不匹配
或换版本失效都不会让 B 站崩溃。

顺带确认的证据链（便于以后适配新版本）：

- `assets/pause-ad/config.json`：暂停广告内置小程序包「高能建站暂停广告」v1.0.684
- `com.bilibili.gripper.api.ad.biz.videodetail.pausedpage.IVDPausedPage`：
  `requestPausedPage(...)` / `getCountDownView(...)`，实现类 `VDPausedPage`
- `kntr.app.ad.biz.videodetail.pausedpage.PausedPageAdFlowImpl`：
  `request(...)` / `countdownShown()` / `countdownFinished()`
- 文案「后将展示广告」「取消暂停广告」「关闭暂停广告」来自 KMP Compose 资源

## 安装

1. 安装 `dist/BiliPauseAdBlock-v1.0.apk`
2. LSPosed（或 NPatch / 其它兼容框架）里**启用模块**，作用域勾选 **哔哩哔哩 `tv.danmaku.bili`**
3. **强制停止**哔哩哔哩，再重新打开（配置在进程启动时读取，不重启不生效）
4. 随便打开一个视频，暂停 → 应该既没有倒计时条，3 秒后也没有广告

想临时关闭：在 LSPosed 里停用本模块，再强停 B 站即可，不需要卸载。

## 验证是否生效

```bash
adb logcat -s BiliPauseAdBlock:V
```

强停并重开 B 站后应能看到：

```
BiliPauseAdBlock: attach process=tv.danmaku.bili first=true
BiliPauseAdBlock: hooked com.bilibili.adcommon.config.DDConfig#boolean
BiliPauseAdBlock: hooked com.bilibili.adcommon.config.DDConfig#getPausePageEnable()
BiliPauseAdBlock: hooked kntr.base.config.KConfig#ff
BiliPauseAdBlock: hooked kntr.app.ad.biz.videodetail.pausedpage.h#getEnabled()
BiliPauseAdBlock: hooked ...VDPausedPage#getCountDownView
BiliPauseAdBlock: hooks installed
```

暂停视频时若看到 `OFF ... key=dd_ad_pausedpage_enable` 或 `OFF ... getEnabled()`，
说明开关确实被拦下了。日志同时会写进 LSPosed 的模块日志。

如果全是 `skip (class absent)`：说明装的 B 站版本与 9.17.0 差异太大，
类名变了，需要按下节重新适配。

## 已知边界

- **只针对暂停广告**。开屏、信息流、视频贴片、评论区广告不受影响（也不该受影响）。
- 类名 `kntr.app.ad.biz.videodetail.pausedpage.h` 是混淆出来的单字母，B 站换版本可能变；
  但 `KConfig.ff` / `DDConfig` 这两层是未混淆的基础配置类，通常能继续兜住。
- 若云控在服务端把开关硬编码为下发值、绕过本地配置读取，前四层会失效，只剩 UI 兜底，
  此时倒计时条会被隐藏但广告面板可能仍弹出 —— 遇到这种情况需要再加一层
  `PausedPageAdFlowImpl.request(...)` 的拦截，届时要小心 suspend 返回值。
- 未做框架隐藏。若 B 站检测到 Xposed，请自行配置 LSPosed 的隐藏选项。

## 重新适配新版本

这次定位开关用了两个自写的轻量脚本（仅本地分析用，未随仓库发布）：

```bash
python dexinfo.py    <apk> <类名片段> [输出]   # 列出类的方法/字段签名
python methodgrep.py <apk> <方法名片段> [输出] # 全 dex 搜方法声明与引用
```

再配合 Android SDK 自带的 `dexdump -d <dex>` 看 smali，重点找
`dd_ad_pausedpage_enable` 这个字符串的 `const-string` 引用点，
顺着就能定位新版本的总开关 getter，然后改 `MainHook.java` 顶部的常量。

## 构建

需要 JDK 17+、Android SDK（build-tools 36.0.0 + platforms/android-36）。

```bash
cd module
bash build.sh
```

构建脚本会自动完成编译、打包与签名。

产物：`dist/BiliPauseAdBlock-v1.0.apk`

## 免责

仅供个人学习与本机使用。模块不修改、不分发 B 站客户端本身，也不绕过任何付费内容。
使用前请自行评估相关服务条款。
