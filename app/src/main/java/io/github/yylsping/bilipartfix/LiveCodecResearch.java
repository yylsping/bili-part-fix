package io.github.yylsping.bilipartfix;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.text.TextUtils;

/** Debug-only, read-only instrumentation for the 7040300 live IJK codec path. */
final class LiveCodecResearch {
    private static final String LIVE_FACTORY =
            "com.bilibili.bililive.playercore.media.ijk.c";
    private static final String LIVE_SELECTOR =
            "com.bilibili.bililive.playercore.media.ijk.b";
    private static final String LIVE_OPTIONS =
            "com.bilibili.bililive.playercore.media.ijk.e";
    private static final String MEDIA_RESOURCE =
            "tv.danmaku.videoplayer.core.media.resource.a";
    private static final String IMEDIA_PLAYER =
            "tv.danmaku.ijk.media.player.IMediaPlayer";
    private static final String CODEC_INFO =
            "tv.danmaku.ijk.media.player.IjkMediaCodecInfo";

    private LiveCodecResearch() {}

    static void install(Context context, ClassLoader classLoader) {
        if (!BuildConfig.DEBUG) return;
        try {
            Class<?> mediaResource = XposedHelpers.findClass(MEDIA_RESOURCE, classLoader);
            XposedHelpers.findAndHookMethod(LIVE_FACTORY, classLoader, "a",
                    Context.class, mediaResource, Object[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object resource = param.args[1];
                            log("player create hostHardware=" + boolField(resource, "c")
                                    + ", codecType=" + intField(resource, "f")
                                    + ", cloud=" + cloudSnapshot(context, classLoader));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object player = param.getResult();
                            log("player created backend=" + (player == null
                                    ? "null" : player.getClass().getName()));
                        }
                    });

            Class<?> player = XposedHelpers.findClass(IMEDIA_PLAYER, classLoader);
            XposedHelpers.findAndHookMethod(LIVE_SELECTOR, classLoader,
                    "a", String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("cached selector mime=" + param.args[0]
                                    + ", selected=" + safe(param.getResult()));
                        }
                    });
            XposedHelpers.findAndHookMethod(LIVE_SELECTOR, classLoader,
                    "onMediaCodecSelect", player, String.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String mime = String.valueOf(param.args[1]);
                            String selected = param.getResult() == null
                                    ? "" : String.valueOf(param.getResult());
                            log("select mime=" + mime
                                    + ", profile=" + param.args[2]
                                    + ", level=" + param.args[3]
                                    + ", selected=" + selected
                                    + ", " + describeCandidate(
                                    classLoader, mime, selected));
                        }
                    });
            log("read-only instrumentation installed");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix/LiveCodec: instrumentation failed", throwable);
        }
    }

    private static String cloudSnapshot(Context context, ClassLoader classLoader) {
        try {
            Class<?> options = XposedHelpers.findClass(LIVE_OPTIONS, classLoader);
            return "fakeName=" + safe(XposedHelpers.callStaticMethod(options, "A"))
                    + ", blockPattern=" + safe(XposedHelpers.callStaticMethod(options, "i"))
                    + ", hwFallback=" + safe(XposedHelpers.callStaticMethod(options, "D"))
                    + ", variableCodec=" + safe(XposedHelpers.callStaticMethod(options, "t"))
                    + ", variableBlocked=" + safe(XposedHelpers.callStaticMethod(options, "N"))
                    + ", hevcPreferred=" + safe(
                    XposedHelpers.callStaticMethod(options, "K", context))
                    + ", hevcSupported=" + safe(XposedHelpers.callStaticMethod(options, "L"));
        } catch (Throwable throwable) {
            return "unavailable:" + throwable.getClass().getSimpleName();
        }
    }

    private static String describeCandidate(ClassLoader classLoader, String mime,
                                            String selected) {
        if (TextUtils.isEmpty(selected)) return "rank=none, hardware=unknown, lowLatency=false";
        try {
            Class<?> codecInfo = XposedHelpers.findClass(CODEC_INFO, classLoader);
            String blockPattern = "";
            try {
                Class<?> options = XposedHelpers.findClass(LIVE_OPTIONS, classLoader);
                blockPattern = safe(XposedHelpers.callStaticMethod(options, "i"));
            } catch (Throwable ignored) {
                // The selected name remains useful even if cloud state cannot be read.
            }
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS)
                    .getCodecInfos()) {
                if (!selected.equals(info.getName())) continue;
                Object candidate = XposedHelpers.callStaticMethod(codecInfo,
                        "setupCandidate", info, mime, blockPattern);
                int rank = candidate == null ? -1
                        : XposedHelpers.getIntField(candidate, "mRank");
                return "rank=" + rank
                        + ", hardware=" + (Build.VERSION.SDK_INT >= 29
                                ? String.valueOf(info.isHardwareAccelerated()) : "unknown")
                        + ", lowLatency=" + selected.toLowerCase().contains("low_latency");
            }
        } catch (Throwable throwable) {
            return "rank=unavailable:" + throwable.getClass().getSimpleName()
                    + ", lowLatency=" + selected.toLowerCase().contains("low_latency");
        }
        return "rank=not-listed, hardware=unknown, lowLatency="
                + selected.toLowerCase().contains("low_latency");
    }

    private static boolean boolField(Object target, String name) {
        try {
            return target != null
                    && Boolean.TRUE.equals(XposedHelpers.getObjectField(target, name));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int intField(Object target, String name) {
        try {
            return target == null ? -1 : XposedHelpers.getIntField(target, name);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String safe(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 256 ? text : text.substring(0, 256) + "...";
    }

    private static void log(String message) {
        XposedBridge.log("BiliPartFix/LiveCodec: " + message);
    }
}
