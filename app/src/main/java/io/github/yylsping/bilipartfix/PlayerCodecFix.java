package io.github.yylsping.bilipartfix;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Applies the shared Smart Auto policy at the real MediaResource -> IJK
 * transform boundary. Version-specific symbols come from DecoderProfile;
 * the decision logic is identical on both supported hosts.
 */
final class PlayerCodecFix {
    private static final String MEDIA_RESOURCE =
            "com.bilibili.lib.media.resource.MediaResource";
    private static final String TRANSFORMER =
            "tv.danmaku.videoplayer.coreV2.transformer.b";
    private static final String MEDIA_ITEM_CALLBACK =
            "tv.danmaku.videoplayer.coreV2.h$b";
    private static final String PRIOR_OVERRIDE = "smartAutoPriorOverride";
    private static final String PRIOR_CONTEXT = "smartAutoPriorContext";
    private static final ThreadLocal<Boolean> HARDWARE_OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<DecisionContext> ACTIVE_CONTEXT = new ThreadLocal<>();
    private static final WeakIdentitySet<Object> LOGGED_MEDIA = new WeakIdentitySet<>();

    private PlayerCodecFix() {}

    static boolean install(Context context, ClassLoader classLoader, HostVersion host) {
        DecoderProfile profile = DecoderProfile.forHost(host);
        if (profile == null) {
            XposedBridge.log("no decoder profile for " + host + "; Smart Auto not installed");
            return false;
        }
        CodecModeStore store = new CodecModeStore(context);
        LazyCapabilityProvider capability = new LazyCapabilityProvider(() -> {
            if (BuildConfig.DEBUG) {
                XposedBridge.log("BiliPartFix/SmartAuto: initializing capability snapshot");
            }
            return CodecCapability.detect();
        });
        DecoderPolicy policy = new DecoderPolicy();
        RuntimeFailureMemory failureMemory = new RuntimeFailureMemory();
        StreamInfoExtractor extractor = new StreamInfoExtractor(profile);
        NormalUgcScope scope = new NormalUgcScope();
        scope.install(classLoader, profile);

        Class<?> optionsClass = XposedHelpers.findClass(profile.itemOptionsClass, classLoader);
        XposedHelpers.findAndHookMethod(optionsClass, profile.hardwarePreferenceGetter,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Boolean override = HARDWARE_OVERRIDE.get();
                        if (override != null) param.setResult(override);
                    }
                });

        Class<?> mediaResourceClass = XposedHelpers.findClass(MEDIA_RESOURCE, classLoader);
        XposedHelpers.findAndHookMethod(mediaResourceClass, profile.assetBuildMethod,
                int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        DecisionContext active = ACTIVE_CONTEXT.get();
                        if (active == null || active.evaluated
                                || active.mediaResource != param.thisObject) return;
                        int selectedVideoId = 0;
                        Object asset = param.getResult();
                        if (asset != null) {
                            try {
                                Object value = XposedHelpers.callMethod(asset,
                                        "getDefaultVideoId");
                                if (value instanceof Number) {
                                    selectedVideoId = ((Number) value).intValue();
                                }
                            } catch (Throwable throwable) {
                                debug("selected IjkMediaAsset video id unavailable", throwable);
                            }
                        }
                        active.evaluate(selectedVideoId, policy, capability, failureMemory,
                                extractor);
                    }
                });

        Class<?> callbackClass = XposedHelpers.findClass(MEDIA_ITEM_CALLBACK, classLoader);
        XposedHelpers.findAndHookMethod(TRANSFORMER, classLoader, "a",
                mediaResourceClass, optionsClass, callbackClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Boolean priorOverride = HARDWARE_OVERRIDE.get();
                        DecisionContext priorContext = ACTIVE_CONTEXT.get();
                        param.setObjectExtra(PRIOR_OVERRIDE, priorOverride);
                        param.setObjectExtra(PRIOR_CONTEXT, priorContext);
                        HARDWARE_OVERRIDE.remove();
                        ACTIVE_CONTEXT.remove();

                        Object mediaResource = param.args[0];
                        Object options = param.args[1];
                        DecisionContext active = new DecisionContext(mediaResource, options,
                                store.getMode(), scope.contains(mediaResource),
                                readHostHardware(options, profile));
                        if (BuildConfig.DEBUG) {
                            XposedBridge.log("BiliPartFix/SmartAuto: transformer enter identity="
                                    + System.identityHashCode(mediaResource)
                                    + ", normalUgc=" + active.normalUgc);
                        }
                        ACTIVE_CONTEXT.set(active);
                        if (!active.normalUgc) {
                            active.evaluate(0, policy, capability, failureMemory, extractor);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        DecisionContext active = ACTIVE_CONTEXT.get();
                        if (active != null && !active.evaluated) {
                            active.evaluate(0, policy, capability, failureMemory, extractor);
                        }
                        HARDWARE_OVERRIDE.remove();
                        ACTIVE_CONTEXT.remove();
                        Object priorOverride = param.getObjectExtra(PRIOR_OVERRIDE);
                        Object priorContext = param.getObjectExtra(PRIOR_CONTEXT);
                        if (priorOverride instanceof Boolean) {
                            HARDWARE_OVERRIDE.set((Boolean) priorOverride);
                        }
                        if (priorContext instanceof DecisionContext) {
                            ACTIVE_CONTEXT.set((DecisionContext) priorContext);
                        }
                    }
                });
        XposedBridge.log(host + " Smart Auto codec policy installed; capability detection=lazy");
        if (BuildConfig.DEBUG) HfrControlHarness.install(classLoader);
        if (BuildConfig.DEBUG && host == HostVersion.V7040300) {
            // The read-only research instrumentation hardcodes 7040300 symbols.
            LiveCodecResearch.install(context, classLoader);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> LegacyCodecResearch.logSnapshot(context, classLoader), 5000L);
        }
        return true;
    }

    private static final class DecisionContext {
        final Object mediaResource;
        final Object options;
        final CodecModeStore.Mode mode;
        final boolean normalUgc;
        final boolean hostHardware;
        boolean evaluated;

        DecisionContext(Object mediaResource, Object options, CodecModeStore.Mode mode,
                        boolean normalUgc, boolean hostHardware) {
            this.mediaResource = mediaResource;
            this.options = options;
            this.mode = mode;
            this.normalUgc = normalUgc;
            this.hostHardware = hostHardware;
        }

        void evaluate(int selectedVideoId, DecoderPolicy policy,
                      DecoderPolicy.CapabilityProvider capability,
                      RuntimeFailureMemory failureMemory,
                      StreamInfoExtractor extractor) {
            if (evaluated) return;
            evaluated = true;
            DecoderPolicy.StreamInfo stream = normalUgc
                    ? extractor.extract(mediaResource, options, selectedVideoId)
                    : DecoderPolicy.StreamInfo.unknown();
            DecoderPolicy.DecisionResult result = policy.decide(mode,
                    normalUgc ? DecoderPolicy.Scope.NORMAL_UGC
                            : DecoderPolicy.Scope.HOST_UNSUPPORTED,
                    stream, capability, hostHardware, failureMemory,
                    DecoderPolicy.LegacyPolicyConflict.none());
            if (result.decision == DecoderPolicy.Decision.PREFER_HARDWARE) {
                HARDWARE_OVERRIDE.set(Boolean.TRUE);
            } else if (result.decision == DecoderPolicy.Decision.PREFER_SOFTWARE) {
                HARDWARE_OVERRIDE.set(Boolean.FALSE);
            }
            debugDecisionOnce(mediaResource, mode, normalUgc, hostHardware, stream, result);
        }
    }

    private static boolean readHostHardware(Object options, DecoderProfile profile) {
        try {
            return Boolean.TRUE.equals(
                    XposedHelpers.callMethod(options, profile.hardwarePreferenceGetter));
        } catch (Throwable throwable) {
            debug("host preference unavailable", throwable);
            return false;
        }
    }

    private static void debugDecisionOnce(Object mediaResource, CodecModeStore.Mode mode,
                                          boolean normalUgc, boolean hostHardware,
                                          DecoderPolicy.StreamInfo stream,
                                          DecoderPolicy.DecisionResult result) {
        if (!BuildConfig.DEBUG || !LOGGED_MEDIA.add(mediaResource)) return;
        CodecCapability.Assessment assessment = result.capability;
        XposedBridge.log("BiliPartFix/SmartAuto: mode=" + mode.storedValue
                + ", scope=" + (normalUgc ? "ugc" : "host")
                + ", host=" + hostHardware
                + ", codec=" + (stream.codec.mime.isEmpty() ? "unknown" : stream.codec.mime)
                + ", profile=" + stream.profile
                + ", level=" + stream.level
                + ", bitDepth=" + stream.bitDepth
                + ", size=" + stream.width + 'x' + stream.height
                + ", fps=" + stream.fps
                + ", hdr=" + stream.hdr
                + ", protection=" + stream.protection
                + ", qn=" + stream.qualityId
                + ", decoder=" + (assessment.decoderName.isEmpty()
                        ? "unknown" : assessment.decoderName)
                + ", capability=" + assessment.support
                + ", capabilityDetail=" + assessment.detail
                + ", failureCacheHit=" + result.failureCacheHit
                + ", decision=" + result.decision
                + ", reason=" + result.reason
                + ", overrideConfidence=" + result.confidence);
    }

    private static void debug(String message, Throwable throwable) {
        if (BuildConfig.DEBUG) {
            XposedBridge.log("BiliPartFix/SmartAuto: " + message, throwable);
        }
    }
}
