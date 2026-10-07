package io.github.yylsping.bilipartfix;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Exercise the real extraction boundary with both exact-version getter profiles. */
public class StreamInfoReflectionTest {
    public static final class Index {
        final String fps;
        Index(String fps) { this.fps = fps; }
        public int n() { return 120; }
        public int p() { return 120; }
        public int k() { return 12; }
        public int l() { return 12; }
        public int getWidth() { return 3840; }
        public int getHeight() { return 2160; }
        public JSONObject b() throws org.json.JSONException { return new JSONObject().put("frame_rate", fps); }
    }
    public static final class Dash {
        final List<Index> values;
        Dash(Index... values) { this.values = Arrays.asList(values); }
        public List<Index> h() { return values; }
    }
    public static final class Resource {
        final Dash dash;
        Resource(Index... values) { dash = new Dash(values); }
        public Dash h() { return dash; }
        public boolean E() { return false; }
        public boolean F() { return false; }
    }
    public static final class Options {
        public boolean A() { return false; }
        public boolean C() { return false; }
        public boolean E() { return false; }
    }

    @Test public void uniqueRepresentationPreservesMetadataForBothHosts() {
        for (HostVersion host : new HostVersion[]{HostVersion.V7040300, HostVersion.V7420400}) {
            DecoderPolicy.StreamInfo value = new StreamInfoExtractor(DecoderProfile.forHost(host))
                    .extract(new Resource(new Index("120000/1001")), new Options(), 120);
            assertEquals(DecoderPolicy.VideoCodec.HEVC, value.codec);
            assertEquals(3840, value.width);
            assertEquals(119.88012f, value.fps, 0.001f);
            assertEquals(DecoderPolicy.ProtectionState.CLEAR, value.protection);
        }
    }

    @Test public void ambiguousRepresentationsReachPolicyAsUnknownForBothHosts() {
        for (HostVersion host : new HostVersion[]{HostVersion.V7040300, HostVersion.V7420400}) {
            DecoderPolicy.StreamInfo value = new StreamInfoExtractor(DecoderProfile.forHost(host))
                    .extract(new Resource(new Index("60"), new Index("120")), new Options(), 120);
            assertEquals(DecoderPolicy.VideoCodec.UNKNOWN, value.codec);
            assertEquals(0f, value.fps, 0f);
            assertFalse(value.hasEssentialMetadata());
            assertEquals(120, value.qualityId);
        }
    }
}
