package io.github.yylsping.bilipartfix;

/**
 * Supported host versions of tv.danmaku.bili and their version-specific
 * obfuscated symbols. Pure value logic, no Android or Xposed dependencies,
 * so it can be unit tested on the JVM.
 */
public enum HostVersion {
    V7040300(7040300L, "w1"),
    V7420400(7420400L, "A1"),
    UNSUPPORTED(-1L, null);

    private final long versionCode;
    private final String dataSourceRouterMethod;

    HostVersion(long versionCode, String dataSourceRouterMethod) {
        this.versionCode = versionCode;
        this.dataSourceRouterMethod = dataSourceRouterMethod;
    }

    public static HostVersion from(long versionCode) {
        for (HostVersion version : values()) {
            if (version.versionCode == versionCode) return version;
        }
        return UNSUPPORTED;
    }

    public boolean isSupported() {
        return this != UNSUPPORTED;
    }

    /**
     * Whether this host gets the full legacy fix set. Only 7040300 does;
     * 7420400 installs its own multipart, watch-later, decoder-mode,
     * charge-trial and image-save fixes, retaining native content rendering.
     */
    public boolean installsLegacyFixes() {
        return this == V7040300;
    }

    /**
     * Name of the obfuscated router method on
     * tv.danmaku.bili.videopage.player.datasource.b that picks between the
     * season data source and the normal multipart data source.
     * "w1" on 7040300, "A1" on 7420400.
     */
    public String dataSourceRouterMethod() {
        return dataSourceRouterMethod;
    }
}
