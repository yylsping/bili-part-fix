package io.github.yylsping.bilipartfix;

import java.util.ArrayList;
import java.util.List;

/** Only server-authorized, independently duration-bounded segment media reaches the old player. */
final class TrialMedia {
    final Object videoInfo;
    final Object playArcConf;
    final byte[] viewInfo;
    final long limitMillis;

    private TrialMedia(Object videoInfo, Object playArcConf, byte[] viewInfo, long limitMillis) {
        this.videoInfo = videoInfo;
        this.playArcConf = playArcConf;
        this.viewInfo = viewInfo;
        this.limitMillis = limitMillis;
    }

    static TrialMedia parse(ClassLoader loader, byte[] response, long aid, long cid) {
        List<ProtoWire.Field> reply = ProtoWire.read(response);
        long limit = TrialPlaybackPolicy.previewLimit(ProtoWire.message(reply, 6), aid, cid);
        if (limit <= 0 || ProtoWire.message(reply, 1).length == 0) return null;
        Class<?> type = XposedHelpers.findClass("com.bapis.bilibili.app.playurl.v1.VideoInfo", loader);
        Object info = XposedHelpers.callStaticMethod(type, "parseFrom", ProtoWire.message(reply, 1));
        long duration = (Long) call(info, "getTimelength");
        if (duration <= 0) return null;
        int selected = (Integer) call(info, "getQuality");
        boolean selectedValid = false;
        List<Object> streams = new ArrayList<>();
        for (Object stream : (List<?>) call(info, "getStreamListList")) {
            Object meta = call(stream, "getStreamInfo");
            if ((Integer) call(meta, "getErrCodeValue") != 0
                    || !(Boolean) call(stream, "hasSegmentVideo")
                    || (Boolean) call(stream, "hasDashVideo")) continue;
            List<?> parts = (List<?>) call(call(stream, "getSegmentVideo"), "getSegmentList");
            if (parts.isEmpty() || parts.size() > 256) continue;
            long[] lengths = new long[parts.size()];
            boolean urlsValid = true;
            for (int i = 0; i < parts.size(); i++) {
                lengths[i] = (Long) call(parts.get(i), "getLength");
                String url = (String) call(parts.get(i), "getUrl");
                if (!(url.startsWith("https://") || url.startsWith("http://"))) urlsValid = false;
            }
            if (!urlsValid || !TrialPlaybackPolicy.boundedSegments(limit, lengths)) continue;
            streams.add(stream);
            if ((Integer) call(meta, "getQuality") == selected) selectedValid = true;
        }
        if (!selectedValid) return null;
        Object builder = call(info, "toBuilder");
        call(builder, "clearStreamList");
        call(builder, "addAllStreamList", streams);
        call(builder, "clearDashAudio");
        call(builder, "setTimelength", Math.min(limit, duration));
        Object conf = null;
        byte[] arcConf = PlayerUniteFix.mapArcConf(ProtoWire.message(reply, 2));
        if (arcConf.length > 0) conf = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.bapis.bilibili.app.playurl.v1.PlayArcConf", loader), "parseFrom", arcConf);
        return new TrialMedia(call(builder, "build"), conf, ProtoWire.message(reply, 9), limit);
    }

    Object apply(Object legacyReply, long requestedQuality) {
        Object selectedInfo = videoInfo;
        // Keep the host's requested quality when the server supplied a bounded stream for it.
        for (Object stream : (List<?>) call(videoInfo, "getStreamListList")) {
            if ((Integer) call(call(stream, "getStreamInfo"), "getQuality") == requestedQuality) {
                Object qualityBuilder = call(videoInfo, "toBuilder");
                call(qualityBuilder, "setQuality", (int) requestedQuality);
                selectedInfo = call(qualityBuilder, "build");
                break;
            }
        }
        Object builder = call(legacyReply, "toBuilder");
        call(builder, "setVideoInfo", selectedInfo);
        if (playArcConf != null) call(builder, "setPlayArc", playArcConf);
        call(builder, "clearPlayLimit");
        call(builder, "clearUpgradeLimit");
        return call(builder, "build");
    }

    private static Object call(Object target, String method, Object... args) {
        return XposedHelpers.callMethod(target, method, args);
    }
}
