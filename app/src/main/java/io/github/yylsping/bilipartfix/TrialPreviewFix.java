package io.github.yylsping.bilipartfix;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Repairs 7.42.0's initial charge gate and legacy PlayURL transport, using authorized trial media. */
final class TrialPreviewFix {
    private static final String PLAYER = "com.bilibili.video.videodetail.player.VideoDetailPlayer";
    private static final String DETAIL = "tv.danmaku.bili.videopage.data.view.model.BiliVideoDetail";
    private static final String MODERN = "com.bapis.bilibili.app.playerunite.v1.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<DetailCall> DETAIL_CALL = new ThreadLocal<>();
    private static final MemoryCache<String, TrialMedia> MEDIA = new MemoryCache<>(16, 120_000L, 10_000L);
    private static final MemoryCache<String, Boolean> CANDIDATES = new MemoryCache<>(16, 3_600_000L, 1000L);
    private static final Map<String, Attempt> PENDING = new ConcurrentHashMap<>();

    private TrialPreviewFix() {}

    private static final class Worker {
        static final ThreadPoolExecutor EXECUTOR = create();

        private static ThreadPoolExecutor create() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4), runnable -> {
                        Thread thread = new Thread(runnable, "BiliPartFix-trial");
                        thread.setDaemon(true);
                        return thread;
                    });
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }

    static boolean install(ClassLoader loader) {
        try {
            Class<?> detailType = XposedHelpers.findClass(DETAIL, loader);
            Class<?> moss = XposedHelpers.findClass(MODERN + "PlayerMoss", loader);
            Class<?> request = XposedHelpers.findClass(MODERN + "PlayViewUniteReq", loader);
            Class<?> playerType = XposedHelpers.findClass(PLAYER, loader);
            Class<?> callbackType = XposedHelpers.findClass(PLAYER + "$t", loader);
            Field owner = null;
            for (Field field : callbackType.getDeclaredFields()) {
                if (field.getType() == playerType) {
                    if (owner != null) throw new IllegalStateException("ambiguous detail callback owner");
                    owner = field;
                }
            }
            if (owner == null) throw new IllegalStateException("missing detail callback owner");
            owner.setAccessible(true);
            Field callbackOwner = owner;

            // Installed first: an opened gate must always have a playback-response adapter.
            XposedHelpers.findAndHookMethod("com.bapis.bilibili.app.playurl.v1.PlayURLMoss", loader,
                    "playView", XposedHelpers.findClass("com.bapis.bilibili.app.playurl.v1.PlayViewReq", loader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() == null) return;
                            try {
                                Object oldRequest = param.args[0];
                                if ((Integer) call(oldRequest, "getDownload") != 0
                                        || (Integer) call(oldRequest, "getTeenagersMode") != 0
                                        || (Boolean) call(param.getResult(), "hasVideoInfo")
                                        || !(Boolean) call(param.getResult(), "hasPlayLimit")) return;
                                long aid = (Long) call(oldRequest, "getAid"), cid = (Long) call(oldRequest, "getCid");
                                String key = key(aid, cid);
                                MemoryCache.Lookup<TrialMedia> cached = MEDIA.get(key);
                                TrialMedia media = cached.value;
                                if (!cached.present && Boolean.TRUE.equals(CANDIDATES.get(key).value)
                                        && Looper.myLooper() != Looper.getMainLooper()) {
                                    media = fetch(loader, moss, request, aid, cid, oldRequest);
                                    MEDIA.put(key, media);
                                }
                                if (media == null) return;
                                param.setResult(media.apply(param.getResult(), (Long) call(oldRequest, "getQn")));
                                PreviewNotice.update(aid, media.viewInfo);
                                if (BuildConfig.DEBUG) XposedBridge.log("verified trial applied; limitMillis=" + media.limitMillis);
                            } catch (Throwable failure) {
                                XposedBridge.log("trial response left unchanged: " + failure.getClass().getSimpleName());
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(PLAYER, loader, "M3", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    DetailCall current = DETAIL_CALL.get();
                    if (current == null) return;
                    CANDIDATES.put(current.key, true);
                    MemoryCache.Lookup<TrialMedia> cached = MEDIA.get(current.key);
                    if (cached.present) {
                        if (cached.value != null) param.setResult(false);
                        return;
                    }
                    current.player = new WeakReference<>(param.thisObject);
                    Attempt attempt = new Attempt(current);
                    Attempt pending = PENDING.putIfAbsent(current.key, attempt);
                    if (pending != null) {
                        if (pending.waiters.size() < 4) pending.waiters.add(current);
                        return;
                    }
                    try {
                        Worker.EXECUTOR.execute(() -> resolve(loader, moss, request, attempt));
                    } catch (RuntimeException rejected) {
                        PENDING.remove(current.key, attempt);
                    }
                    // Until verified media is ready the original charge cover stays in place.
                }
            });

            XposedHelpers.findAndHookMethod(PLAYER + "$t", loader, "b", detailType, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("previousTrialDetail", DETAIL_CALL.get());
                    DETAIL_CALL.remove();
                    try {
                        Object detail = param.args[0];
                        Object player = callbackOwner.get(param.thisObject);
                        Activity activity = (Activity) XposedHelpers.getObjectField(player, "a");
                        if (activity != null) PreviewNotice.bind(activity,
                                detail == null ? 0 : XposedHelpers.getLongField(detail, "mAvid"));
                        if (detail == null || XposedHelpers.getObjectField(detail, "mBangumiInfo") != null) return;
                        long aid = XposedHelpers.getLongField(detail, "mAvid");
                        long cid = XposedHelpers.getLongField(detail, "mCid");
                        if (aid > 0 && cid > 0) DETAIL_CALL.set(new DetailCall(param.thisObject, detail, aid, cid));
                    } catch (Throwable failure) {
                        if (BuildConfig.DEBUG) XposedBridge.log("trial detail unavailable", failure);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    DetailCall previous = (DetailCall) param.getObjectExtra("previousTrialDetail");
                    if (previous == null) DETAIL_CALL.remove();
                    else DETAIL_CALL.set(previous);
                }
            });
            PreviewNotice.install();
            XposedBridge.log("charge preview compatibility hook installed for 7420400");
            return true;
        } catch (Throwable failure) {
            XposedBridge.log("charge preview hook installation failed", failure);
            return false;
        }
    }

    private static void resolve(ClassLoader loader, Class<?> moss, Class<?> requestType, Attempt attempt) {
        DetailCall current = attempt.first;
        TrialMedia media = null;
        try {
            media = fetch(loader, moss, requestType, current.aid, current.cid, null);
        } catch (Throwable failure) {
            XposedBridge.log("trial unavailable; retaining charge cover: " + failure.getClass().getSimpleName());
        } finally {
            MEDIA.put(current.key, media);
            PENDING.remove(current.key, attempt);
        }
        if (media == null) return;
        MAIN.post(() -> {
            for (DetailCall waiter : attempt.waiters) resume(waiter);
        });
    }

    private static TrialMedia fetch(ClassLoader loader, Class<?> moss, Class<?> requestType,
                                    long aid, long cid, Object oldRequest) throws Exception {
        return BoundedTaskRunner.run(() -> fetchNative(loader, moss, requestType, aid, cid, oldRequest), 8000L);
    }

    private static TrialMedia fetchNative(ClassLoader loader, Class<?> moss, Class<?> requestType,
                                          long aid, long cid, Object oldRequest) {
        // Moss is initialized by the host after Application.attach, not at hook installation.
        Object options = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.bilibili.lib.moss.api.CallOptionsKt", loader), "getDEF_OPTIONS");
        Object bounded = call(options, "withTimeout", 8L, TimeUnit.SECONDS);
        Object service = XposedHelpers.newInstance(moss, "grpc.biliapi.net", 443, bounded);
        // No version or session header changes. A later native quality/retry request
        // carries its own options; initial charge-cover discovery uses the server default.
        ProtoWire.Writer vod = new ProtoWire.Writer().number(1, aid).number(2, cid).number(11, 1);
        ProtoWire.Writer body = new ProtoWire.Writer();
        if (oldRequest == null) vod.number(5, 4048);
        else {
            vod.number(3, (Long) call(oldRequest, "getQn"))
                    .number(4, (Integer) call(oldRequest, "getFnver"))
                    .number(5, (Integer) call(oldRequest, "getFnval"))
                    .number(7, (Integer) call(oldRequest, "getForceHost"))
                    .number(8, (Boolean) call(oldRequest, "getFourk") ? 1 : 0)
                    .number(9, (Integer) call(oldRequest, "getPreferCodecTypeValue"))
                    .number(10, (Long) call(oldRequest, "getVoiceBalance"));
            body.text(2, (String) call(oldRequest, "getSpmid"))
                    .text(3, (String) call(oldRequest, "getFromSpmid"));
        }
        Object request = XposedHelpers.callStaticMethod(requestType, "parseFrom", body.message(1, vod.bytes()).bytes());
        Object response = call(service, "playViewUnite", request);
        return response == null ? null : TrialMedia.parse(loader, (byte[]) call(response, "toByteArray"), aid, cid);
    }

    private static void resume(DetailCall current) {
        try {
            Object callback = current.callback.get(), detail = current.detail.get();
            Object player = current.player == null ? null : current.player.get();
            if (callback == null || detail == null || player == null) return;
            Activity activity = (Activity) XposedHelpers.getObjectField(player, "a");
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
            // A reply from a previous video must never restart the current page.
            Object repository = XposedHelpers.getObjectField(player, "e");
            if (call(repository, "k") != detail) return;
            call(callback, "b", detail);
        } catch (Throwable failure) {
            XposedBridge.log("trial resume skipped", failure);
        }
    }

    private static final class Attempt {
        final DetailCall first;
        // Added and traversed only on the main thread, including the posted completion.
        final List<DetailCall> waiters = new ArrayList<>();
        Attempt(DetailCall current) { first = current; waiters.add(current); }
    }

    private static final class DetailCall {
        final WeakReference<Object> callback;
        final WeakReference<Object> detail;
        final long aid, cid;
        final String key;
        WeakReference<Object> player;

        DetailCall(Object callback, Object detail, long aid, long cid) {
            this.callback = new WeakReference<>(callback);
            this.detail = new WeakReference<>(detail);
            this.aid = aid;
            this.cid = cid;
            this.key = key(aid, cid);
        }
    }

    private static String key(long aid, long cid) { return aid + ":" + cid; }

    private static Object call(Object target, String method, Object... args) {
        return XposedHelpers.callMethod(target, method, args);
    }
}
