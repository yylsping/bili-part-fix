package io.github.yylsping.bilipartfix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FeatureInstallSummaryTest {
    @Test
    public void allInstalled() {
        String summary = FeatureInstallSummary.of7420400(true, true, true, true, true, true);
        assertTrue(summary.contains("multipart=installed"));
        assertTrue(summary.contains("watchLater=installed"));
        assertTrue(summary.contains("codec=installed"));
        assertTrue(summary.contains("settings=installed"));
        assertTrue(summary.contains("trialPreview=installed"));
        assertTrue(summary.contains("imageSave=installed"));
        assertFalse(summary.contains("FAILED"));
    }

    @Test
    public void multipartFailureDoesNotMaskWatchLater() {
        String summary = FeatureInstallSummary.of7420400(false, true, true, true, true, true);
        assertTrue(summary.contains("multipart=FAILED"));
        assertTrue(summary.contains("watchLater=installed"));
    }

    @Test
    public void watchLaterFailureDoesNotMaskMultipart() {
        String summary = FeatureInstallSummary.of7420400(true, false, true, true, true, true);
        assertTrue(summary.contains("multipart=installed"));
        assertTrue(summary.contains("watchLater=FAILED"));
    }

    @Test
    public void codecFailureDoesNotMaskSettings() {
        String summary = FeatureInstallSummary.of7420400(true, true, false, true, true, true);
        assertTrue(summary.contains("codec=FAILED"));
        assertTrue(summary.contains("settings=installed"));
    }

    @Test
    public void settingsFailureDoesNotMaskCodec() {
        String summary = FeatureInstallSummary.of7420400(true, true, true, false, true, true);
        assertTrue(summary.contains("codec=installed"));
        assertTrue(summary.contains("settings=FAILED"));
    }

    @Test
    public void allFailedIsReportedHonestly() {
        String summary = FeatureInstallSummary.of7420400(false, false, false, false, false, false);
        assertTrue(summary.contains("multipart=FAILED"));
        assertTrue(summary.contains("watchLater=FAILED"));
        assertTrue(summary.contains("codec=FAILED"));
        assertTrue(summary.contains("settings=FAILED"));
        assertFalse(summary.contains("=installed"));
    }

    @Test
    public void skippedFeaturesAreNotClaimedAsInstalled() {
        String summary = FeatureInstallSummary.of7420400(true, true, true, true, true, true);
        assertTrue(summary.contains("skipped"));
    }
}
