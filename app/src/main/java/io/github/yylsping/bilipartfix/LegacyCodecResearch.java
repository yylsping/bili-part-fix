package io.github.yylsping.bilipartfix;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Debug-only, read-only snapshot of the host's legacy selector and cloud controls. */
final class LegacyCodecResearch {
    private static final String OPTIONS = "tv.danmaku.videoplayer.core.media.ijk.d";
    private static final String CODEC_INFO = "tv.danmaku.ijk.media.player.IjkMediaCodecInfo";
    private static final String CODEC_HELPER = "tv.danmaku.ijk.media.player.IjkCodecHelper";

    private LegacyCodecResearch() {}

    static void logSnapshot(Context context, ClassLoader classLoader) {
        if (!BuildConfig.DEBUG) return;
        try {
            Class<?> options = XposedHelpers.findClass(OPTIONS, classLoader);
            log("cloud fakeName=" + safe(XposedHelpers.callStaticMethod(options, "G"))
                    + ", ndkModelBlacklist=" + safe(
                    XposedHelpers.callStaticMethod(options, "H"))
                    + ", powerModelBlacklist=" + safe(
                    XposedHelpers.callStaticMethod(options, "I"))
                    + ", unusedLowLatency=" + safe(
                    XposedHelpers.callStaticMethod(options, "M"))
                    + ", variableCodec=" + safe(
                    XposedHelpers.callStaticMethod(options, "r"))
                    + ", variableCodecBlocked=" + safe(
                    XposedHelpers.callStaticMethod(options, "x0"))
                    + ", h265CpuBlocked=" + safe(
                    XposedHelpers.callStaticMethod(options, "t0"))
                    + ", hevcSupported=" + safe(
                    XposedHelpers.callStaticMethod(options, "v0"))
                    + ", hevcEnabled=" + safe(
                    XposedHelpers.callStaticMethod(options, "u0", context))
                    + ", ndkMediaCodec=" + safe(
                    XposedHelpers.callStaticMethod(options, "l0"))
                    + ", powerMode=" + safe(
                    XposedHelpers.callStaticMethod(options, "o0")));
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix/LegacyCodec: cloud snapshot failed", throwable);
        }

        try {
            Class<?> codecInfo = XposedHelpers.findClass(CODEC_INFO, classLoader);
            Class<?> codecHelper = XposedHelpers.findClass(CODEC_HELPER, classLoader);
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS)
                    .getCodecInfos();
            for (String mime : new String[]{"video/avc", "video/hevc"}) {
                Object bestName = XposedHelpers.callStaticMethod(codecHelper,
                        "getBestCodecName", mime);
                List<String> candidates = new ArrayList<>();
                for (MediaCodecInfo info : infos) {
                    if (info == null || info.isEncoder() || !supports(info, mime)) continue;
                    Object candidate = XposedHelpers.callStaticMethod(codecInfo,
                            "setupCandidate", info, mime, "");
                    int rank = candidate == null ? -1
                            : XposedHelpers.getIntField(candidate, "mRank");
                    String hardware;
                    try {
                        hardware = Build.VERSION.SDK_INT >= 29
                                ? String.valueOf(info.isHardwareAccelerated()) : "unknown";
                    } catch (Throwable ignored) {
                        hardware = "unknown";
                    }
                    candidates.add(info.getName() + ":rank=" + rank + ":hw=" + hardware);
                }
                log("mime=" + mime + ", bestName=" + safe(bestName)
                        + ", candidates=" + candidates);
            }
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix/LegacyCodec: selector snapshot failed", throwable);
        }
    }

    private static boolean supports(MediaCodecInfo info, String mime) {
        try {
            for (String type : info.getSupportedTypes()) {
                if (mime.equalsIgnoreCase(type)) return true;
            }
        } catch (Throwable ignored) {
            // A broken candidate is omitted from this diagnostic snapshot only.
        }
        return false;
    }

    private static String safe(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }

    private static void log(String message) {
        XposedBridge.log("BiliPartFix/LegacyCodec: " + message);
    }
}
