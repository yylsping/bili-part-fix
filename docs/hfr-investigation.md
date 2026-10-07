# HFR研究与保守选流修正

2026-10-07，7040300与7420400，PLQ110 / Android17。完整跨仓库调查记录位于bili-hook的investigation-report.md和debug-evidence/README.md；本文件仅记录本仓库改动及证据边界。

目标BV1P5411Q7Cj的旧版请求没有返回可播放4K或>100fps流。原生打开URL匹配1920×1080 HEVC、声明30.303fps。原问题尚未修复，不据此添加生产Surface/Window120Hz策略。

## StreamInfoExtractor

qn标识画质组，不能保证representation唯一。旧实现遍历后直接返回第一项；同qn的两个codec、帧率或URL会令Auto对错误metadata做size/rate判断。

现在只接受唯一qn候选。重复或不可读候选返回UNKNOWN；Auto保留宿主选择，不查询能力。单一候选、显式用户模式、scope、HDR/DRM和原生fallback路径保留。合法fps后缀/分数可解析；零、负值、NaN、Infinity、非法分母和内嵌fps文本返回未知。

这是独立的可靠性修正。目标当前清单未复现同qn多个可播放候选，不能把它说成目标HFR问题根因。该决策边界早于原生实际URL选择，因此不把稍后才能观测到的URL强行当作已知选择。

新增6项纯逻辑测试和2项双版本反射测试；本仓库共81项通过。两个profile均验证唯一候选保持metadata、歧义到达policy时为UNKNOWN。DEBUG/release构建通过。

## DEBUG本地控制

仅显式bili_hfr_control intent打开固定cache/hfr-control.mp4，使用宿主IjkMediaPlayer。已验证HEVC3840×2160@120、1440红蓝交替帧；硬解需mediacodec和mediacodec-hevc，实际c2.qti.hevc.decoder.low_latency。没有修改生产UGC请求或资源。

旧版硬解可在持续触摸时呈现约118–121fps；无触摸仍可能约60fps，即便Scene物理模式120。该显示调度结论属于独立控制，不能替代原视频的服务端边界。

742双模块另验证手机后台短触摸循环：单Surface约116–121fps；停脚本后的独立控制约60.358fps。目标视频在system render120时，原生输入仍为1080P、声明30.303fps，decode/output估算约29–32。模拟触摸解除显示调度限制，未解决目标流供给。脚本已停止，未编入模块。

Surface120实验已按用户要求取消，Window120实验也已移除。完成、pause/destroy、dismiss、Surface销毁或20s超时均释放。704 HOME退出观察到CONTROL_CLOSED且无残留视频decoder；742自然完成观察到COMPLETE/CLOSED。Release source set只有空桩，DEX不含研究入口/遥测标记。

## 回归与限制

两个版本、两模块四种组合均打开目标、1080P60及4K50，系统解码器/尺寸符合预期。双模块组合另由原生进度证明持续播放；对4K样本的非全屏/全屏、seek、暂停恢复、HOME返回和切P进行了验证。线上多Surface计数仅作辅助，不冒充视频帧。

未新做直播/PGC/HDR/DRM/codec fallback的完整设备矩阵；既有policy测试通过。尚未实现目标4K120修复。

18:38最终覆盖安装release；实际APK哈希与构建产物一致，新进程确认本模块及hook均加载。三个基础播放样本打开预期codec/尺寸，4K50额外等待一次；仅计基础smoke。显式本地研究intent不再创建播放器，新增研究日志为0。

回滚可本地撤销此检查点；也可通过LSPosed GUI关闭本模块。未修改远程仓库、签名文件、用户账号、全局显示设置或LSP数据库。
