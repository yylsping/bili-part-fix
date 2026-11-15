package io.github.yylsping.bilipartfix;

import java.util.List;

/** Wire-level identity and duration checks, independent of Android and host classes. */
final class TrialPlaybackPolicy {
    private TrialPlaybackPolicy() {}

    static long previewLimit(byte[] playArc, long aid, long cid) {
        List<ProtoWire.Field> arc = ProtoWire.read(playArc);
        if (aid <= 0 || cid <= 0 || ProtoWire.number(arc, 1) != 1
                || ProtoWire.number(arc, 2) != aid || ProtoWire.number(arc, 3) != cid
                || ProtoWire.number(arc, 4) != 0 || ProtoWire.number(arc, 6) != 0
                || ProtoWire.message(arc, 6).length != 0 || ProtoWire.number(arc, 9) != 1) return 0;
        return Math.max(0, ProtoWire.number(arc, 10));
    }

    static boolean boundedSegments(long limit, long[] lengths) {
        if (limit <= 0 || lengths.length == 0 || lengths.length > 256) return false;
        long remaining = limit;
        for (long length : lengths) {
            if (length <= 0 || length > remaining) return false;
            remaining -= length;
        }
        return true;
    }
}
