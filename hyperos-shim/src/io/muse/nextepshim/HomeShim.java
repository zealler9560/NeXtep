package io.muse.nextepshim;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HomeShim implements IXposedHookLoadPackage {
    private static final String TAG = "NeXtepHomeShim: ";
    private static final String VERSION = "2.8";
    /** Recent log lines, answered to the main app's diagnostics export (the LSPosed log is not in it). */
    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();
    private static final java.util.Set<String> SEEN_ACTIVITIES = new java.util.HashSet<>();

    static synchronized void log(String message) {
        XposedBridge.log(TAG + message);
        RECENT.addLast(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()) + " " + message);
        while (RECENT.size() > 200) RECENT.removeFirst();
    }

    static synchronized String recent() {
        return String.join("\n", RECENT);
    }
    private static final int RESULT_HANDLED = 0x4E58;
    private static boolean registered = false;
    private static volatile boolean active = false;

    private static final String[][] NAMESPACES = {
        {"io.github.yishisanren.panes", "52", "io.github.yishisanren.panes"},
        {"io.github.lujinxin.nextep", "39", "io.github.lujinxin.nextep"},
    };

    // ---- workspace transform state (v2) ----
    private static final String KEY_ACTIVE = "nextep_workspace_active_v1";
    private static final String KEY_GEOMETRY = "nextep_workspace_geometry_v1";
    private static WeakReference<View> decorRef = new WeakReference<>(null);
    private static boolean wsActive = false;
    private static boolean hasGeo = false;
    private static int pSW = 0, pSH = 0, pTopH = 0, pSB = 0, pNavL = 0, pNavT = 0, pNavR = 0, pNavB = 0, pSI = 0, pMCH = 0, pBG = 0;
    private static boolean pCab = false;
    private static String pSide = "RIGHT";
    private static ValueAnimator runningAnim;
    private static volatile float curScale = 1f, curTx = 0f, curTy = 0f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            // Only HyperOS/MIUI lacks NeXtep's own IME fit; on ColorOS the two hooks would
            // scale the input method twice if a user enabled this scope by mistake.
            if (!isMiuiRom()) {
                log("not a MIUI/HyperOS build; ime fit not installed");
                return;
            }
            ImeFit.install(lpparam.classLoader);
            return;
        }
        if (!"com.miui.home".equals(lpparam.packageName)) return;
        if (!"com.miui.home".equals(lpparam.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Application app = (Application) param.thisObject;
                    register(app);
                    installTransformHooks(app);
                } catch (Throwable t) {
                    log("init failed " + t);
                }
            }
        });
        log("hooked com.miui.home Application.onCreate");
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (curScale == 1f) return;
                    Activity a = (Activity) param.thisObject;
                    String n = a.getClass().getName();
                    if (!n.contains("miui.home") || !n.contains("Launcher")) return;
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev == null) return;
                    Matrix m = new Matrix();
                    m.setTranslate(-curTx, -curTy);
                    m.postScale(1f / curScale, 1f / curScale);
                    ev.transform(m);
                } catch (Throwable t) {
                    log("touch map failed " + t);
                }
            }
        });
    }

    private static synchronized void register(Context ctx) {
        if (registered) return;
        registered = true;
        for (String[] ns : NAMESPACES) {
            // QUERY/SET: the sender must hold the module's signature permission, as NeXtep's own
            // launcher receiver requires; SYSTEMUI_SET comes from SystemUI and stays open, as in NeXtep.
            IntentFilter control = new IntentFilter();
            control.addAction(ns[0] + ".action.QUERY_WORKSPACE");
            control.addAction(ns[0] + ".action.SET_WORKSPACE");
            ctx.registerReceiver(new HomeResponder(), control, ns[0] + ".permission.CONTROL_WORKSPACE", null,
                Context.RECEIVER_EXPORTED);
            IntentFilter systemUi = new IntentFilter(ns[0] + ".action.SYSTEMUI_SET_WORKSPACE");
            ctx.registerReceiver(new HomeResponder(), systemUi, Context.RECEIVER_EXPORTED);
            IntentFilter diagnostics = new IntentFilter(ns[0] + ".action.QUERY_LAUNCHER_DIAGNOSTICS");
            ctx.registerReceiver(new DiagnosticsResponder(), diagnostics, Context.RECEIVER_EXPORTED);
        }
        log("responder registered in com.miui.home");
    }

    /** HyperOS/MIUI only: Xiaomi builds carry the MIUI (or HyperOS) version property. */
    private static boolean isMiuiRom() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            for (String key : new String[] {"ro.miui.ui.version.code", "ro.miui.ui.version.name", "ro.mi.os.version.name"}) {
                String v = (String) get.invoke(null, key, "");
                if (v != null && !v.isEmpty()) return true;
            }
        } catch (Throwable ignored) {
        }
        return "xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER);
    }

    // ---------------- v2: launcher transform ----------------

    private static void installTransformHooks(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                try {
                    String name = activity.getClass().getName();
                    boolean launcher = name.contains("miui.home") && name.contains("Launcher");
                    if (SEEN_ACTIVITIES.size() < 12 && SEEN_ACTIVITIES.add(name)) log("resumed " + name + " launcher=" + launcher);
                    if (launcher) {
                        View decor = activity.getWindow().getDecorView();
                        decorRef = new WeakReference<>(decor);
                        applyTransform(decor, false);
                    }
                } catch (Throwable t) {
                    log("resume hook failed " + t);
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        ContentResolver resolver = app.getContentResolver();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                refreshFromSettings(app);
            }
        };
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_ACTIVE), false, observer);
        resolver.registerContentObserver(Settings.Global.getUriFor(KEY_GEOMETRY), false, observer);
        // Relay the system-side IME fit state: system-process LSPosed logs
        // proved lossy in exports, this process's log stream is reliable.
        ContentObserver imeRelay = new ContentObserver(new Handler(Looper.getMainLooper())) {
            private String last = "";
            @Override public void onChange(boolean selfChange, Uri uri) {
                try {
                    String v = Settings.Global.getString(app.getContentResolver(), "nextepshim_ime_state_v1");
                    if (v != null && !v.equals(last)) {
                        last = v;
                        log("sys ime: " + v);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        resolver.registerContentObserver(Settings.Global.getUriFor("nextepshim_ime_state_v1"), false, imeRelay);
        refreshFromSettings(app);
        log("transform hooks installed");
    }

    private static void refreshFromSettings(Context ctx) {
        try {
            ContentResolver resolver = ctx.getContentResolver();
            wsActive = Settings.Global.getInt(resolver, KEY_ACTIVE, 0) == 1;
            String geo = Settings.Global.getString(resolver, KEY_GEOMETRY);
            log("settings active=" + wsActive + " geometry=" + geo + " decor=" + (decorRef.get() != null));
            if (geo != null) {
                String[] parts = geo.split(",");
                if (parts.length >= 14) {
                    pSW = Integer.parseInt(parts[0].trim());
                    pSH = Integer.parseInt(parts[1].trim());
                    pTopH = Integer.parseInt(parts[2].trim());
                    pSB = Integer.parseInt(parts[3].trim());
                    pSide = parts[4].trim();
                    pNavL = Integer.parseInt(parts[5].trim());
                    pNavT = Integer.parseInt(parts[6].trim());
                    pNavR = Integer.parseInt(parts[7].trim());
                    pNavB = Integer.parseInt(parts[8].trim());
                    pCab = Integer.parseInt(parts[10].trim()) == 1;
                    pSI = Integer.parseInt(parts[11].trim());
                    pMCH = Integer.parseInt(parts[12].trim());
                    pBG = Integer.parseInt(parts[13].trim());
                    hasGeo = pSW > 0 && pSH > 0 && pSB > 0;
                }
            }
            View decor = decorRef.get();
            if (decor != null) applyTransform(decor, true);
        } catch (Throwable t) {
            log("refresh failed " + t);
        }
    }

    private static void applyTransform(final View decor, boolean animate) {
        float targetScale = 1f, targetTx = 0f, targetTy = 0f;
        if (wsActive && hasGeo) {
            boolean exact = false;
            if (pSW <= pSH) {
                // Portrait: NeXtep's own launcher fit (contentScale/contentTranslation).
                int aL = pNavL, aT = pNavT + pSI, aR = pSW - pNavR, aB = pSH - pNavB;
                int aW = aR - aL, aH = aB - aT;
                if (aW > 1 && aH > 1 && pSB < aW) {
                    int contentTop = aT + (pCab ? 0 : pTopH);
                    int contentWidth = aW - pSB;
                    int contentHeight = aH - pTopH - pBG;
                    int contentLeft = aL + ("LEFT".equals(pSide) ? pSB : 0);
                    if (contentWidth > 1 && contentHeight > 1) {
                        float cs = Math.min((float) contentWidth / aW, (float) contentHeight / aH);
                        targetScale = cs;
                        targetTx = contentLeft + (contentWidth - aW * cs) / 2f - aL * cs;
                        targetTy = contentTop - aT * cs;
                        exact = true;
                    }
                }
            }
            if (!exact) {
                // Landscape/fallback approximation.
                targetScale = (float) (pSW - pSB) / (float) pSW;
                targetTx = "RIGHT".equals(pSide) ? 0f : (float) pSB;
                targetTy = (float) pTopH;
            }
        }
        final float fromScale = decor.getScaleX();
        final float fromTx = decor.getTranslationX();
        final float fromTy = decor.getTranslationY();
        final float toScale = targetScale, toTx = targetTx, toTy = targetTy;
        curScale = toScale; curTx = toTx; curTy = toTy;
        log("transform target scale=" + toScale + " tx=" + toTx + " ty=" + toTy + " wsActive=" + wsActive);
        decor.setPivotX(0f);
        decor.setPivotY(0f);
        if (runningAnim != null) runningAnim.cancel();
        if (!animate || (fromScale == toScale && fromTx == toTx && fromTy == toTy)) {
            decor.setScaleX(toScale); decor.setScaleY(toScale);
            decor.setTranslationX(toTx); decor.setTranslationY(toTy);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.addUpdateListener(animation -> {
            float t = (Float) animation.getAnimatedValue();
            decor.setScaleX(fromScale + (toScale - fromScale) * t);
            decor.setScaleY(fromScale + (toScale - fromScale) * t);
            decor.setTranslationX(fromTx + (toTx - fromTx) * t);
            decor.setTranslationY(fromTy + (toTy - fromTy) * t);
        });
        runningAnim = anim;
        anim.start();
    }

    // ---------------- v1: protocol responder ----------------

    private static String[] nsFor(String action) {
        for (String[] ns : NAMESPACES) {
            if (action != null && action.startsWith(ns[0] + ".action.")) return ns;
        }
        return null;
    }

    /** Answers the main app's diagnostics export, as its own launcher hook does on ColorOS. */
    private static class DiagnosticsResponder extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isOrderedBroadcast()) return;
            android.app.PendingIntent identity = intent.getParcelableExtra("systemui_identity");
            if (identity == null || !"com.android.systemui".equals(identity.getCreatorPackage())) return;
            Bundle extras = new Bundle();
            extras.putString("events", "NeXtepHomeShim " + VERSION + " wsActive=" + wsActive + " hasGeo=" + hasGeo
                + " scale=" + curScale + " decor=" + (decorRef.get() != null) + "\n" + recent());
            setResultCode(35201);
            setResultExtras(extras);
        }
    }

    private static class HomeResponder extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String[] ns = nsFor(action);
            if (ns == null) return;
            String prefix = ns[0];
            // Echo the version the asking build expects; the table value only serves older builds.
            int protocol = intent.getIntExtra(prefix + ".extra.RUNTIME_PROTOCOL_VERSION", Integer.parseInt(ns[1]));
            try {
                if (action.endsWith(".action.QUERY_WORKSPACE")) {
                    reply(prefix, protocol);
                    log("QUERY answered active=" + active + " proto=" + protocol);
                } else if (action.endsWith(".action.SET_WORKSPACE")) {
                    active = intent.getBooleanExtra(prefix + ".extra.REQUESTED_ACTIVE", active);
                    reply(prefix, protocol);
                    log("SET applied active=" + active + " proto=" + protocol);
                } else if (action.endsWith(".action.SYSTEMUI_SET_WORKSPACE")) {
                    boolean requested = intent.getBooleanExtra(prefix + ".extra.REQUESTED_ACTIVE", active);
                    Intent relay = new Intent(prefix + ".action.HOST_SET_REQUEST");
                    relay.setComponent(new ComponentName(ns[2],
                        "io.github.lujinxin.nextep.trigger.WorkspaceControlBrokerReceiver"));
                    relay.putExtra(prefix + ".extra.REQUESTED_ACTIVE", requested);
                    context.sendBroadcast(relay);
                    log("SYSTEMUI_SET relayed active=" + requested);
                }
            } catch (Throwable t) {
                log("onReceive failed " + t);
            }
        }

        private void reply(String prefix, int protocol) {
            Bundle extras = new Bundle();
            extras.putBoolean(prefix + ".extra.WORKSPACE_ACTIVE", wsActive);
            extras.putString(prefix + ".extra.HOME_PACKAGE", "com.miui.home");
            extras.putInt(prefix + ".extra.RUNTIME_PROTOCOL_VERSION", protocol);
            setResultCode(RESULT_HANDLED);
            setResultExtras(extras);
        }
    }
}
