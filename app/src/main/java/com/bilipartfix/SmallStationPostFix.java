package com.bilipartfix;

import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Restores modern small-station/Opus posts on the 7.4.0 dynamic detail page. */
final class SmallStationPostFix {
    private static final String COMPOSE_ACTIVITY = "com.bilibili.lib.ui.ComposeActivity";
    private static final String DETAIL_FRAGMENT =
            "com.bilibili.bplus.followinglist.detail.DynamicDetailFragment";
    private static final String BILI_ACCOUNTS = "com.bilibili.lib.accounts.BiliAccounts";
    private static final String BILI_IMAGE_VIEW = "com.bilibili.lib.image2.view.BiliImageView";
    private static final String BILI_IMAGE_LOADER = "com.bilibili.lib.image2.BiliImageLoader";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BiliPartFix-small-station");
        thread.setDaemon(true);
        return thread;
    });
    private static final MemoryCache<String, PostContent> CACHE =
            new MemoryCache<>(64, 30L * 60L * 1000L, 45L * 1000L);
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Class<?>, Method> IMAGE_INTO_METHODS =
            new ConcurrentHashMap<>();
    private static final Map<View, String> RENDERED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static ClassLoader appClassLoader;
    private static Class<?> biliImageViewClass;

    private SmallStationPostFix() {}

    static void install(ClassLoader classLoader) {
        try {
            appClassLoader = classLoader;
            biliImageViewClass = XposedHelpers.findClass(BILI_IMAGE_VIEW, classLoader);
            XposedBridge.log("BiliPartFix: small-station post compatibility hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: small-station post hook installation failed: "
                    + throwable);
        }
    }

    static void onActivityResumed(Object activity) {
        if (!COMPOSE_ACTIVITY.equals(activity.getClass().getName())) return;
        RetryScheduler.schedule(activity, "small-station",
                new long[]{250L, 700L, 1500L, 3200L}, SmallStationPostFix::repairActivity);
    }

    private static boolean repairActivity(Object activity) {
        try {
            Object manager = XposedHelpers.callMethod(activity, "getSupportFragmentManager");
            Object value = XposedHelpers.callMethod(manager, "getFragments");
            if (!(value instanceof List)) return false;
            for (Object fragment : (List<?>) value) {
                if (fragment != null && DETAIL_FRAGMENT.equals(fragment.getClass().getName())) {
                    return repairFragment(fragment);
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("small-station activity repair failed", throwable);
        }
        return false;
    }

    private static boolean repairFragment(Object fragment) {
        try {
            View root = (View) XposedHelpers.callMethod(fragment, "getView");
            if (root == null) return false;
            int placeholderId = root.getResources().getIdentifier(
                    "dy_no_content", "id", "tv.danmaku.bili");
            View placeholder = root.findViewById(placeholderId);
            if (!(placeholder instanceof TextView)
                    || !isSmallStationMarker(((TextView) placeholder).getText())) return true;
            if (!(placeholder.getParent() instanceof ViewGroup)) return false;

            Object viewModel = XposedHelpers.getObjectField(fragment, "m");
            String dynamicId = String.valueOf(XposedHelpers.callMethod(viewModel, "i2"));
            if (!dynamicId.matches("[1-9][0-9]{5,19}")) return false;
            ViewGroup host = (ViewGroup) placeholder.getParent();
            if (dynamicId.equals(RENDERED.get(host))) return true;

            MemoryCache.Lookup<PostContent> cached = CACHE.get(dynamicId);
            if (cached.present) {
                if (cached.value != null) render(host, placeholder, dynamicId, cached.value);
                return true;
            }
            if (!IN_FLIGHT.add(dynamicId)) return true;
            Context context = root.getContext().getApplicationContext();
            NETWORK.execute(() -> {
                try {
                    PostContent result = fetch(context, dynamicId);
                    if (result != null && !result.blocks.isEmpty()) {
                        CACHE.put(dynamicId, result);
                        MAIN.post(() -> {
                            if (placeholder.getParent() == host
                                    && isSmallStationMarker(((TextView) placeholder).getText())) {
                                render(host, placeholder, dynamicId, result);
                            }
                        });
                    } else {
                        CACHE.put(dynamicId, null);
                    }
                } catch (Throwable throwable) {
                    XposedBridge.log("BiliPartFix: small-station lookup failed for dynamic="
                            + dynamicId + ": " + throwable.getClass().getSimpleName());
                } finally {
                    IN_FLIGHT.remove(dynamicId);
                }
            });
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log("small-station fragment repair failed", throwable);
            return false;
        }
    }

    private static void render(ViewGroup host, View placeholder, String dynamicId,
                               PostContent content) {
        if (dynamicId.equals(RENDERED.get(host))) return;
        try {
            Context context = host.getContext();
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.START);
            int horizontal = dp(host, 18);
            column.setPadding(horizontal, dp(host, 8), horizontal, dp(host, 18));

            if (!content.title.isEmpty()) {
                TextView title = new TextView(context);
                title.setText(content.title);
                title.setTextSize(21);
                title.setTextColor(resolveTextColor(context, android.R.attr.textColorPrimary,
                        Color.WHITE));
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                titleParams.bottomMargin = dp(host, 12);
                column.addView(title, titleParams);
            }

            int availableWidth = host.getWidth() - horizontal * 2;
            if (availableWidth <= 0) {
                availableWidth = host.getResources().getDisplayMetrics().widthPixels
                        - horizontal * 2;
            }
            for (Block block : content.blocks) {
                if (block.type == Block.TEXT) {
                    TextView text = new TextView(context);
                    text.setText(block.text);
                    text.setTextSize(17);
                    text.setTextColor(resolveTextColor(context,
                            android.R.attr.textColorPrimary, Color.WHITE));
                    text.setLineSpacing(0, 1.25f);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.bottomMargin = dp(host, 14);
                    column.addView(text, params);
                } else if (block.type == Block.IMAGE) {
                    View image = (View) XposedHelpers.newInstance(biliImageViewClass, context);
                    int height = block.width > 0 && block.height > 0
                            ? Math.round(availableWidth * (block.height / (float) block.width))
                            : availableWidth;
                    height = Math.max(dp(host, 140), Math.min(height, dp(host, 760)));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, height);
                    params.bottomMargin = dp(host, 14);
                    image.setLayoutParams(params);
                    image.setBackgroundColor(Color.rgb(40, 40, 40));
                    image.setContentDescription("图文帖子图片，点击查看大图");
                    image.setOnClickListener(v -> showPictureDialog(v, content.pictures));
                    column.addView(image);
                    loadWithBiliImageLoader(host, image, block.url);
                } else {
                    View line = new View(context);
                    line.setBackgroundColor(resolveTextColor(context,
                            android.R.attr.textColorSecondary, Color.GRAY));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(host, 1));
                    params.topMargin = dp(host, 8);
                    params.bottomMargin = dp(host, 18);
                    column.addView(line, params);
                }
            }

            ViewGroup.LayoutParams hostParams = host.getLayoutParams();
            if (hostParams != null) {
                hostParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                host.setLayoutParams(hostParams);
            }
            host.removeAllViews();
            host.setBackground(null);
            host.addView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            RENDERED.put(host, dynamicId);
            host.requestLayout();
            host.invalidate();
            XposedBridge.log("BiliPartFix: restored small-station post dynamic="
                    + dynamicId + ", blocks=" + content.blocks.size());
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: small-station render failed: " + throwable);
        }
    }

    private static PostContent fetch(Context context, String dynamicId) throws Exception {
        String endpoint = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id="
                + dynamicId;
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 BiliDroid/7.4.0");
        connection.setRequestProperty("Referer", "https://www.bilibili.com/");
        String cookies = accountCookies(context);
        if (!cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            JSONObject envelope = new JSONObject(new String(
                    readBounded(connection), StandardCharsets.UTF_8));
            if (envelope.optInt("code", -1) != 0) return null;
            JSONObject data = envelope.optJSONObject("data");
            JSONObject item = data == null ? null : data.optJSONObject("item");
            if (item == null || !dynamicId.equals(item.optString("id_str"))) return null;

            String title = "";
            List<Block> blocks = new ArrayList<>();
            List<Picture> pictures = new ArrayList<>();
            JSONArray modules = item.optJSONArray("modules");
            if (modules == null) return null;
            for (int i = 0; i < modules.length(); i++) {
                JSONObject module = modules.optJSONObject(i);
                if (module == null) continue;
                JSONObject moduleTitle = module.optJSONObject("module_title");
                if (moduleTitle != null && title.isEmpty()) {
                    title = moduleTitle.optString("text", "").trim();
                }
                JSONObject moduleContent = module.optJSONObject("module_content");
                JSONArray paragraphs = moduleContent == null
                        ? null : moduleContent.optJSONArray("paragraphs");
                if (paragraphs == null) continue;
                for (int j = 0; j < paragraphs.length(); j++) {
                    JSONObject paragraph = paragraphs.optJSONObject(j);
                    if (paragraph == null) continue;
                    int type = paragraph.optInt("para_type", 0);
                    if (type == 2) {
                        JSONObject pic = paragraph.optJSONObject("pic");
                        JSONArray values = pic == null ? null : pic.optJSONArray("pics");
                        if (values == null) continue;
                        for (int k = 0; k < values.length(); k++) {
                            JSONObject value = values.optJSONObject(k);
                            if (value == null) continue;
                            String url = value.optString("url", "");
                            if (!isHttpUrl(url)) continue;
                            Picture picture = new Picture(url, value.optInt("width"),
                                    value.optInt("height"));
                            pictures.add(picture);
                            blocks.add(Block.image(picture));
                        }
                    } else if (type == 3) {
                        blocks.add(Block.line());
                    } else {
                        String text = paragraphText(paragraph);
                        if (!text.trim().isEmpty()) blocks.add(Block.text(text));
                    }
                }
            }
            return blocks.isEmpty() ? null : new PostContent(title, blocks, pictures);
        } finally {
            connection.disconnect();
        }
    }

    private static String accountCookies(Context context) {
        try {
            Class<?> accountsClass = XposedHelpers.findClass(BILI_ACCOUNTS, appClassLoader);
            Object accounts = XposedHelpers.callStaticMethod(accountsClass, "get", context);
            Object cookieInfo = XposedHelpers.callMethod(accounts, "getAccountCookie");
            Object rawCookies = XposedHelpers.getObjectField(cookieInfo, "a");
            if (!(rawCookies instanceof List)) return "";
            StringBuilder value = new StringBuilder();
            for (Object cookie : (List<?>) rawCookies) {
                if (cookie == null) continue;
                Object rawName = XposedHelpers.getObjectField(cookie, "a");
                Object rawValue = XposedHelpers.getObjectField(cookie, "b");
                if (!(rawName instanceof String) || !(rawValue instanceof String)) continue;
                if (value.length() > 0) value.append("; ");
                value.append(rawName).append('=').append(rawValue);
            }
            return value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String paragraphText(JSONObject paragraph) {
        JSONObject text = paragraph.optJSONObject("text");
        JSONArray nodes = text == null ? null : text.optJSONArray("nodes");
        if (nodes == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            JSONObject word = node.optJSONObject("word");
            if (word != null) {
                result.append(word.optString("words", ""));
                continue;
            }
            JSONObject rich = node.optJSONObject("rich");
            if (rich != null) result.append(rich.optString("text", ""));
        }
        return result.toString();
    }

    private static byte[] readBounded(HttpURLConnection connection) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("opus response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void loadWithBiliImageLoader(View contextView, View image, String rawUrl)
            throws Exception {
        String url = rawUrl.startsWith("http://")
                ? "https://" + rawUrl.substring("http://".length()) : rawUrl;
        Class<?> loaderClass = XposedHelpers.findClass(BILI_IMAGE_LOADER, appClassLoader);
        Object loader = XposedHelpers.getStaticObjectField(loaderClass, "INSTANCE");
        Object builder = XposedHelpers.callMethod(loader, "with", contextView.getContext());
        Object afterUrl = XposedHelpers.callMethod(builder, "url", url);
        if (afterUrl != null) builder = afterUrl;
        try {
            Object afterCache = XposedHelpers.callMethod(builder, "smallCacheStrategy");
            if (afterCache != null) builder = afterCache;
        } catch (Throwable ignored) {
            // Optional on older image-loader builds.
        }
        Method into = IMAGE_INTO_METHODS.get(builder.getClass());
        if (into == null) {
            into = findIntoMethod(builder.getClass(), image.getClass());
            if (into != null) IMAGE_INTO_METHODS.put(builder.getClass(), into);
        }
        if (into == null) throw new NoSuchMethodException("ImageRequestBuilder.into");
        into.setAccessible(true);
        into.invoke(builder, image);
    }

    private static Method findIntoMethod(Class<?> builderClass, Class<?> imageClass) {
        for (Method method : builderClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ("into".equals(method.getName()) && parameters.length == 1
                    && parameters[0].isAssignableFrom(imageClass)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void showPictureDialog(View anchor, List<Picture> pictures) {
        if (pictures.isEmpty()) return;
        try {
            Dialog dialog = new Dialog(anchor.getContext());
            ScrollView scroll = new ScrollView(anchor.getContext());
            scroll.setBackgroundColor(Color.rgb(20, 20, 20));
            LinearLayout column = new LinearLayout(anchor.getContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            int padding = dp(anchor, 12);
            column.setPadding(padding, padding, padding, padding);
            scroll.addView(column, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int width = anchor.getResources().getDisplayMetrics().widthPixels - padding * 2;
            for (Picture picture : pictures) {
                View image = (View) XposedHelpers.newInstance(
                        biliImageViewClass, anchor.getContext());
                int height = picture.width > 0 && picture.height > 0
                        ? Math.round(width * (picture.height / (float) picture.width)) : width;
                height = Math.max(dp(anchor, 160), Math.min(height, dp(anchor, 900)));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
                params.bottomMargin = padding;
                column.addView(image, params);
                loadWithBiliImageLoader(anchor, image, picture.url);
            }
            dialog.setContentView(scroll);
            dialog.setCanceledOnTouchOutside(true);
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
            }
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: small-station picture dialog failed: " + throwable);
        }
    }

    private static boolean isSmallStationMarker(CharSequence text) {
        return text != null && text.toString().contains("小站图文内容")
                && text.toString().contains("最新版本");
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static int resolveTextColor(Context context, int attribute, int fallback) {
        TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        try {
            return values.getColor(0, fallback);
        } finally {
            values.recycle();
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static final class Picture {
        final String url;
        final int width;
        final int height;

        Picture(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Block {
        static final int TEXT = 1;
        static final int IMAGE = 2;
        static final int LINE = 3;

        final int type;
        final String text;
        final String url;
        final int width;
        final int height;

        private Block(int type, String text, String url, int width, int height) {
            this.type = type;
            this.text = text;
            this.url = url;
            this.width = width;
            this.height = height;
        }

        static Block text(String value) {
            return new Block(TEXT, value, "", 0, 0);
        }

        static Block image(Picture picture) {
            return new Block(IMAGE, "", picture.url, picture.width, picture.height);
        }

        static Block line() {
            return new Block(LINE, "", "", 0, 0);
        }
    }

    private static final class PostContent {
        final String title;
        final List<Block> blocks;
        final List<Picture> pictures;

        PostContent(String title, List<Block> blocks, List<Picture> pictures) {
            this.title = title;
            this.blocks = blocks;
            this.pictures = pictures;
        }
    }
}
