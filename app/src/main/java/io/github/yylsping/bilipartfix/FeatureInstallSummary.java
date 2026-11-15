package io.github.yylsping.bilipartfix;

/**
 * Builds the per-feature install-result log lines. The entry point reports
 * each feature independently so a failure of one can never masquerade as an
 * overall success, and a successful feature is still reported when its
 * sibling fails.
 */
final class FeatureInstallSummary {
    private FeatureInstallSummary() {}

    static String of7420400(boolean multipartInstalled, boolean watchLaterInstalled,
                            boolean codecInstalled, boolean settingsInstalled,
                            boolean trialPreviewInstalled, boolean imageSaveInstalled) {
        return "7420400 feature results: multipart=" + status(multipartInstalled)
                + ", watchLater=" + status(watchLaterInstalled)
                + ", codec=" + status(codecInstalled)
                + ", settings=" + status(settingsInstalled)
                + ", trialPreview=" + status(trialPreviewInstalled)
                + ", imageSave=" + status(imageSaveInstalled)
                + "; legacy content adapters skipped";
    }

    static String status(boolean installed) {
        return installed ? "installed" : "FAILED";
    }
}
