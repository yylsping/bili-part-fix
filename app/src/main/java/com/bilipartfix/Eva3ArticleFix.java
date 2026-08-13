package com.bilipartfix;

import java.lang.reflect.Method;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

/** Renders EVA3/Opus payloads that Bilibili 7.4.0 only exposes as a dead jump. */
final class Eva3ArticleFix {
    private static final String CLIENT = "com.bilibili.column.web.ColumnWebView$b";
    private static final String WEB_VIEW = "com.bilibili.app.comm.bh.BiliWebView";
    private static final String CALLBACK = "com.bilibili.app.comm.bh.interfaces.h";

    // The article HTML already embeds the complete payload in __INITIAL_STATE__.
    private static final String SCRIPT = "(function(){try{"
            + "var s=window.__INITIAL_STATE__,r=s&&s.readInfo;"
            + "if(!r||r.type!==4||!r.opus||!r.opus.content)return;"
            + "var root=document.querySelector('.eva3-content');"
            + "if(!root||root.getAttribute('data-bili740-fixed')==='1')return;"
            + "var ps=r.opus.content.paragraphs||[];if(!ps.length)return;"
            + "var dark=(new URLSearchParams(location.search).get('theme')==='2')"
            + "||(window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches);"
            + "var box=document.createElement('div');box.className='bili740-eva3-body';"
            + "box.style.cssText='padding:8px 18px 26px;color:'+(dark?'#e3e5e7':'#18191c')"
            + "+';font-size:17px;line-height:1.75;word-break:break-word;';"
            + "function words(p){var ns=p&&p.text&&p.text.nodes||[],f=document.createDocumentFragment();"
            + "ns.forEach(function(n){var w=n.word;if(!w)return;var x=document.createElement('span');"
            + "x.textContent=w.words||'';var st=w.style||{};if(st.bold)x.style.fontWeight='700';"
            + "if(st.italic)x.style.fontStyle='italic';if(st.strikethrough)x.style.textDecoration='line-through';"
            + "if(w.font_size)x.style.fontSize=w.font_size+'px';"
            + "var c=dark?(w.dark_color||''):(w.color||'');if(c)x.style.color=c;f.appendChild(x);});return f;}"
            + "ps.forEach(function(p){if(!p)return;var e;"
            + "if(p.para_type===2&&p.pic&&p.pic.pics){e=document.createElement('div');"
            + "e.style.cssText='margin:14px 0 18px;';p.pic.pics.forEach(function(pic){"
            + "var im=document.createElement('img'),u=pic.url||'';if(u.indexOf('http:')===0)u='https:'+u.slice(5);"
            + "im.src=u;im.loading='lazy';im.style.cssText='display:block;width:100%;height:auto;border-radius:4px;margin:8px 0;';"
            + "e.appendChild(im);if(pic.comment){var cap=document.createElement('div');cap.textContent=pic.comment;"
            + "cap.style.cssText='font-size:13px;opacity:.65;text-align:center;';e.appendChild(cap);}});"
            + "}else if(p.para_type===3){e=document.createElement('hr');e.style.cssText='border:0;border-top:1px solid '+(dark?'#3a3d42':'#e3e5e7')+';margin:22px 0;';"
            + "}else{e=document.createElement(p.para_type===4?'blockquote':'div');e.appendChild(words(p));"
            + "e.style.cssText='margin:0 0 14px;white-space:pre-wrap;';if(p.para_type===4)e.style.cssText+='padding-left:12px;border-left:3px solid #fb7299;opacity:.9;';}"
            + "box.appendChild(e);});root.innerHTML='';root.appendChild(box);root.setAttribute('data-bili740-fixed','1');"
            + "}catch(e){console.error('BiliPartFix EVA3',e);}})();";

    private Eva3ArticleFix() {}

    static void install(ClassLoader classLoader) {
        try {
            Class<?> client = XposedHelpers.findClass(CLIENT, classLoader);
            Class<?> baseClient = XposedHelpers.findClass(
                    "com.bilibili.column.web.y", classLoader);
            Class<?> webView = XposedHelpers.findClass(WEB_VIEW, classLoader);
            Class<?> callback = XposedHelpers.findClass(CALLBACK, classLoader);
            Method evaluate = webView.getMethod("evaluateJavascript", String.class, callback);
            XposedBridge.hookAllMethods(client, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || param.args[0] == null
                            || !(param.args[1] instanceof String)) return;
                    String url = (String) param.args[1];
                    if (!url.contains("/read/native")) return;
                    try {
                        evaluate.invoke(param.args[0], SCRIPT, null);
                    } catch (Throwable throwable) {
                        XposedBridge.log("BiliPartFix: EVA3 injection failed: " + throwable);
                    }
                }
            });
            XposedBridge.hookAllMethods(baseClient, "onPageFinished", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && param.args[0] != null
                            && param.args[1] instanceof String
                            && ((String) param.args[1]).contains("/read/native")) {
                        injectAfterFinished(evaluate, param.args[0]);
                    }
                }
            });
            XposedBridge.hookAllMethods(webView, "loadUrl", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)
                            || !((String) param.args[0]).contains("/read/native")) return;
                    Object target = param.thisObject;
                    RetryScheduler.schedule(target, "eva-webview",
                            new long[]{800L, 2500L, 6000L}, value -> {
                                inject(evaluate, value);
                                return false;
                            });
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "performResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!"com.bilibili.column.ui.detail.ColumnDetailActivity".equals(
                            param.thisObject.getClass().getName())) return;
                    Activity activity = (Activity) param.thisObject;
                    RetryScheduler.schedule(activity, "eva-activity",
                            new long[]{500L, 1800L, 4500L}, value -> {
                                injectTree(evaluate, webView, value);
                                return false;
                            });
                }
            });
            XposedBridge.log("BiliPartFix: EVA3 article compatibility hook installed");
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: EVA3 hook installation failed: " + throwable);
        }
    }

    private static void inject(Method evaluate, Object target) {
        try {
            evaluate.invoke(target, SCRIPT, null);
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: EVA3 delayed injection failed: " + throwable);
        }
    }

    private static void injectAfterFinished(Method evaluate, Object target) {
        inject(evaluate, target);
    }

    private static void injectTree(Method evaluate, Class<?> webView, Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            injectTree(evaluate, webView, root);
        } catch (Throwable throwable) {
            XposedBridge.log("BiliPartFix: EVA3 view traversal failed: " + throwable);
        }
    }

    private static void injectTree(Method evaluate, Class<?> webView, View view) {
        if (webView.isInstance(view)) {
            inject(evaluate, view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                injectTree(evaluate, webView, group.getChildAt(i));
            }
        }
    }
}
