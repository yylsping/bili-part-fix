package io.github.yylsping.bilipartfix;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Choreographer;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

/** Explicit DEBUG intent entry, isolated from all UGC request/selection paths. */
final class HfrControlHarness {
    private static final String TAG = "BiliHfrProbe";
    private static final String EXTRA = "bili_hfr_control";
    private static final WeakHashMap<Activity, Session> SESSIONS = new WeakHashMap<>();

    static void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!activity.getIntent().getBooleanExtra(EXTRA, false)) return;
                    boolean hardware = activity.getIntent().getBooleanExtra("bili_hfr_hardware", true);
                    activity.getIntent().removeExtra(EXTRA);
                    Session prior = SESSIONS.remove(activity);
                    if (prior != null) prior.close();
                    Session session = new Session(activity, loader, hardware);
                    SESSIONS.put(activity, session);
                    session.show();
                }
            });
            for (String lifecycle : new String[]{"onPause", "onDestroy"}) {
                XposedHelpers.findAndHookMethod(Activity.class, lifecycle, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Session session = SESSIONS.remove((Activity) param.thisObject);
                        if (session != null) session.close();
                    }
                });
            }
            log("CONTROL_ENTRY installed=true explicitIntent=true");
        } catch (Throwable error) { log("CONTROL_INSTALL_ERROR type=" + error.getClass().getSimpleName()); }
    }

    private static final class Session implements SurfaceHolder.Callback {
        final Activity activity;
        final ClassLoader loader;
        final boolean hardware;
        final Handler handler = new Handler(Looper.getMainLooper());
        Dialog dialog;
        TextView status;
        Object player;
        SurfaceView surfaceView;
        final Bitmap pixel = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        boolean copyPending;
        int redSamples, blueSamples, colorSwitches, lastColor;
        long started;
        boolean closed;

        Session(Activity activity, ClassLoader loader, boolean hardware) {
            this.activity = activity; this.loader = loader; this.hardware = hardware;
        }

        void show() {
            File file = new File(activity.getCacheDir(), "hfr-control.mp4");
            if (!file.isFile()) { log("CONTROL_ERROR stage=input reason=missingFile"); close(); return; }
            dialog = new Dialog(activity);
            FrameLayout layout = new FrameLayout(activity);
            layout.setBackgroundColor(Color.BLACK);
            SurfaceView surface = new SurfaceView(activity);
            surfaceView = surface;
            surface.getHolder().addCallback(this);
            layout.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            status = new TextView(activity);
            status.setTextColor(Color.WHITE);
            status.setTextSize(18);
            status.setBackgroundColor(0x99000000);
            status.setText("Local 4K120 control — preparing");
            layout.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
            dialog.setContentView(layout);
            dialog.setOnDismissListener(ignored -> close());
            dialog.show();
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // Observe native mode negotiation; display refresh is managed by Scene.
            handler.postDelayed(this::close, 20000L);
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {
            if (closed || player != null) return;
            try {
                Class<?> type = XposedHelpers.findClass("tv.danmaku.ijk.media.player.IjkMediaPlayer", loader);
                player = XposedHelpers.newInstance(type, activity);
                listener("setOnPreparedListener", "OnPreparedListener", () -> {
                    try {
                        XposedHelpers.callMethod(player, "start");
                        started = SystemClock.elapsedRealtime();
                        log("CONTROL_PREPARED hardwareRequested=" + hardware);
                        sampleColor();
                        tick();
                    } catch (Throwable error) { fail("start", error); }
                });
                listener("setOnCompletionListener", "OnCompletionListener", () -> {
                    log("CONTROL_COMPLETE"); close();
                });
                Class<?> errorType = XposedHelpers.findClass(
                        "tv.danmaku.ijk.media.player.IMediaPlayer$OnErrorListener", loader);
                Object errorListener = Proxy.newProxyInstance(loader, new Class<?>[]{errorType}, (proxy, method, args) -> {
                    if (method.getName().equals("onError")) {
                        log("CONTROL_ERROR stage=player what=" + args[1] + " extra=" + args[2]); close(); return true;
                    }
                    return objectMethod(proxy, method.getName(), args);
                });
                XposedHelpers.callMethod(player, "setOnErrorListener", errorListener);
                XposedHelpers.callMethod(player, "setOption", 4, "mediacodec", hardware ? 1L : 0L);
                XposedHelpers.callMethod(player, "setOption", 4, "mediacodec-hevc", hardware ? 1L : 0L);
                XposedHelpers.callMethod(player, "setDisplay", holder);
                XposedHelpers.callMethod(player, "setDataSource", new File(activity.getCacheDir(), "hfr-control.mp4").getAbsolutePath());
                log("CONTROL_INPUT source=localGeneratedHEVC width=3840 height=2160 nominalFps=120 bytes="
                        + new File(activity.getCacheDir(), "hfr-control.mp4").length());
                XposedHelpers.callMethod(player, "prepareAsync");
            } catch (Throwable error) { fail("prepare", error); }
        }

        void listener(String setter, String name, Runnable action) {
            Class<?> type = XposedHelpers.findClass("tv.danmaku.ijk.media.player.IMediaPlayer$" + name, loader);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (self, method, args) -> {
                if (method.getName().startsWith("on")) { action.run(); return null; }
                return objectMethod(self, method.getName(), args);
            });
            XposedHelpers.callMethod(player, setter, proxy);
        }

        void tick() {
            if (closed || player == null) return;
            String line = "CONTROL_WINDOW elapsedMs=" + (SystemClock.elapsedRealtime() - started)
                    + " decoderType=" + scalar("getVideoDecoder") + " declaredFps=" + scalar("getVideoFps")
                    + " decodeEstimate=" + scalar("getVideoDecodeFramesPerSecond")
                    + " outputEstimate=" + scalar("getVideoOutputFramesPerSecond")
                    + " redSamples=" + redSamples + " blueSamples=" + blueSamples
                    + " colorSwitches=" + colorSwitches
                    + " windowPreference=" + dialog.getWindow().getAttributes().preferredDisplayModeId
                    + " surfaceHint=none"
                    + " positionMs=" + scalar("getCurrentPosition")
                    + " displayMode=" + activity.getDisplay().getMode().getModeId()
                    + " refreshRate=" + activity.getDisplay().getRefreshRate();
            log(line);
            status.setText(line);
            handler.postDelayed(this::tick, 1000L);
        }

        // These are compositor-buffer samples, not counts of decoded or presented frames.
        void sampleColor() {
            if (closed) return;
            if (!copyPending && surfaceView.getWidth() > 2 && surfaceView.getHeight() > 2) {
                copyPending = true;
                int x = surfaceView.getWidth() / 2, y = surfaceView.getHeight() / 2;
                try {
                    PixelCopy.request(surfaceView, new Rect(x, y, x + 2, y + 2), pixel, result -> {
                        copyPending = false;
                        if (closed || result != PixelCopy.SUCCESS) return;
                        int value = pixel.getPixel(0, 0);
                        int color = Color.red(value) > Color.blue(value) + 50 ? 1
                                : Color.blue(value) > Color.red(value) + 50 ? 2 : 0;
                        if (color == 1) redSamples++;
                        if (color == 2) blueSamples++;
                        if (color != 0 && lastColor != 0 && color != lastColor) colorSwitches++;
                        if (color != 0) lastColor = color;
                    }, handler);
                } catch (Throwable ignored) { copyPending = false; }
            }
            Choreographer.getInstance().postFrameCallback(ignored -> sampleColor());
        }

        Object scalar(String getter) {
            try { Object value = XposedHelpers.callMethod(player, getter); return value instanceof Number ? value : "unknown"; }
            catch (Throwable ignored) { return "unknown"; }
        }

        void fail(String stage, Throwable error) { log("CONTROL_ERROR stage=" + stage + " type=" + error.getClass().getSimpleName()); close(); }
        void close() {
            if (closed) return;
            closed = true;
            handler.removeCallbacksAndMessages(null);
            if (player != null) { try { XposedHelpers.callMethod(player, "release"); } catch (Throwable ignored) {} player = null; }
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            SESSIONS.remove(activity);
            log("CONTROL_CLOSED");
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
        @Override public void surfaceDestroyed(SurfaceHolder holder) { close(); }
    }

    private static Object objectMethod(Object self, String method, Object[] args) {
        if (method.equals("hashCode")) return System.identityHashCode(self);
        if (method.equals("equals")) return self == args[0];
        if (method.equals("toString")) return "HfrControlListener";
        return null;
    }
    private static void log(String message) { Log.i(TAG, message); }
}
