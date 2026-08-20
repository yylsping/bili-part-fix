package io.github.yylsping.bilipartfix;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restores picture comments in Bilibili 7.4.0 without embedding a WebView.
 *
 * <p>7.4.0's generated reply protobuf stops at Content field 8. Picture comments
 * use fields 9 (pictures) and 10 (picture_scale), so the old renderer only gets
 * the server's upgrade marker. For marker-bearing comments (plus the small
 * pinned/copy-view-model fallback), this bridge obtains the same public reply
 * through x/v2/reply/detail and lets the app's own image components render the
 * returned CDN URLs. Normal comments do no extra I/O.</p>
 */
final class CommentImageFix {
    private static final String VIEW_MODEL =
            "com.bilibili.app.comm.comment2.comments.viewmodel.v0";
    private static final String BILI_COMMENT =
            "com.bilibili.app.comm.comment2.model.BiliComment";
    private static final String ADAPTER =
            "com.bilibili.app.comm.comment2.comments.vvmadapter.v1";
    private static final String BINDING = "com.bilibili.app.comment2.databinding.w";
    private static final String NORMAL_HOLDER =
            "com.bilibili.app.comm.comment2.comments.view.viewholder.PrimaryCommentNormalViewHolder";
    private static final String REPLY_HOLDER =
            "com.bilibili.app.comm.comment2.comments.view.viewholder.PrimaryCommentNormalWithReplyViewHolder";
    private static final String BILI_IMAGE_VIEW = "com.bilibili.lib.image2.view.BiliImageView";
    private static final String BILI_IMAGE_LOADER = "com.bilibili.lib.image2.BiliImageLoader";
    private static final String IMAGE_VIEWER_MODEL =
            "com.bilibili.lib.imageviewer.ImageViewerModel";
    private static final String IMAGE_ITEM =
            "com.bilibili.lib.imageviewer.data.ImageItem";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private static final Map<Object, Object> COMMENTS_BY_VIEW_MODEL =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, String> BOUND_KEYS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, String> RENDERED_KEYS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> BIND_GENERATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, SlotState> SLOT_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<TextView, MarkerWatchState> MARKER_WATCHERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final MemoryCache<String, ReplyPictures> CACHE =
            new MemoryCache<>(128, 30L * 60L * 1000L, 45L * 1000L);
    private static final MemoryCache<String, Boolean> RESTORE_LOGS =
            new MemoryCache<>(256, 30L * 60L * 1000L, 30L * 60L * 1000L);
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, List<RenderTarget>> PENDING_TARGETS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, IdentityFields> IDENTITY_FIELDS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> IMAGE_INTO_METHODS =
            new ConcurrentHashMap<>();
    private static final ExecutorService NETWORK = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BiliPartFix-comment-image");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static ClassLoader appClassLoader;
    private static Class<?> biliCommentClass;
    private static Class<?> biliImageViewClass;

    private CommentImageFix() {}

    static void install(ClassLoader classLoader) {
        try {
            appClassLoader = classLoader;
            Class<?> viewModelClass = XposedHelpers.findClass(VIEW_MODEL, classLoader);
            biliCommentClass = XposedHelpers.findClass(BILI_COMMENT, classLoader);
            biliImageViewClass = XposedHelpers.findClass(BILI_IMAGE_VIEW, classLoader);

            XposedBridge.hookAllConstructors(viewModelClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object comment = null;
                    Object sourceViewModel = null;
                    for (Object arg : param.args) {
                        if (arg != null && biliCommentClass.isInstance(arg)) {
                            comment = arg;
                            break;
                        }
                        if (arg != null && viewModelClass.isInstance(arg)) {
                            sourceViewModel = arg;
                        }
                    }
                    if (comment == null && sourceViewModel != null) {
                        comment = COMMENTS_BY_VIEW_MODEL.get(sourceViewModel);
                    }
                    if (comment != null) {
                        COMMENTS_BY_VIEW_MODEL.put(param.thisObject, comment);
                    }
                }
            });

            Class<?> bindingClass = XposedHelpers.findClass(BINDING, classLoader);
            Class<?> adapterClass = XposedHelpers.findClass(ADAPTER, classLoader);
            XC_MethodHook bindHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && param.args[1] != null) {
                        onBound(param.thisObject, param.args[1]);
                    }
                }
            };
            // JADX presents this typed overload as d2(), but its real dex name in
            // 7.4.0 is p2(); d2(ViewDataBinding, a0) is only the bridge method.
            XposedHelpers.findAndHookMethod(
                    NORMAL_HOLDER, classLoader, "p2", bindingClass, adapterClass, bindHook);
            XposedHelpers.findAndHookMethod(
                    REPLY_HOLDER, classLoader, "p2", bindingClass, adapterClass, bindHook);
            XposedBridge.log("BiliPartFix: picture-comment compatibility hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: picture-comment hook installation failed: "
                    + throwable);
        }
    }

    private static void onBound(Object holder, Object adapter) {
        try {
            View itemView = (View) XposedHelpers.getObjectField(holder, "itemView");
            int messageId = id(itemView, "comment_message");
            View rawMessage = itemView.findViewById(messageId);
            if (!(rawMessage instanceof TextView)) return;
            TextView messageView = (TextView) rawMessage;
            Object viewModel = XposedHelpers.callMethod(adapter, "q0");
            Object comment = COMMENTS_BY_VIEW_MODEL.get(viewModel);
            boolean pinnedOrCopied = comment == null;
            boolean rawMarker = containsUpgradeMarker(rawCommentMessage(comment));
            CommentIdentity identity;
            if (comment != null) {
                identity = new CommentIdentity(
                        XposedHelpers.getLongField(comment, "mRpId"),
                        XposedHelpers.getLongField(comment, "mOid"),
                        XposedHelpers.getLongField(comment, "mRootId"),
                        XposedHelpers.getIntField(comment, "mType"));
            } else {
                // Pinned comments can arrive through a prebuilt/copy view-model
                // whose constructor did not expose the original BiliComment.
                identity = identityFromViewModel(viewModel);
            }
            if (identity == null) return;
            long rpid = identity.rpid;
            long oid = identity.oid;
            long root = identity.root;
            int type = identity.type;
            if (rpid <= 0 || oid <= 0 || type <= 0) return;
            if (root <= 0) root = rpid;

            String key = type + ":" + oid + ":" + root + ":" + rpid;
            String previousKey = BOUND_KEYS.put(itemView, key);
            int generation = bindingGeneration(itemView, previousKey, key);
            if (previousKey != null && !previousKey.equals(key)) {
                clearMarkerWatcher(messageView);
                restoreOriginalSlots(itemView);
            }
            // p2() can bind the same comment into the same holder again after
            // the app has hidden/cleared its picture slots. Treat every bind as
            // a new render opportunity; the memory cache makes this local-only.
            RENDERED_KEYS.remove(itemView);
            MemoryCache.Lookup<ReplyPictures> cached = CACHE.get(key);
            if (cached.present) {
                if (cached.value != null) {
                    resetImageSlots(itemView);
                    renderIfCurrent(messageView, itemView, key, generation, cached.value);
                }
                return;
            }
            if (!rawMarker && !containsUpgradeMarker(messageView.getText())
                    && !pinnedOrCopied) {
                // CommentMessageWidget applies line collapsing after the holder's
                // p2() bind returns. On a fresh detail page the server marker can
                // therefore be absent here and appear a frame later. Retain the
                // row identity and perform bounded, recycling-safe checks.
                if (!key.equals(RENDERED_KEYS.get(itemView))) {
                    scheduleMarkerChecks(messageView, itemView, key,
                            generation, type, oid, root, rpid);
                }
                return;
            }
            resetImageSlots(itemView);
            requestOrRender(messageView, itemView, key, generation,
                    type, oid, root, rpid);
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: picture-comment bind failed: " + throwable);
        }
    }

    private static void scheduleMarkerChecks(TextView messageView, View itemView,
                                             String key, int generation, int type, long oid,
                                             long root, long rpid) {
        long[] delays = {32L, 120L, 400L, 1000L};
        for (long delay : delays) {
            MAIN.postDelayed(() -> {
                if (key.equals(BOUND_KEYS.get(itemView))
                        && generation == currentGeneration(itemView)
                        && !key.equals(RENDERED_KEYS.get(itemView))
                        && containsUpgradeMarker(messageView.getText())) {
                    requestOrRender(messageView, itemView, key, generation,
                            type, oid, root, rpid);
                }
            }, delay);
        }
    }

    private static void requestOrRender(TextView messageView, View itemView,
                                        String key, int generation, int type, long oid,
                                        long root, long rpid) {
        MemoryCache.Lookup<ReplyPictures> cached = CACHE.get(key);
        if (cached.present) {
            if (cached.value != null) {
                renderIfCurrent(messageView, itemView, key, generation, cached.value);
            }
            return;
        }
        queueRenderTarget(key, messageView, itemView, generation);
        // Close the race where the worker publishes/removes its pending list
        // just as another bind appends a target. If the result appeared after
        // the first lookup, deliver every late target immediately.
        cached = CACHE.get(key);
        if (cached.present) {
            if (cached.value != null) {
                deliverRenderTargets(key, cached.value);
            } else {
                PENDING_TARGETS.remove(key);
            }
            return;
        }
        if (!IN_FLIGHT.add(key)) return;
        NETWORK.execute(() -> {
            try {
                ReplyPictures result = fetch(type, oid, root, rpid);
                if (result != null && !result.pictures.isEmpty()) {
                    CACHE.put(key, result);
                    deliverRenderTargets(key, result);
                } else {
                    CACHE.put(key, null);
                    PENDING_TARGETS.remove(key);
                }
            } catch (Throwable throwable) {
                PENDING_TARGETS.remove(key);
                XposedBridge.log("BiliPartFix: picture lookup failed for rpid=" + rpid
                        + ": " + throwable.getClass().getSimpleName());
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    private static void queueRenderTarget(String key, TextView messageView, View itemView,
                                          int generation) {
        List<RenderTarget> targets = PENDING_TARGETS.computeIfAbsent(
                key, ignored -> Collections.synchronizedList(new ArrayList<>()));
        targets.add(new RenderTarget(messageView, itemView, generation));
    }

    private static void deliverRenderTargets(String key, ReplyPictures result) {
        List<RenderTarget> targets = PENDING_TARGETS.remove(key);
        if (targets == null) return;
        List<RenderTarget> snapshot;
        synchronized (targets) {
            snapshot = new ArrayList<>(targets);
        }
        long[] delays = {0L, 80L, 300L};
        for (RenderTarget target : snapshot) {
            for (long delay : delays) {
                MAIN.postDelayed(() -> target.render(key, result), delay);
            }
        }
    }

    private static CharSequence rawCommentMessage(Object comment) {
        if (comment == null) return null;
        try {
            Object content = XposedHelpers.getObjectField(comment, "mContent");
            return content == null ? null
                    : (CharSequence) XposedHelpers.getObjectField(content, "mMsg");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: unable to inspect raw comment message: "
                    + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static CommentIdentity identityFromViewModel(Object viewModel) {
        if (viewModel == null) return null;
        try {
            IdentityFields fields = IDENTITY_FIELDS.computeIfAbsent(
                    viewModel.getClass(), CommentImageFix::resolveIdentityFields);
            Object meta = fields.meta == null ? null : fields.meta.get(viewModel);
            Object commentContext = fields.context == null
                    ? null : fields.context.get(viewModel);
            if (meta == null || commentContext == null) return null;
            long rpid = XposedHelpers.getLongField(meta, "a");
            long root = XposedHelpers.getLongField(meta, "c");
            long oid = ((Number) XposedHelpers.callMethod(commentContext, "getOid"))
                    .longValue();
            int type = ((Number) XposedHelpers.callMethod(commentContext, "getType"))
                    .intValue();
            return new CommentIdentity(rpid, oid, root, type);
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: unable to derive pinned comment identity: "
                    + throwable.getClass().getSimpleName());
            return null;
        }
    }

    private static IdentityFields resolveIdentityFields(Class<?> type) {
        Field meta = null;
        Field context = null;
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.equals(VIEW_MODEL + "$k")) meta = field;
                else if (typeName.equals(
                        "com.bilibili.app.comm.comment2.CommentContext")) context = field;
            }
            current = current.getSuperclass();
        }
        if (meta != null) meta.setAccessible(true);
        if (context != null) context.setAccessible(true);
        return new IdentityFields(meta, context);
    }

    private static void renderIfCurrent(TextView messageView, View itemView,
                                        String key, int generation, ReplyPictures result) {
        if (!key.equals(BOUND_KEYS.get(itemView))
                || generation != currentGeneration(itemView)
                || key.equals(RENDERED_KEYS.get(itemView))) {
            return;
        }
        try {
            View[] slots = ensureBiliImageSlots(itemView);
            resetImageSlots(itemView);
            int availableWidth = messageView.getWidth();
            if (availableWidth <= 0) {
                availableWidth = messageView.getResources().getDisplayMetrics().widthPixels
                        - dp(messageView, 72);
            }
            int maxHeight = dp(messageView, 480);
            if (slots[0] == null) throw new IllegalStateException("picture slots unavailable");
            int count = Math.min(result.pictures.size(), slots.length);
            configureSlots(slots, count, availableWidth, maxHeight, result.pictures,
                    itemView);
            for (int i = 0; i < count; i++) {
                Picture picture = result.pictures.get(i);
                View image = slots[i];
                int clickedIndex = i;
                image.setBackgroundColor(Color.rgb(238, 238, 238));
                image.setVisibility(View.VISIBLE);
                image.setContentDescription(result.pictures.size() > slots.length
                        ? "共" + result.pictures.size() + "张图片，点击查看全部"
                        : "评论图片，点击查看大图");
                image.setOnClickListener(
                        v -> showNativePictureViewer(messageView, result, clickedIndex));
                loadWithBiliImageLoader(messageView, image, picture.url);
            }
            // Do not remove the server marker until every image request has
            // been accepted by the app loader (fail closed on loader mismatch).
            stripUpgradeMarker(messageView);
            messageView.postDelayed(() -> stripUpgradeMarker(messageView), 120);
            messageView.postDelayed(() -> stripUpgradeMarker(messageView), 500);
            installMarkerWatcher(messageView, itemView, key, generation);
            itemView.requestLayout();
            itemView.invalidate();
            RENDERED_KEYS.put(itemView, key);
            if (!RESTORE_LOGS.get(key).present) {
                RESTORE_LOGS.put(key, Boolean.TRUE);
                XposedBridge.log("restored " + result.pictures.size()
                        + " picture(s) for rpid=" + result.rpid);
            }
        } catch (Throwable throwable) {
            restoreOriginalSlots(itemView);
            XposedBridge.log("BiliPartFix: picture render failed: " + throwable);
        }
    }

    private static View[] ensureBiliImageSlots(View itemView) {
        SlotState existing = SLOT_STATES.get(itemView);
        if (existing != null) return existing.replacements;
        String[] names = {"pre_triple_image_first", "pre_triple_image_second",
                "pre_triple_image_third"};
        View[] originals = new View[names.length];
        View[] replacements = new View[names.length];
        for (int i = 0; i < names.length; i++) {
            View original = itemView.findViewById(id(itemView, names[i]));
            if (original == null || !(original.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup) original.getParent();
            int index = parent.indexOfChild(original);
            ViewGroup.LayoutParams params = original.getLayoutParams();
            parent.removeViewAt(index);
            View replacement = (View) XposedHelpers.newInstance(
                    biliImageViewClass, itemView.getContext());
            replacement.setId(original.getId());
            replacement.setVisibility(View.GONE);
            parent.addView(replacement, index, params);
            originals[i] = original;
            replacements[i] = replacement;
        }
        SlotState state = new SlotState(originals, replacements);
        SLOT_STATES.put(itemView, state);
        return replacements;
    }

    private static void restoreOriginalSlots(View itemView) {
        RENDERED_KEYS.remove(itemView);
        SlotState state = SLOT_STATES.remove(itemView);
        if (state == null) return;
        for (int i = 0; i < state.replacements.length; i++) {
            View replacement = state.replacements[i];
            View original = state.originals[i];
            if (replacement == null || original == null
                    || !(replacement.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup) replacement.getParent();
            int index = parent.indexOfChild(replacement);
            parent.removeViewAt(index);
            parent.addView(original, index, original.getLayoutParams());
        }
        itemView.requestLayout();
    }

    private static int bindingGeneration(View itemView, String previousKey, String key) {
        synchronized (BIND_GENERATIONS) {
            int current = BIND_GENERATIONS.getOrDefault(itemView, 0);
            if (previousKey == null || !previousKey.equals(key)) {
                current++;
                BIND_GENERATIONS.put(itemView, current);
            }
            return current;
        }
    }

    private static int currentGeneration(View itemView) {
        synchronized (BIND_GENERATIONS) {
            return BIND_GENERATIONS.getOrDefault(itemView, -1);
        }
    }

    private static void configureSlots(View[] slots, int count, int availableWidth,
                                       int maxHeight, List<Picture> pictures, View itemView) {
        int firstId = id(itemView, "pre_triple_image_first");
        int secondId = id(itemView, "pre_triple_image_second");
        int thirdId = id(itemView, "pre_triple_image_third");
        int height;
        if (count == 1) {
            Picture p = pictures.get(0);
            height = p.width > 0 && p.height > 0
                    ? Math.round(availableWidth * (p.height / (float) p.width))
                    : availableWidth;
            height = Math.max(dp(itemView, 120), Math.min(height, maxHeight));
        } else {
            height = Math.max(dp(itemView, 100), (availableWidth - dp(itemView, 8)) / count);
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            ViewGroup.LayoutParams params = slots[i].getLayoutParams();
            params.width = 0;
            params.height = height;
            try {
                XposedHelpers.setObjectField(params, "dimensionRatio", null);
                if (i == 0) {
                    XposedHelpers.setIntField(params, "rightToLeft", count >= 2 ? secondId : -1);
                    XposedHelpers.setIntField(params, "rightToRight", count == 1 ? 0 : -1);
                } else if (i == 1) {
                    XposedHelpers.setIntField(params, "leftToRight", firstId);
                    XposedHelpers.setIntField(params, "rightToLeft", count >= 3 ? thirdId : -1);
                    XposedHelpers.setIntField(params, "rightToRight", count == 2 ? 0 : -1);
                } else {
                    XposedHelpers.setIntField(params, "leftToRight", secondId);
                    XposedHelpers.setIntField(params, "rightToRight", 0);
                }
            } catch (Throwable ignored) {
                // The XML constraints are already a valid three-slot fallback.
            }
            slots[i].setLayoutParams(params);
        }
    }

    private static void resetImageSlots(View itemView) {
        String[] names = {"pre_triple_image_first", "pre_triple_image_second",
                "pre_triple_image_third"};
        for (String name : names) {
            View slot = itemView.findViewById(id(itemView, name));
            if (slot == null) continue;
            slot.setVisibility(View.GONE);
            slot.setOnClickListener(null);
            slot.setContentDescription(null);
            try {
                XposedHelpers.callMethod(slot, "setImageDrawable", (Object) null);
            } catch (Throwable ignored) {
                // Visibility reset is sufficient if this method is renamed.
            }
        }
    }

    private static void showNativePictureViewer(View anchor, ReplyPictures result,
                                                int clickedIndex) {
        try {
            Activity activity = findActivity(anchor.getContext());
            if (activity == null) throw new IllegalStateException("activity context unavailable");
            Class<?> imageItemClass = XposedHelpers.findClass(IMAGE_ITEM, appClassLoader);
            List<Object> items = new ArrayList<>(result.pictures.size());
            for (Picture picture : result.pictures) {
                String url = normalizeUrl(picture.url);
                items.add(XposedHelpers.newInstance(imageItemClass,
                        url, null, url, url, picture.width, picture.height, 0));
            }
            Class<?> modelClass = XposedHelpers.findClass(IMAGE_VIEWER_MODEL, appClassLoader);
            Object model = XposedHelpers.newInstance(modelClass, activity);
            XposedHelpers.callMethod(model, "d", items);
            XposedHelpers.callMethod(model, "g", Math.max(0,
                    Math.min(clickedIndex, items.size() - 1)));
            XposedHelpers.callMethod(model, "f");
            XposedHelpers.callMethod(model, "c");
            XposedHelpers.callMethod(model, "e");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: native picture viewer failed: " + throwable);
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static String normalizeUrl(String rawUrl) {
        return rawUrl.startsWith("http://")
                ? "https://" + rawUrl.substring("http://".length()) : rawUrl;
    }

    private static void loadWithBiliImageLoader(View contextView, View image, String rawUrl)
            throws Exception {
        String url = normalizeUrl(rawUrl);
        Class<?> loaderClass = XposedHelpers.findClass(BILI_IMAGE_LOADER, appClassLoader);
        Object loader = XposedHelpers.getStaticObjectField(loaderClass, "INSTANCE");
        Object builder = XposedHelpers.callMethod(loader, "with", contextView.getContext());
        Object afterUrl = XposedHelpers.callMethod(builder, "url", url);
        if (afterUrl != null) builder = afterUrl;
        try {
            Object afterCache = XposedHelpers.callMethod(builder, "smallCacheStrategy");
            if (afterCache != null) builder = afterCache;
        } catch (Throwable ignored) {
            // Older image-loader variants may not expose this fluent option.
        }
        // XposedHelpers' overload resolver treats the concrete RoundImageView
        // class as an exact signature here. Resolve the inherited BiliImageView
        // parameter using normal assignability instead.
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

    private static ReplyPictures fetch(int type, long oid, long root, long rpid)
            throws Exception {
        String endpoint = "https://api.bilibili.com/x/v2/reply/detail?type=" + type
                + "&oid=" + oid + "&root=" + root + "&ps=20&pn=1";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "BiliPartFix/1.5 (Android)");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] bytes = readBounded(connection);
            JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (envelope.optInt("code", -1) != 0) return null;
            JSONObject data = envelope.optJSONObject("data");
            JSONObject reply = findReply(data == null ? null : data.optJSONObject("root"), rpid);
            if (reply == null && data != null) {
                reply = findReply(data.optJSONArray("replies"), rpid);
            }
            if (reply == null) return null;
            JSONObject content = reply.optJSONObject("content");
            JSONArray picturesJson = content == null ? null : content.optJSONArray("pictures");
            if (picturesJson == null || picturesJson.length() == 0) return null;
            List<Picture> pictures = new ArrayList<>();
            for (int i = 0; i < picturesJson.length() && i < 9; i++) {
                JSONObject p = picturesJson.optJSONObject(i);
                if (p == null) continue;
                String url = p.optString("img_src", "");
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
                pictures.add(new Picture(url, p.optInt("img_width"),
                        p.optInt("img_height")));
            }
            return pictures.isEmpty() ? null : new ReplyPictures(rpid, pictures);
        } finally {
            connection.disconnect();
        }
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
                    throw new IllegalStateException("reply response too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static JSONObject findReply(JSONObject reply, long rpid) {
        if (reply == null) return null;
        if (reply.optLong("rpid") == rpid) return reply;
        return findReply(reply.optJSONArray("replies"), rpid);
    }

    private static JSONObject findReply(JSONArray replies, long rpid) {
        if (replies == null) return null;
        for (int i = 0; i < replies.length(); i++) {
            JSONObject found = findReply(replies.optJSONObject(i), rpid);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean containsUpgradeMarker(CharSequence value) {
        if (value == null) return false;
        String text = value.toString();
        return text.contains("请升级到App最新版本查看图文评论")
                || (text.contains("请升级") && text.contains("查看图文评论"))
                // CommentMessageWidget collapses long text before our after-hook,
                // and can leave as little as "[请升" plus its 展开 control.
                // The leading bracket keeps this short fallback specific to the
                // server-injected marker instead of ordinary prose.
                || text.contains("[请升");
    }

    private static void stripUpgradeMarker(TextView view) {
        CharSequence value = view.getText();
        CharSequence stripped = stripUpgradeMarker(value);
        if (stripped != value) {
            // CommentMessageWidget inherits ImageSpannableTextView. Its setText()
            // override detaches every ImageSpan from the old text, but plain
            // TextView#setText does not run ImageSpannableTextView#onAttach for
            // the replacement text. That leaves emote spans showing their TV
            // placeholder until RecyclerView detaches/reattaches the row. Keep
            // the spans in a spannable buffer and immediately restart their
            // normal image-loading lifecycle after removing the server marker.
            view.setText(stripped, TextView.BufferType.SPANNABLE);
            try {
                XposedHelpers.callMethod(view, "onAttach");
            } catch (Throwable throwable) {
                // The marker is already removed and ordinary text is intact.
                // Log the compatibility mismatch instead of failing rendering.
                XposedBridge.log("BiliPartFix: unable to reattach comment emotes: "
                        + throwable.getClass().getSimpleName());
            }
        }
        CharSequence description = view.getContentDescription();
        CharSequence strippedDescription = stripUpgradeMarker(description);
        if (strippedDescription != description) {
            view.setContentDescription(strippedDescription);
        }
    }

    private static void installMarkerWatcher(TextView messageView, View itemView,
                                             String key, int generation) {
        synchronized (MARKER_WATCHERS) {
            MarkerWatchState existing = MARKER_WATCHERS.get(messageView);
            if (existing != null && existing.matches(key, generation)) return;
            if (existing != null) {
                messageView.removeOnLayoutChangeListener(existing.listener);
            }
            View.OnLayoutChangeListener listener = (view, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> {
                if (key.equals(BOUND_KEYS.get(itemView))
                        && generation == currentGeneration(itemView)) {
                    MemoryCache.Lookup<ReplyPictures> cached = CACHE.get(key);
                    if (cached.present && cached.value != null) {
                        stripUpgradeMarker(messageView);
                    }
                }
            };
            messageView.addOnLayoutChangeListener(listener);
            MARKER_WATCHERS.put(messageView,
                    new MarkerWatchState(key, generation, listener));
        }
    }

    private static void clearMarkerWatcher(TextView messageView) {
        synchronized (MARKER_WATCHERS) {
            MarkerWatchState existing = MARKER_WATCHERS.remove(messageView);
            if (existing != null) {
                messageView.removeOnLayoutChangeListener(existing.listener);
            }
        }
    }

    private static CharSequence stripUpgradeMarker(CharSequence value) {
        if (value == null) return value;
        String text = value.toString();
        int coreStart = text.indexOf("请升级到App最新版本查看图文评论");
        if (coreStart < 0) {
            int upgrade = text.indexOf("请升级");
            if (upgrade < 0) {
                // A fresh holder may already have been line-collapsed to
                // "[请升\n展开". Treat only this bracketed fragment as
                // the marker; a later full bind still follows the normal path.
                int partial = text.indexOf("[请升");
                if (partial < 0) return value;
                coreStart = partial + 1;
                upgrade = coreStart;
            }
            int picture = text.indexOf("查看图文评论", upgrade);
            // The collapsed widget replaces the tail with a newline + 展开.
            // A leading '[' plus the stable upgrade prefix is sufficient here.
            if (picture < 0 && (upgrade == 0 || text.charAt(upgrade - 1) != '[')) {
                return value;
            }
            coreStart = upgrade;
        }
        int start = coreStart;
        if (start > 0 && text.charAt(start - 1) == '[') start--;
        int end = text.indexOf(']', coreStart);
        if (end < 0) {
            int expand = text.indexOf("展开", coreStart);
            end = expand < 0 ? text.length() : expand;
            while (end > coreStart && Character.isWhitespace(text.charAt(end - 1))) end--;
        } else {
            end++;
        }
        while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
        SpannableStringBuilder builder = new SpannableStringBuilder(value);
        builder.delete(start, Math.min(end, builder.length()));
        return builder;
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", "tv.danmaku.bili");
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

    private static final class ReplyPictures {
        final long rpid;
        final List<Picture> pictures;

        ReplyPictures(long rpid, List<Picture> pictures) {
            this.rpid = rpid;
            this.pictures = pictures;
        }
    }

    private static final class CommentIdentity {
        final long rpid;
        final long oid;
        final long root;
        final int type;

        CommentIdentity(long rpid, long oid, long root, int type) {
            this.rpid = rpid;
            this.oid = oid;
            this.root = root;
            this.type = type;
        }
    }

    private static final class SlotState {
        final View[] originals;
        final View[] replacements;

        SlotState(View[] originals, View[] replacements) {
            this.originals = originals;
            this.replacements = replacements;
        }
    }

    private static final class IdentityFields {
        final Field meta;
        final Field context;

        IdentityFields(Field meta, Field context) {
            this.meta = meta;
            this.context = context;
        }
    }

    private static final class RenderTarget {
        final WeakReference<TextView> messageView;
        final WeakReference<View> itemView;
        final int generation;

        RenderTarget(TextView messageView, View itemView, int generation) {
            this.messageView = new WeakReference<>(messageView);
            this.itemView = new WeakReference<>(itemView);
            this.generation = generation;
        }

        void render(String key, ReplyPictures result) {
            TextView message = messageView.get();
            View item = itemView.get();
            if (message != null && item != null) {
                renderIfCurrent(message, item, key, generation, result);
            }
        }
    }

    private static final class MarkerWatchState {
        final String key;
        final int generation;
        final View.OnLayoutChangeListener listener;

        MarkerWatchState(String key, int generation, View.OnLayoutChangeListener listener) {
            this.key = key;
            this.generation = generation;
            this.listener = listener;
        }

        boolean matches(String otherKey, int otherGeneration) {
            return generation == otherGeneration && key.equals(otherKey);
        }
    }
}
