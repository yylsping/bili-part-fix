# BiliPartFix

面向哔哩哔哩 Android `7.4.0`（versionCode `7040300`）与 `7.42.0`（versionCode `7420400`）的 LSPosed 兼容性修复模块，基于 libxposed Modern API 102。仅支持这两个精确版本，其他版本不安装任何 Hook。

## 修复内容

### 7.4.0（7040300）

- 恢复部分新式动态详情页的评论请求。
- 在旧客户端中稳定展示新式图文评论（含首次加载、展开与列表重绑），并使用客户端原生图片查看器浏览、缩放和切换完整图片列表。
- 恢复 EVA3/Opus 专栏的正文、图片和分隔内容。
- 恢复“小站图文”动态详情页的标题、正文和图片。
- 修复部分 UGC 合集被错误路由后无法正常进行分 P 播放的问题。
- 恢复“稍后再看”的列表加载与视频跳转。
- 修复部分普通及充电 UGC 视频被旧播放接口要求升级的问题，保留服务端返回的试看范围和充电条件。
- 复用原生充电入口，修复从试看提示进入充电页面时顶部位置异常、返回区域难以点击的问题。
- 在原生设置顶部提供 `bili-part-fix` 入口，进入后可通过“解码模式选择”切换 Smart Auto、V3 硬解优先或软件解码优先。Smart Auto V2 默认信任宿主：只有现代硬件能力与已证明过时的旧 selector 原因同时成立时才允许纠正 host=false；信息不完整、HDR/DRM、特殊链与直播均沿用宿主，并保留 IJK 原生失败回退。

7040300 的直播已确认使用独立 IJK selector，并稳定选择 `c2.qti.hevc.decoder.low_latency`，因此当前没有加入 Live Smart Auto。

### 7.42.0（7420400）

- 修复 UGC 合集视频被路由到合集数据源后分 P 被吞（只剩 1 P、切 P 卡在缓冲）的问题。
- 恢复"稍后再看"的列表加载与视频跳转（`page.cid` int 溢出，与 7.4.0 同根因；URI 改写改为结构化 query 解析：不再把整串 URI 当正则文本匹配，能正确处理百分号编码与 fragment，并避免相似参数名误匹配）。
- 在原生设置顶部提供 `bili-part-fix` 入口，进入后可通过"解码模式选择"切换 Smart Auto、V3 硬解优先或软件解码优先。7.42.0 与 7.4.0 的解码链语义同构（仅混淆符号不同），直接复用同一套共享策略：Smart Auto 只处理普通 UGC VOD 的本地 decoder preference，HDR/DRM/特殊链与直播沿用宿主，保留 IJK 原生失败回退。

7.42.0 原生已正常、明确不修复的功能：图文评论与原生图片浏览、动态评论、EVA3/Opus 专栏（原生 ColumnDetailActivity）、小站图文动态详情、充电/普通 UGC 播放（无"需要升级"错误）。

模块仅在目标版本匹配时安装业务 Hook；其他哔哩哔哩版本会直接跳过。

## 网络与数据说明

普通情况下不会产生额外请求。7.4.0 上遇到旧客户端无法解析的图文评论或小站图文占位内容时，模块按需请求哔哩哔哩公开详情接口；用户主动播放 UGC 视频且旧播放接口明确返回兼容性升级错误时，按需补发一次兼容播放请求（仅 7.4.0；7.42.0 不涉及这些链路，稍后再看修复只在本地重写响应 JSON，不产生额外请求）。请求复用目标应用当前会话，充电内容仍受服务端返回的账号权限和试看范围限制。

模块没有独立后台服务或常驻进程，不进行后台轮询或周期请求，也不记录或持久化 Cookie、Token 等敏感会话信息。图片继续使用哔哩哔哩自带的加载与缓存组件。

解码模式保存在宿主私有目录的 `SharedPreferences: bili_part_fix.xml` 中，key 为 `decoder_mode`（7.4.0 与 7.42.0 均适用）。模块只持久化解码模式，不保存 Cookie/Token 等敏感数据。停用模块后该 preference 可能仍留在哔哩哔哩私有数据目录中，但不会产生后台行为。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 哔哩哔哩 Android 7.4.0（7040300）或 7.42.0（7420400） |
| Android | 8.1（API 27）及以上 |
| 框架 | 支持 libxposed Modern API 102 的 LSPosed |
| 模块版本 | 1.8.0（versionCode 12） |

## 安装

1. 从 GitHub Releases 下载并安装 APK。
2. 在 LSPosed 中启用模块，作用域只选择“哔哩哔哩”。
3. 强制停止哔哩哔哩后重新打开。

需要回滚时，在 LSPosed 中停用模块并重启目标应用，或直接卸载模块。停用后解码模式 preference 可以保留，但没有 Hook 或后台组件会读取并执行它。

## 构建

需要 JDK 17、Android SDK 36，并可从 Maven Central 获取 `io.github.libxposed:api:102.0.0`。

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

测试 APK 输出到 `app/build/outputs/apk/debug/`。面向普通用户的已签名版本请从 GitHub Releases 下载。

## 高帧率研究记录

同一画质 qn 存在多个 representation 时，Smart Auto 保守保留宿主选择。该修正并不代表目标视频已实现 4K120fps 播放。研究边界、DEBUG 本地对照和验证范围见[研究记录](docs/hfr-investigation.md)。

## 相关项目

- [bili hook](https://github.com/yylsping/bili-hook)：面向哔哩哔哩 7.4.0 与 7.42.0，提供画质解锁与去广告功能。

`BiliPartFix` 负责旧客户端兼容性修复，`bili hook` 负责画质解锁与去广告；两者没有构建依赖，可以按需要分别安装。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与哔哩哔哩及 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
