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
import android.os.SystemClock;
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
                    XposedBridge.log(TAG + "init failed " + t);
                }
            }
        });
        XposedBridge.log(TAG + "hooked com.miui.home Application.onCreate");
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
                    XposedBridge.log(TAG + "touch map failed " + t);
                }
            }
        });
    }

    private static synchronized void register(Context ctx) {
        if (registered) return;
        registered = true;
        IntentFilter filter = new IntentFilter();
        for (String[] ns : NAMESPACES) {
            filter.addAction(ns[0] + ".action.QUERY_WORKSPACE");
            filter.addAction(ns[0] + ".action.SET_WORKSPACE");
            filter.addAction(ns[0] + ".action.SYSTEMUI_SET_WORKSPACE");
        }
        ctx.registerReceiver(new HomeResponder(), filter, Context.RECEIVER_EXPORTED);
        XposedBridge.log(TAG + "responder registered in com.miui.home");
    }

    // ---------------- v2: launcher transform ----------------

    private static void installTransformHooks(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                try {
                    String name = activity.getClass().getName();
                    if (name.contains("miui.home") && name.contains("Launcher")) {
                        View decor = activity.getWindow().getDecorView();
                        decorRef = new WeakReference<>(decor);
                        applyTransform(decor, false);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "resume hook failed " + t);
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
                        XposedBridge.log(TAG + "sys ime: " + v);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        resolver.registerContentObserver(Settings.Global.getUriFor("nextepshim_ime_state_v1"), false, imeRelay);
        refreshFromSettings(app);
        XposedBridge.log(TAG + "transform hooks installed");
    }

    private static void refreshFromSettings(Context ctx) {
        try {
            ContentResolver resolver = ctx.getContentResolver();
            wsActive = Settings.Global.getInt(resolver, KEY_ACTIVE, 0) == 1;
            String geo = Settings.Global.getString(resolver, KEY_GEOMETRY);
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
            XposedBridge.log(TAG + "refresh failed " + t);
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
        XposedBridge.log(TAG + "transform target scale=" + toScale + " tx=" + toTx + " ty=" + toTy + " wsActive=" + wsActive);
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

    private static class HomeResponder extends BroadcastReceiver {
        private static Boolean lastRelayed = null;
        private static int droppedEchoes = 0;
        private static long lastEchoLogMs = 0;

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String[] ns = nsFor(action);
            if (ns == null) return;
            String prefix = ns[0];
            int protocol = Integer.parseInt(ns[1]);
            try {
                if (action.endsWith(".action.QUERY_WORKSPACE")) {
                    reply(prefix, protocol);
                    XposedBridge.log(TAG + "QUERY answered active=" + active + " proto=" + protocol);
                } else if (action.endsWith(".action.SET_WORKSPACE")) {
                    active = intent.getBooleanExtra(prefix + ".extra.REQUESTED_ACTIVE", active);
                    reply(prefix, protocol);
                    XposedBridge.log(TAG + "SET applied active=" + active + " proto=" + protocol);
                } else if (action.endsWith(".action.SYSTEMUI_SET_WORKSPACE")) {
                    boolean requested = intent.getBooleanExtra(prefix + ".extra.REQUESTED_ACTIVE", active);
                    // Echo guard (v2.7): the broker re-notifies SYSTEMUI_SET
                    // after applying every HOST_SET_REQUEST. Relaying that
                    // echo again closes a self-sustaining broadcast loop
                    // (~100 Hz; observed 2026-10-26: 3,738 relays in ~2 min,
                    // launcher jittering). In the original design the
                    // launcher consumes SYSTEMUI_SET terminally; a genuine
                    // new request always flips the value vs. the last one
                    // relayed, so same-value repeats are echoes: drop them.
                    if (lastRelayed != null && lastRelayed == requested) {
                        droppedEchoes++;
                        long now = SystemClock.uptimeMillis();
                        if (now - lastEchoLogMs >= 5000) {
                            lastEchoLogMs = now;
                            XposedBridge.log(TAG + "SYSTEMUI_SET echo dropped active="
                                + requested + " total=" + droppedEchoes);
                        }
                        return;
                    }
                    lastRelayed = requested;
                    Intent relay = new Intent(prefix + ".action.HOST_SET_REQUEST");
                    relay.setComponent(new ComponentName(ns[2],
                        "io.github.lujinxin.nextep.trigger.WorkspaceControlBrokerReceiver"));
                    relay.putExtra(prefix + ".extra.REQUESTED_ACTIVE", requested);
                    context.sendBroadcast(relay);
                    XposedBridge.log(TAG + "SYSTEMUI_SET relayed active=" + requested);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "onReceive failed " + t);
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
