package io.github.yylsping.bilipartfix;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class StreamInfoExtractorTest {
    private static final class Candidate {
        final int qn;
        final float fps;
        final int codec;
        final String url;
        Candidate(int qn, float fps) { this(qn, fps, 12, "test-stream"); }
        Candidate(int qn, float fps, int codec, String url) {
            this.qn = qn; this.fps = fps; this.codec = codec; this.url = url;
        }
    }

    @Test public void sameQnDifferentCodecOrUrlNeverSelectsFirst() {
        Candidate hevc = new Candidate(120, 120f, 12, "hevc");
        Candidate avc = new Candidate(120, 120f, 7, "avc");
        Candidate otherUrl = new Candidate(120, 120f, 12, "other-hevc");
        assertNull(StreamInfoExtractor.uniqueQualityMatch(Arrays.asList(hevc, avc), 120, c -> c.qn));
        assertNull(StreamInfoExtractor.uniqueQualityMatch(Arrays.asList(hevc, otherUrl), 120, c -> c.qn));
    }

    @Test public void frameRateParsingKeepsUnknownAndNonFiniteValuesOutOfCapabilityChecks() {
        assertEquals(120f, StreamInfoExtractor.parseFrameRate("120"), 0.001f);
        assertEquals(120f, StreamInfoExtractor.parseFrameRate("120fps"), 0.001f);
        assertEquals(119.88012f, StreamInfoExtractor.parseFrameRate("120000/1001"), 0.001f);
        assertEquals(59.94006f, StreamInfoExtractor.parseFrameRate("60000/1001"), 0.001f);
        for (String value : new String[]{null, "", "invalid", "0", "-120", "NaN", "Infinity", "120/0", "-120/-1", "1fps20"}) {
            assertEquals(0f, StreamInfoExtractor.parseFrameRate(value), 0f);
        }
    }

    @Test public void differentRepresentationsWithSameQnRemainAmbiguousInEitherOrder() {
        Candidate slow = new Candidate(120, 60f);
        Candidate fast = new Candidate(120, 119.88f);
        assertNull(StreamInfoExtractor.uniqueQualityMatch(Arrays.asList(slow, fast), 120, c -> c.qn));
        assertNull(StreamInfoExtractor.uniqueQualityMatch(Arrays.asList(fast, slow), 120, c -> c.qn));
    }

    @Test public void oneCandidateInTheRequestedQualityGroupIsUsable() {
        Candidate selected = new Candidate(120, 119.88f);
        assertSame(selected, StreamInfoExtractor.uniqueQualityMatch(
                Arrays.asList(new Candidate(80, 30f), null, selected), 120, c -> c.qn));
    }

    @Test public void missingOrUnreadableSelectionStaysUnknown() {
        Candidate selected = new Candidate(120, 119.88f);
        assertNull(StreamInfoExtractor.uniqueQualityMatch(Collections.singletonList(selected), 80, c -> c.qn));
        assertNull(StreamInfoExtractor.uniqueQualityMatch(Collections.singletonList(selected), 0, c -> c.qn));
        assertNull(StreamInfoExtractor.uniqueQualityMatch(
                Arrays.asList(selected, new Candidate(Integer.MIN_VALUE, 30f)), 120, c -> c.qn));
    }

    @Test public void ambiguousMetadataDoesNotQueryCapabilitiesOrOverrideHost() {
        Candidate match = StreamInfoExtractor.uniqueQualityMatch(
                Arrays.asList(new Candidate(120, 60f), new Candidate(120, 120f)), 120, c -> c.qn);
        assertNull(match);
        DecoderPolicy.StreamInfo unknown = new DecoderPolicy.StreamInfo(
                DecoderPolicy.VideoCodec.UNKNOWN, 0, 0, 0, 0, 0f, 0,
                DecoderPolicy.HdrState.SDR, DecoderPolicy.ProtectionState.CLEAR, 120);
        for (boolean hostHardware : new boolean[]{false, true}) {
            DecoderPolicy.DecisionResult result = new DecoderPolicy().decide(
                    CodecModeStore.Mode.AUTO, DecoderPolicy.Scope.NORMAL_UGC, unknown,
                    stream -> { throw new AssertionError("Ambiguous stream must not query size/rate support"); },
                    hostHardware, null, DecoderPolicy.LegacyPolicyConflict.none());
            assertEquals(DecoderPolicy.Decision.USE_HOST, result.decision);
            assertEquals(DecoderPolicy.Reason.STREAM_UNKNOWN, result.reason);
        }
    }
}
