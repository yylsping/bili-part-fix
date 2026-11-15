package io.github.yylsping.bilipartfix;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Shows the server's trial/charge explanation on the legacy video surface. */
final class PreviewNotice {
    private static final String ACTIVITY = "com.bilibili.video.videodetail.VideoDetailsActivity";
    private static final String TAG = "bilipartfix:preview-notice";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Activity, Boolean> ACTIVITIES = new WeakHashMap<>();
    private static final Map<Activity, Long> BOUND_IDENTITIES = new WeakHashMap<>();
    private static final MemoryCache<Long, Notice> NOTICES = new MemoryCache<>(32, 300_000L, 1_000L);

    private PreviewNotice() {}

    /** 7.42.0 supplies its already-resolved identity from the detail callback. Main thread only. */
    static void bind(Activity activity, long aid) {
        BOUND_IDENTITIES.put(activity, aid);
        ACTIVITIES.put(activity, true);
        schedule(activity);
    }

    static void install() {
        XposedBridge.hookAllMethods(Activity.class, "performResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!ACTIVITY.equals(activity.getClass().getName())) return;
                ACTIVITIES.put(activity, true);
                schedule(activity);
            }
        });
    }

    static void update(long aid, byte[] viewInfo) {
        Notice notice = null;
        if (viewInfo != null) {
            List<ProtoWire.Field> prompt = ProtoWire.read(
                    ProtoWire.message(ProtoWire.read(viewInfo), 2));
            String title = text(prompt, 1), subtitle = text(prompt, 2);
            List<ProtoWire.Field> button = ProtoWire.read(ProtoWire.message(prompt, 6));
            String link = ProtoWire.text(button, 6);
            String message = (title + "\n" + subtitle).trim();
            if (message.isEmpty()) message = "该视频仅开放试看；完整观看取决于视频要求及当前账号权限";
            notice = new Notice(message, link);
        }
        NOTICES.put(aid, notice);
        MAIN.post(() -> {
            for (Activity activity : new ArrayList<>(ACTIVITIES.keySet())) {
                if (activity != null && videoId(activity) == aid) schedule(activity);
            }
        });
    }

    private static String text(List<ProtoWire.Field> fields, int number) {
        return ProtoWire.text(ProtoWire.read(ProtoWire.message(fields, number)), 1);
    }

    private static void schedule(Activity activity) {
        RetryScheduler.schedule(activity, "preview-notice", new long[]{0, 250, 800, 1600},
                target -> render((Activity) target));
    }

    private static boolean render(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return true;
        long aid = videoId(activity);
        boolean boundHost = BOUND_IDENTITIES.containsKey(activity);
        // On 7.42 the player touch layer is a sibling of the video container.
        // Put the notice above it, leaving the native back/home/overflow targets free.
        int id = activity.getResources().getIdentifier(
                boundHost ? "reveal_placeholder" : "videoview_container_page", "id", "tv.danmaku.bili");
        View found = activity.findViewById(id);
        if (!(found instanceof FrameLayout)) return false;
        FrameLayout host = (FrameLayout) found;
        View existing = host.findViewWithTag(TAG);
        MemoryCache.Lookup<Notice> lookup = NOTICES.get(aid);
        if (!lookup.present || lookup.value == null) {
            if (existing != null) host.removeView(existing);
            return lookup.present;
        }
        Notice notice = lookup.value;
        TextView banner;
        if (existing instanceof TextView) banner = (TextView) existing;
        else {
            banner = new TextView(activity);
            banner.setTag(TAG);
            banner.setTextSize(11);
            banner.setTextColor(Color.WHITE);
            banner.setBackgroundColor(0xdd242424);
            int padding = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            banner.setPadding(padding, padding / 2, padding, padding / 2);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            if (boundHost) {
                params.leftMargin = Math.round(96 * activity.getResources().getDisplayMetrics().density);
                params.rightMargin = Math.round(40 * activity.getResources().getDisplayMetrics().density);
            }
            host.addView(banner, params);
        }
        // 7.42's native player intercepts touches inside this status area. Keep it
        // informational; the existing creator-page charge entry remains available.
        Uri destination = boundHost ? null : safeDestination(notice.link);
        banner.setText(notice.message + (destination == null ? "" : "  ›"));
        banner.setOnClickListener(destination == null ? null : view -> {
            try {
                // Charge links are internal routes, not necessarily exported Android intents.
                ClassLoader loader = activity.getClassLoader();
                Object builder = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "com.bilibili.lib.blrouter.RouteRequest$Builder", loader), destination);
                Object request = XposedHelpers.callMethod(builder, "build");
                Object response = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                        "com.bilibili.lib.blrouter.BLRouter", loader), "routeTo", request, activity);
                if (!(Boolean) XposedHelpers.callMethod(response, "isSuccess"))
                    throw new IllegalStateException("Charge route rejected");
            } catch (RuntimeException failure) {
                XposedBridge.log("preview charge route unavailable");
                Toast.makeText(activity, "暂时无法打开充电页面，可从 UP 主主页查看充电条件",
                        Toast.LENGTH_LONG).show();
            }
        });
        banner.bringToFront();
        return true;
    }

    private static Uri safeDestination(String link) {
        if (link == null || link.isEmpty()) return null;
        Uri uri = Uri.parse(link);
        String host = uri.getHost();
        // 7.4's native creator-page entry uses /index. The newer /pay H5 assumes
        // a container with different top-bar handling and places its close button
        // under the status bar in this legacy Activity. Reuse the native entry;
        // keep the server's creator, tier, source and permission-related parameters.
        if ("https".equals(uri.getScheme()) && "www.bilibili.com".equals(host)
                && "/h5/upower/pay".equals(uri.getPath())) {
            uri = uri.buildUpon().path("/h5/upower/index").build();
        }
        if ("bilibili".equals(uri.getScheme())) return uri;
        if ("https".equals(uri.getScheme()) && host != null
                && (host.equals("bilibili.com") || host.endsWith(".bilibili.com"))) return uri;
        return null;
    }

    private static long videoId(Activity activity) {
        Long bound = BOUND_IDENTITIES.get(activity);
        if (bound != null) return bound;
        // BV search routes keep a BV identifier in the Intent. Use the identity
        // already resolved by the native detail model, also covering in-page changes.
        try {
            Object model = XposedHelpers.getObjectField(activity, "y");
            if (model != null) {
                Object network = XposedHelpers.callMethod(model, "V1");
                Object detail = XposedHelpers.callMethod(network, "A1");
                if (detail != null) {
                    long aid = (Long) XposedHelpers.getObjectField(detail, "mAvid");
                    if (aid > 0) return aid;
                }
            }
        } catch (RuntimeException ignored) {
            // The model may not be ready during the first resume callback.
        }
        Intent intent = activity.getIntent();
        Uri uri = intent == null ? null : intent.getData();
        try {
            if (uri != null && "video".equals(uri.getHost())) return Long.parseLong(uri.getLastPathSegment());
            if (uri != null && uri.getQueryParameter("avid") != null)
                return Long.parseLong(uri.getQueryParameter("avid"));
            if (intent != null) {
                long aid = intent.getLongExtra("avid", 0);
                if (aid != 0) return aid;
                return intent.getLongExtra("aid", 0);
            }
        } catch (RuntimeException ignored) {
            // Non-AV routes remain unmodified until a supported identity is available.
        }
        return 0;
    }

    private static final class Notice {
        final String message;
        final String link;
        Notice(String message, String link) { this.message = message; this.link = link; }
    }
}
