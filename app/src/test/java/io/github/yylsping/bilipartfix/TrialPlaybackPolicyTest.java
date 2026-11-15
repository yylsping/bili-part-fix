package io.github.yylsping.bilipartfix;

import org.junit.Test;

import static org.junit.Assert.*;

public class TrialPlaybackPolicyTest {
    private static byte[] arc(long aid, long cid, int type, int drm, int preview, long duration) {
        return new ProtoWire.Writer().number(1, type).number(2, aid).number(3, cid)
                .number(6, drm).number(9, preview).number(10, duration).bytes();
    }

    @Test public void requiresServerTrialAndMatchingUgcIdentity() {
        assertEquals(1000, TrialPlaybackPolicy.previewLimit(arc(1, 2, 1, 0, 1, 1000), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 3, 1, 0, 1, 1000), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(3, 2, 1, 0, 1, 1000), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 2, 2, 0, 1, 1000), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 2, 1, 1, 1, 1000), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 2, 1, 0, 0, 1000), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 2, 1, 0, 1, 0), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 2, 1, 0, 1, -1), 1, 2));
        assertEquals(0, TrialPlaybackPolicy.previewLimit(arc(1, 2, 1, 0, 1, 1000), 0, 2));
        byte[] interactive = new ProtoWire.Writer().number(1, 1).number(2, 1).number(3, 2)
                .number(4, 1).number(9, 1).number(10, 1000).bytes();
        assertEquals(0, TrialPlaybackPolicy.previewLimit(interactive, 1, 2));
    }

    @Test public void checksWholeMediaDurationWithoutOverflow() {
        assertTrue(TrialPlaybackPolicy.boundedSegments(1000, new long[]{400, 600}));
        assertTrue(TrialPlaybackPolicy.boundedSegments(1000, new long[]{400}));
        assertFalse(TrialPlaybackPolicy.boundedSegments(1000, new long[]{400, 601}));
        assertFalse(TrialPlaybackPolicy.boundedSegments(1000, new long[]{0}));
        assertFalse(TrialPlaybackPolicy.boundedSegments(1000, new long[]{-1}));
        assertFalse(TrialPlaybackPolicy.boundedSegments(1000, new long[0]));
        assertFalse(TrialPlaybackPolicy.boundedSegments(Long.MAX_VALUE, new long[]{Long.MAX_VALUE, 1}));
        assertFalse(TrialPlaybackPolicy.boundedSegments(0, new long[]{1}));
        long[] tooMany = new long[257];
        java.util.Arrays.fill(tooMany, 1);
        assertFalse(TrialPlaybackPolicy.boundedSegments(1000, tooMany));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsMalformedArc() {
        TrialPlaybackPolicy.previewLimit(new byte[]{(byte) 0x80}, 1, 2);
    }
}
