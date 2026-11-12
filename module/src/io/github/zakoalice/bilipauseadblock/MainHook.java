package io.github.zakoalice.bilipauseadblock;

import android.util.Log;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 屏蔽哔哩哔哩「暂停视频后弹出广告」的 LSPosed 模块。
 *
 * 逆向依据（样本：tv.danmaku.bili 9.17.0 / versionCode 9170300）：
 *
 *  1) 旧播放器架构（ship.theseus）用 com.bilibili.adcommon.config.DDConfig：
 *       DDConfig.getPausePageEnable():Z            直接返回暂停页总开关
 *       DDConfig.boolean(String, boolean):Z       通用布尔配置读取（key = dd_ad_pausedpage_enable）
 *
 *  2) 新版 KMP 架构（ship.theseus2 / kntr）用 kntr.base.config.KConfig：
 *       kntr.app.ad.biz.videodetail.pausedpage.h.getEnabled():Z
 *           -> KConfig.INSTANCE.ff("dd_ad_pausedpage_enable", true)
 *       所以同时 hook 配置类与底层 ff()，任意一层生效都能关掉整条流程。
 *
 *  3) 兜底：VDPausedPage.getCountDownView(...) 返回的是「N秒后将展示广告 / 取消」
 *     那个 ComposeView，把它隐藏掉（保持原对象类型，避免调用方 ClassCastException）。
 *
 * 设计原则：全部 hook 按「类名 + 方法名」查找、找不到就跳过，任一层失败都不影响宿主；
 * 只改 boolean 返回值和 View 可见性，不替换对象、不拦截网络、不碰 suspend 返回值。
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "BiliPauseAdBlock";

    private static final String HOST = "tv.danmaku.bili";

    /** 云控总开关的 key。用 contains 匹配，兼容后续加后缀的变体。 */
    private static final String KEY_FRAGMENT = "pausedpage_enable";

    private static final String C_DD_CONFIG = "com.bilibili.adcommon.config.DDConfig";
    private static final String C_K_CONFIG = "kntr.base.config.KConfig";
    private static final String C_KMP_CONFIG = "kntr.app.ad.biz.videodetail.pausedpage.h";
    private static final String C_VD_PAUSED = "com.bilibili.ad.adview.videodetail.pausedpage.VDPausedPage";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!HOST.equals(lpparam.packageName)) {
            return;
        }

        log("attach process=" + lpparam.processName + " first=" + lpparam.isFirstApplication);

        // 源头：把两套架构的总开关都读成 false
        hookBooleanConfig(lpparam.classLoader, C_DD_CONFIG, "boolean");
        hookBooleanConfig(lpparam.classLoader, C_K_CONFIG, "ff");
        hookNoArgFalse(lpparam.classLoader, C_DD_CONFIG, "getPausePageEnable");
        hookNoArgFalse(lpparam.classLoader, C_KMP_CONFIG, "getEnabled");

        // 兜底：万一下游还是创建了倒计时条，直接隐藏
        hookCountdownView(lpparam.classLoader);

        log("hooks installed");
    }

    /** hook boolean xxx(String key, boolean def) 形式：命中暂停广告 key 时短路返回 false。 */
    private static void hookBooleanConfig(ClassLoader cl, String className, String method) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
            if (clazz == null) {
                log("skip (class absent): " + className);
                return;
            }
            XposedBridge.hookAllMethods(clazz, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object[] args = param.args;
                        if (args == null || args.length < 2 || !isTargetKey(args[0])) {
                            return;
                        }
                        param.setResult(Boolean.FALSE);
                        log("OFF " + className + "#" + method + " key=" + args[0]);
                    } catch (Throwable t) {
                        log("error in " + className + "#" + method + ": " + t);
                    }
                }
            });
            log("hooked " + className + "#" + method);
        } catch (Throwable t) {
            log("hook failed " + className + "#" + method + ": " + t);
        }
    }

    /** hook 无参 boolean getter，直接返回 false。 */
    private static void hookNoArgFalse(ClassLoader cl, String className, String method) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
            if (clazz == null) {
                log("skip (class absent): " + className);
                return;
            }
            XposedBridge.hookAllMethods(clazz, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        if (param.args != null && param.args.length != 0) {
                            return;
                        }
                        param.setResult(Boolean.FALSE);
                        log("OFF " + className + "#" + method + "()");
                    } catch (Throwable t) {
                        log("error in " + className + "#" + method + ": " + t);
                    }
                }
            });
            log("hooked " + className + "#" + method + "()");
        } catch (Throwable t) {
            log("hook failed " + className + "#" + method + ": " + t);
        }
    }

    /** 不替换返回对象，只在原 ComposeView 上设为 GONE，类型安全。 */
    private static void hookCountdownView(ClassLoader cl) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(C_VD_PAUSED, cl);
            if (clazz == null) {
                log("skip (class absent): " + C_VD_PAUSED);
                return;
            }
            XposedBridge.hookAllMethods(clazz, "getCountDownView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object result = param.getResult();
                        if (result instanceof View) {
                            View v = (View) result;
                            v.setVisibility(View.GONE);
                            v.setAlpha(0f);
                            log("hide countdown view");
                        }
                    } catch (Throwable t) {
                        log("hide countdown view failed: " + t);
                    }
                }
            });
            log("hooked " + C_VD_PAUSED + "#getCountDownView");
        } catch (Throwable t) {
            log("hook failed " + C_VD_PAUSED + "#getCountDownView: " + t);
        }
    }

    private static boolean isTargetKey(Object key) {
        return key instanceof String && ((String) key).contains(KEY_FRAGMENT);
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
        Log.i(TAG, message);
    }
}
