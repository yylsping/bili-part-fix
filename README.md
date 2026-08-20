# BiliPartFix

面向哔哩哔哩 Android `7.4.0`（versionCode `7040300`）的 LSPosed 兼容性修复模块，基于 libxposed Modern API 102。

## 修复内容

- 恢复部分新式动态详情页的评论请求。
- 在旧客户端中稳定展示新式图文评论（含首次加载、展开与列表重绑），并使用客户端原生图片查看器浏览、缩放和切换完整图片列表。
- 恢复 EVA3/Opus 专栏的正文、图片和分隔内容。
- 恢复“小站图文”动态详情页的标题、正文和图片。
- 修复部分 UGC 合集被错误路由后无法正常进行分 P 播放的问题。

模块仅在目标版本匹配时安装业务 Hook；其他哔哩哔哩版本会直接跳过。

## 网络与数据说明

普通内容不会产生额外请求。只有检测到旧客户端无法解析的图文评论或小站图文占位内容时，模块才会请求哔哩哔哩公开详情接口，并复用目标应用当前登录会话加载内容。Cookie 不写入模块日志或持久化存储，图片继续使用哔哩哔哩自带的加载与缓存组件。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 哔哩哔哩 Android 7.4.0（7040300） |
| Android | 8.1（API 27）及以上 |
| 框架 | 支持 libxposed Modern API 102 的 LSPosed |
| 模块版本 | 1.5.0（versionCode 8） |

## 安装

1. 从 GitHub Releases 下载并安装 APK。
2. 在 LSPosed 中启用模块，作用域只选择“哔哩哔哩”。
3. 强制停止哔哩哔哩后重新打开。

需要回滚时，在 LSPosed 中停用模块并重启目标应用，或直接卸载模块。模块不会创建需要迁移或清理的长期业务数据。

## 构建

需要 JDK 17、Android SDK 36，并可从 Maven Central 获取 `io.github.libxposed:api:102.0.0`。

```powershell
.\gradlew.bat clean assembleDebug
```

测试 APK 输出到 `app/build/outputs/apk/debug/`。面向普通用户的已签名版本请从 GitHub Releases 下载。

## 相关项目

- [bili hook](https://github.com/yylsping/bili-hook)：同样面向哔哩哔哩 7.4.0，提供画质解锁与去广告功能。

`BiliPartFix` 负责旧客户端兼容性修复，`bili hook` 负责画质解锁与去广告；两者没有构建依赖，可以按需要分别安装。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与哔哩哔哩及 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
