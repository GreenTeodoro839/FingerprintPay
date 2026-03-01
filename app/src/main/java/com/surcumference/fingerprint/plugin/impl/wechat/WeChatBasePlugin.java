package com.surcumference.fingerprint.plugin.impl.wechat;

import static com.surcumference.fingerprint.Constant.PACKAGE_NAME_WECHAT;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hjq.toast.Toaster;
import com.surcumference.fingerprint.BuildConfig;
import com.surcumference.fingerprint.Constant;
import com.surcumference.fingerprint.Lang;
import com.surcumference.fingerprint.R;
import com.surcumference.fingerprint.bean.DigitPasswordKeyPadInfo;
import com.surcumference.fingerprint.plugin.inf.IAppPlugin;
import com.surcumference.fingerprint.plugin.inf.IMockCurrentUser;
import com.surcumference.fingerprint.plugin.inf.OnFingerprintVerificationOKListener;
import com.surcumference.fingerprint.util.ActivityViewObserver;
import com.surcumference.fingerprint.util.ActivityViewObserverHolder;
import com.surcumference.fingerprint.util.ApplicationUtils;
import com.surcumference.fingerprint.util.BizBiometricIdentify;
import com.surcumference.fingerprint.util.BlackListUtils;
import com.surcumference.fingerprint.util.Config;
import com.surcumference.fingerprint.util.DpUtils;
import com.surcumference.fingerprint.util.FragmentObserver;
import com.surcumference.fingerprint.util.ImageUtils;
import com.surcumference.fingerprint.util.NotifyUtils;
import com.surcumference.fingerprint.util.StyleUtils;
import com.surcumference.fingerprint.util.Task;
import com.surcumference.fingerprint.util.ViewUtils;
import com.surcumference.fingerprint.util.WeChatVersionControl;
import com.surcumference.fingerprint.util.XBiometricIdentify;
import com.surcumference.fingerprint.util.drawable.XDrawable;
import com.surcumference.fingerprint.util.log.L;
import com.surcumference.fingerprint.util.paydialog.WeChatPayDialog;
import com.surcumference.fingerprint.view.SettingsView;
import com.wei.android.lib.fingerprintidentify.bean.FingerprintIdentifyFailInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

public class WeChatBasePlugin implements IAppPlugin, IMockCurrentUser {

    private WeakHashMap<View, View.OnAttachStateChangeListener> mView2OnAttachStateChangeListenerMap = new WeakHashMap<>();
    protected boolean mMockCurrentUser = false;
    protected XBiometricIdentify mFingerprintIdentify;
    private FragmentObserver mFragmentObserver;
    private int mWeChatVersionCode = 0;
    private boolean mFingerprintIdentifyTemporaryBlocking = false;

    @Override
    public int getVersionCode(Context context) {
        if (mWeChatVersionCode != 0) {
            return mWeChatVersionCode;
        }
        mWeChatVersionCode = ApplicationUtils.getPackageVersionCode(context, PACKAGE_NAME_WECHAT);
        return mWeChatVersionCode;
    }

    protected synchronized void initFingerPrintLock(Context context, Config config,
                                                    boolean smallPayDialogFloating, String passwordEncrypted,
                                                    OnFingerprintVerificationOKListener onSuccessUnlockCallback,
                                                    final Runnable onFailureUnlockCallback) {
        cancelFingerprintIdentify();
        mFingerprintIdentify = new BizBiometricIdentify(context)
                .withMockCurrentUserCallback(this)
                .decryptPasscode(passwordEncrypted, new BizBiometricIdentify.IdentifyListener() {

                    @Override
                    public void onDecryptionSuccess(BizBiometricIdentify identify, @NonNull String decryptedContent) {
                        super.onDecryptionSuccess(identify, decryptedContent);
                        onSuccessUnlockCallback.onFingerprintVerificationOK(decryptedContent);
                    }

                    @Override
                    public void onFailed(BizBiometricIdentify target, FingerprintIdentifyFailInfo failInfo) {
                        super.onFailed(target, failInfo);
                        onFailureUnlockCallback.run();
                    }
                });
    }

    protected boolean isHeaderViewExistsFallback(ListView listView) {
        if (listView == null) {
            return false;
        }
        if (listView.getHeaderViewsCount() <= 0) {
            return false;
        }
        try {
            Field mHeaderViewInfosField = ListView.class.getDeclaredField("mHeaderViewInfos");
            mHeaderViewInfosField.setAccessible(true);
            ArrayList<ListView.FixedViewInfo> mHeaderViewInfos = (ArrayList<ListView.FixedViewInfo>) mHeaderViewInfosField.get(listView);
            if (mHeaderViewInfos != null) {
                for (ListView.FixedViewInfo viewInfo : mHeaderViewInfos) {
                    if (viewInfo.view == null) {
                        continue;
                    }
                    Object tag = viewInfo.view.getTag();
                    if (BuildConfig.APPLICATION_ID.equals(tag)) {
                        L.d("found plugin settings headerView");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            L.e(e);
        }
        return false;
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        //Xposed not hooked yet!
    }

    private boolean isPaymentActivity(String activityClzName) {
        return activityClzName.contains(".WalletPayUI")
                || activityClzName.contains(".UIPageFragmentActivity")
                || activityClzName.contains(".AppBrandUI") /** mini-program 8.0.67+ */
                || activityClzName.contains(".AppBrandPluginUI") /** mini-program plugin 8.0.67+ */
                || activityClzName.contains(".WalletPayCustomUI"); /** custom payment 8.0.67+ */
    }

    private boolean isSettingsActivity(String activityClzName) {
        return activityClzName.contains("com.tencent.mm.plugin.setting.ui.setting.SettingsUI")
                || activityClzName.contains("com.tencent.mm.plugin.wallet.pwd.ui.WalletPasswordSettingUI")
                || activityClzName.contains("com.tencent.mm.ui.vas.VASCommonActivity") /** 8.0.18 */
                || activityClzName.contains("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI") /** 8.0.67+ */
                || activityClzName.contains("com.tencent.mm.plugin.setting.ui.setting_new.CommonSettingsUI"); /** 8.0.67+ */
    }

    @Override
    public void onActivityResumed(Activity activity) {
        L.d("Activity onResume =", activity);
        final String activityClzName = activity.getClass().getName();
        if (isSettingsActivity(activityClzName)) {
            doSettingsMenuInjectWithRetry(activity, 0);
        }
        if (getVersionCode(activity) >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_20 && activityClzName.contains("com.tencent.mm.ui.LauncherUI")) {
            startFragmentObserver(activity);
        }
        // 对所有 Activity 启动支付观察器，以便捕获小程序通过 IPC 触发的支付弹窗
        startPaymentObserver(activity);
    }

    private void doSettingsMenuInjectWithRetry(Activity activity, int retryCount) {
        Task.onMain(retryCount == 0 ? 200 : 500, () -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            doSettingsMenuInject(activity);
            // 如果注入未成功（未找到合适的视图），最多重试 5 次
            View injected = activity.getWindow().getDecorView().findViewWithTag(BuildConfig.APPLICATION_ID);
            if (injected == null && retryCount < 5) {
                doSettingsMenuInjectWithRetry(activity, retryCount + 1);
            }
        });
    }

    private void startPaymentObserver(Activity activity) {
        ActivityViewObserver activityViewObserver = new ActivityViewObserver(activity);
        // 同时匹配主进程支付（EditHintPasswdView）和小程序支付（MiniAppSecureEditText）
        activityViewObserver.setActivityViewFinder(outViewList -> {
            List<View> windowViews = ViewUtils.getWindowManagerViews();
            for (View decorView : windowViews) {
                if (!(decorView instanceof ViewGroup)) continue;
                List<View> candidates = new ArrayList<>();
                ViewUtils.getChildViewsByType((ViewGroup) decorView, ".EditHintPasswdView", candidates);
                if (candidates.isEmpty()) {
                    ViewUtils.getChildViewsByType((ViewGroup) decorView, ".MiniAppSecureEditText", candidates);
                }
                if (!candidates.isEmpty()) {
                    outViewList.addAll(candidates);
                    return;
                }
            }
        });
        ActivityViewObserverHolder.start(ActivityViewObserverHolder.Key.WeChatPayView,  activityViewObserver,
                100, new ActivityViewObserver.IActivityViewListener() {
            @Override
            public void onViewFounded(ActivityViewObserver observer, View view) {
                ActivityViewObserver.IActivityViewListener l = this;
                ActivityViewObserverHolder.stop(observer);
                L.d("onViewFounded:", view, " rootView: ", view.getRootView());
                ViewGroup rootView = (ViewGroup) view.getRootView();
                view.post(() -> onPayDialogShown(activity, rootView));

                View.OnAttachStateChangeListener listener = mView2OnAttachStateChangeListenerMap.get(view);
                if (listener != null) {
                    view.removeOnAttachStateChangeListener(listener);
                }
                listener = new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        L.d("onViewAttachedToWindow:", view);

                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        L.d("onViewDetachedFromWindow:", view);
                        Context context = v.getContext();
                        onPayDialogDismiss(context, rootView);
                        if (Config.from(context).isVolumeDownMonitorEnabled()) {
                            ViewUtils.unregisterVolumeKeyDownEventListener(activity.getWindow());
                        }
                        Task.onMain(500, () -> observer.start(100, l));
                    }
                };
                view.addOnAttachStateChangeListener(listener);
                mView2OnAttachStateChangeListenerMap.put(view, listener);
            }
        });
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        //Xposed not hooked yet!
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            L.d("Activity onPause =", activity);
            final String activityClzName = activity.getClass().getName();
            // 仅在明确的支付 Activity 暂停时停止观察器和清理
            if (activityClzName.contains(".WalletPayUI")
                    || activityClzName.contains(".UIPageFragmentActivity")
                    || activityClzName.contains(".WalletPayCustomUI")) {
                ActivityViewObserverHolder.stop(ActivityViewObserverHolder.Key.WeChatPayView);
                ActivityViewObserverHolder.stop(ActivityViewObserverHolder.Key.WeChatPaymentMethodView);
                onPayDialogDismiss(activity, activity.getWindow().getDecorView());
            }
            if (getVersionCode(activity) >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_20 && activityClzName.contains("com.tencent.mm.ui.LauncherUI")) {
                stopFragmentObserver(activity);
            }
        } catch (Exception e) {
            L.e(e);
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        //Xposed not hooked yet!
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        //Xposed not hooked yet!
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        //Xposed not hooked yet!
    }

    @Override
    public boolean getMockCurrentUser() {
        return this.mMockCurrentUser;
    }

    @Override
    public void setMockCurrentUser(boolean mock) {
        this.mMockCurrentUser = mock;
    }

    private void startFragmentObserver(Activity activity) {
        stopFragmentObserver(activity);
        FragmentObserver fragmentObserver = new FragmentObserver(activity);
        fragmentObserver.setFragmentIdentifyClassName("com.tencent.mm.ui.vas.VASCommonFragment");
        fragmentObserver.start((observer, fragmentObject, fragmentRootView) -> doSettingsMenuInject(fragmentRootView.getContext(), fragmentRootView, fragmentObject.getClass().getName()));
        mFragmentObserver = fragmentObserver;
    }

    private void stopFragmentObserver(Activity activity) {
        FragmentObserver fragmentObserver = mFragmentObserver;
        if (fragmentObserver != null) {
            fragmentObserver.stop();
            mFragmentObserver = null;
        }
    }

    protected void onPayDialogShown(Activity activity, ViewGroup rootView) {
        L.d("PayDialog show");
        Context context = rootView.getContext();
        Config config = Config.from(context);
        if (!config.isOn()) {
            return;
        }
        if (mFingerprintIdentifyTemporaryBlocking) {
            return;
        }
        String passwordEncrypted = config.getPasswordEncrypted();
        if (TextUtils.isEmpty(passwordEncrypted) || TextUtils.isEmpty(config.getPasswordIV())) {
            NotifyUtils.notifyBiometricIdentify(context, Lang.getString(R.id.toast_password_not_set_wechat));
            return;
        }

        int versionCode = getVersionCode(context);
        WeChatPayDialog payDialogView = WeChatPayDialog.findFrom(versionCode, rootView);
        L.d(payDialogView);
        if (payDialogView == null) {
            NotifyUtils.notifyVersionUnSupport(context, Constant.PACKAGE_NAME_WECHAT);
            return;
        }

        ViewGroup passwordLayout = payDialogView.passwordLayout;
        EditText mInputEditText = payDialogView.inputEditText;
        List<View> keyboardViews = payDialogView.keyboardViews;
        TextView usePasswordText = payDialogView.usePasswordText;
        TextView titleTextView = payDialogView.titleTextView;

        boolean smallPayDialogFloating = isSmallPayDialogFloating(passwordLayout);
        RelativeLayout fingerPrintLayout = new RelativeLayout(context);
        fingerPrintLayout.setTag("fingerPrintLayout");
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        fingerPrintLayout.setLayoutParams(layoutParams);

        fingerPrintLayout.setClipChildren(false);
        ImageView fingerprintImageView = new ImageView(context);
        try {
            final Bitmap bitmap = ImageUtils.base64ToBitmap(Constant.ICON_FINGER_PRINT_WECHAT_BASE64);
            fingerprintImageView.setImageBitmap(bitmap);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                fingerprintImageView.getViewTreeObserver().addOnWindowAttachListener(new ViewTreeObserver.OnWindowAttachListener() {
                    @Override
                    public void onWindowAttached() {

                    }

                    @Override
                    public void onWindowDetached() {
                        fingerprintImageView.getViewTreeObserver().removeOnWindowAttachListener(this);
                        try {
                            bitmap.recycle();
                        } catch (Exception e) {
                        }
                    }
                });
            }
        } catch (OutOfMemoryError e) {
            L.d(e);
        }
        RelativeLayout.LayoutParams fingerprintImageViewLayoutParams = new RelativeLayout.LayoutParams(DpUtils.dip2px(context, 70), DpUtils.dip2px(context, 70));
        fingerprintImageViewLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        fingerprintImageViewLayoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        if (smallPayDialogFloating) {
            fingerprintImageViewLayoutParams.topMargin = DpUtils.dip2px(context, -14);
            fingerPrintLayout.addView(fingerprintImageView, fingerprintImageViewLayoutParams);
            fingerprintImageView.setVisibility(View.VISIBLE);
        } else {
            fingerprintImageViewLayoutParams.bottomMargin = DpUtils.dip2px(context, 180);
            fingerPrintLayout.addView(fingerprintImageView, fingerprintImageViewLayoutParams);
            fingerprintImageView.setVisibility(config.isShowFingerprintIcon() ? View.VISIBLE : View.GONE);
        }

        final Runnable switchToPasswordRunnable = ()-> {
            if (smallPayDialogFloating) {
                passwordLayout.removeView(fingerPrintLayout);
            } else {
                rootView.removeView(fingerPrintLayout);
            }
            mInputEditText.setVisibility(View.VISIBLE);
            keyboardViews.get(keyboardViews.size() - 1).setVisibility(View.VISIBLE);
            mInputEditText.requestFocus();
            mInputEditText.performClick();
            cancelFingerprintIdentify();
            mMockCurrentUser = false;
            if (titleTextView != null) {
                titleTextView.setText(Lang.getString(R.id.wechat_payview_password_title));
            }
            if (usePasswordText != null) {
                usePasswordText.setText(Lang.getString(R.id.wechat_payview_fingerprint_switch_text));
            }
        };

        final Runnable switchToFingerprintRunnable = ()-> {
            mInputEditText.setVisibility(View.GONE);
            for (View keyboardView : keyboardViews) {
                keyboardView.setVisibility(View.GONE);
            }
            if (smallPayDialogFloating) {
                View fingerPrintLayoutLast = passwordLayout.findViewWithTag("fingerPrintLayout");
                if (fingerPrintLayoutLast != null) {
                    passwordLayout.removeView(fingerPrintLayoutLast);
                }
                // 禁止修改, 会导致layoutListener 再次调用 switchToFingerprintRunnable
                // onPayDialogShown 调用 initFingerPrintLock
                // switchToFingerprintRunnable 调用 initFingerPrintLock 导致 onFailed 调用 switchToPasswordRunnable
                // switchToPasswordRunnable 调用 cancelFingerprintIdentify cancel 掉当前, 最终导致全部指纹识别取消
                // fingerPrintLayout.setVisibility(View.GONE);
                passwordLayout.addView(fingerPrintLayout);
                // ensure image icon visibility
                Task.onMain(1000, fingerPrintLayout::requestLayout);
                passwordLayout.setClipChildren(false);
                try {
                    ((ViewGroup) passwordLayout.getParent()).setClipChildren(false);
                    ((ViewGroup) passwordLayout.getParent().getParent()).setTop(((ViewGroup) passwordLayout.getParent().getParent()).getTop() + 200);
                    ((ViewGroup) passwordLayout.getParent().getParent()).setClipChildren(false);
                    ((ViewGroup) passwordLayout.getParent().getParent()).setBackgroundColor(Color.TRANSPARENT);
                    ((ViewGroup) passwordLayout.getParent()).setBackgroundColor(Color.TRANSPARENT);
                } catch (Exception e) {
                    L.d("switchToFingerprint parent navigation failed", e);
                }
            } else {
                View fingerPrintLayoutLast = rootView.findViewWithTag("fingerPrintLayout");
                if (fingerPrintLayoutLast != null) {
                    rootView.removeView(fingerPrintLayoutLast);
                }
                rootView.addView(fingerPrintLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            initFingerPrintLock(context, config, smallPayDialogFloating, passwordEncrypted, (password)-> {
                BlackListUtils.applyIfNeeded(context);
                inputDigitalPassword(context, mInputEditText, password, keyboardViews, smallPayDialogFloating);
            }, switchToPasswordRunnable);
            if (titleTextView != null) {
                titleTextView.setText(Lang.getString(R.id.wechat_payview_fingerprint_title));
            }
            if (usePasswordText != null) {
                usePasswordText.setText(Lang.getString(R.id.wechat_payview_password_switch_text));
            }
        };

        if (usePasswordText != null) {
            Task.onMain(()-> usePasswordText.setVisibility(View.VISIBLE));
            usePasswordText.setOnTouchListener((view, motionEvent) -> {
                try {
                    if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                        if (mInputEditText.getVisibility() == View.GONE) {
                            switchToPasswordRunnable.run();
                        } else {
                            switchToFingerprintRunnable.run();
                        }
                    }
                } catch (Exception e) {
                    L.e(e);
                }
                return true;
            });
        }
        if (titleTextView != null) {
            titleTextView.setOnTouchListener((view, motionEvent) -> {
                try {
                    if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
                        if (mInputEditText.getVisibility() == View.GONE) {
                            switchToPasswordRunnable.run();
                        } else {
                            switchToFingerprintRunnable.run();
                        }
                    }
                } catch (Exception e) {
                    L.e(e);
                }
                return true;
            });
        }

        fingerprintImageView.setOnClickListener(view -> switchToPasswordRunnable.run());
        switchToFingerprintRunnable.run();
        if (config.isVolumeDownMonitorEnabled()) {
            ViewUtils.registerVolumeKeyDownEventListener(activity.getWindow(), event -> {
                if (mFingerprintIdentifyTemporaryBlocking) {
                    return false;
                }
                switchToPasswordRunnable.run();
                Toaster.showLong(Lang.getString(R.id.toast_fingerprint_temporary_disabled));
                mFingerprintIdentifyTemporaryBlocking = true;
                Task.onBackground(60000, () -> mFingerprintIdentifyTemporaryBlocking = false);
                return false;
            });
        }
        watchForSwitchPaymentMethod(activity, rootView, switchToPasswordRunnable, switchToFingerprintRunnable);
    }

    private void watchForSwitchPaymentMethod(Activity activity, ViewGroup rootView, Runnable switchToPasswordRunnable, Runnable switchToFingerprintRunnable) {
        L.d("watchForSwitchPaymentMethod", activity, ViewUtils.getViewInfo(rootView));
        ActivityViewObserver activityViewObserver = new ActivityViewObserver(activity);
        activityViewObserver.setViewIdentifyText("选择付款方式", "選擇付款方式", "Select payment method");
        ActivityViewObserverHolder.start(ActivityViewObserverHolder.Key.WeChatPaymentMethodView, activityViewObserver, 333, (ActivityViewObserver observer, View view) -> {
            L.d("选择付款方式 founded", ViewUtils.getViewInfo(view));
            switchToPasswordRunnable.run();
            observer.stop();
            view.addOnAttachStateChangeListener(
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(@NonNull View view) {
                            L.d("onViewAttachedToWindow", ViewUtils.getViewInfo(view));
                        }

                        @Override
                        public void onViewDetachedFromWindow(@NonNull View view) {
                            L.d("onViewDetachedFromWindow", ViewUtils.getViewInfo(view));
                            view.removeOnAttachStateChangeListener(this);
                            switchToFingerprintRunnable.run();
                            rootView.post(() -> watchForSwitchPaymentMethod(activity, rootView, switchToPasswordRunnable, switchToFingerprintRunnable));
                        }
                    }
            );
        });
    }

    private void inputDigitalPassword(Context context, EditText inputEditText, String pwd,
                                      List<View> keyboardViews, boolean smallPayDialogFloating) {
        int versionCode = getVersionCode(context);
        if (versionCode >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_43) {
            DigitPasswordKeyPadInfo digitPasswordKeyPad = WeChatVersionControl.getDigitPasswordKeyPad(versionCode);
            inputEditText.getText().clear();
            View keyboardView = keyboardViews.get(0); //测了很多遍就是第一个
            // 在半高支付界面需要先激活inputEditText才能正常输入
            ViewGroup inputEditTextGrandParent = null;
            if (!smallPayDialogFloating) {
                try {
                    inputEditTextGrandParent = (ViewGroup) inputEditText.getParent().getParent();
                    inputEditTextGrandParent.setAlpha(0.01f);
                } catch (Exception e) {
                    L.d("inputDigitalPassword setAlpha failed", e);
                }
                inputEditText.setVisibility(View.VISIBLE);
            }
            ViewGroup.LayoutParams keyboardViewParams = keyboardView.getLayoutParams();
            int keyboardViewHeight = keyboardViewParams.height;
            keyboardViewParams.height = 2;
            inputEditText.requestFocus();
            final ViewGroup finalInputEditTextGrandParent = inputEditTextGrandParent;
            inputEditText.post(() -> {
                for (char c : pwd.toCharArray()) {
                    String[] keyIds = digitPasswordKeyPad.keys.get(String.valueOf(c));
                    if (keyIds == null) {
                        continue;
                    }
                    View digitView = ViewUtils.findViewByName(keyboardView, context.getPackageName(), keyIds);
                    if (digitView != null) {
                        ViewUtils.performActionClick(digitView);
                    }
                }
                // inputEditText.setVisibility(View.VISIBLE); 副作用反制
                keyboardView.post(() -> inputEditText.setVisibility(View.GONE));
                keyboardView.postDelayed(() -> {
                    try {
                        if (finalInputEditTextGrandParent != null) {
                            finalInputEditTextGrandParent.setAlpha(1f);
                        }
                    } catch (Exception e) {
                        L.d("inputDigitalPassword restore alpha failed", e);
                    }
                    keyboardViewParams.height = keyboardViewHeight;
                }, 1000);
            });
            return;
        }
        if (getVersionCode(context) >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_18) {
            inputEditText.getText().clear();
            for (char c : pwd.toCharArray()) {
                inputEditText.append(String.valueOf(c));
            }
            return;
        }
        inputEditText.setText(pwd);
    }

    private boolean isSmallPayDialogFloating(ViewGroup passwordLayout) {
        try {
            ViewGroup floatRootView = ((ViewGroup) passwordLayout.getParent().getParent().getParent().getParent().getParent());
            int []location = new int[]{0,0};
            floatRootView.getLocationOnScreen(location);
            L.d("floatRootView", ViewUtils.getViewInfo(floatRootView));
            return location[0] > 0 || floatRootView.getChildCount() > 1;
        } catch (Exception e) {
            L.d("isSmallPayDialogFloating failed, assuming non-floating", e);
            return false;
        }
    }

    protected void onPayDialogDismiss(Context context, View rootView) {
        L.d("PayDialog dismiss");
        if (!Config.from(context).isOn()) {
            return;
        }
        cancelFingerprintIdentify();
        View fingerPrintLayoutLast = rootView.findViewWithTag("fingerPrintLayout");
        if (fingerPrintLayoutLast != null) {
            ViewUtils.removeFromSuperView(fingerPrintLayoutLast);
        }
        mMockCurrentUser = false;
    }

    private void cancelFingerprintIdentify() {
        XBiometricIdentify fingerprintIdentify = mFingerprintIdentify;
        if (fingerprintIdentify == null) {
            return;
        }
        if (!fingerprintIdentify.fingerprintScanStateReady) {
            return;
        }
        fingerprintIdentify.cancelIdentify();
        mFingerprintIdentify = null;
    }

    protected void doSettingsMenuInject(final Activity activity) {
        doSettingsMenuInject(activity, activity.getWindow().getDecorView(), activity.getClass().getName());
    }

    protected void doSettingsMenuInject(Context context, View targetView, String targetClassName) {
        int versionCode = getVersionCode(context);

        // 8.0.67+ 新版设置页面使用 RecyclerView，不再有 android:list ListView
        if (versionCode >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_67
                && (targetClassName.contains("setting_new.MainSettingsUI")
                    || targetClassName.contains("setting_new.CommonSettingsUI"))) {
            doSettingsMenuInjectNewUI(context, targetView);
            return;
        }

        ListView itemView = (ListView) ViewUtils.findViewByName(targetView, "android", "list");
        if (itemView == null) {
            // Fallback: 尝试新版 UI 注入（可能通过 VASCommonActivity 跳转到了新版设置）
            L.d("ListView not found, trying new UI injection");
            doSettingsMenuInjectNewUI(context, targetView);
            return;
        }
        if (ViewUtils.findViewByText(itemView, Lang.getString(R.id.app_settings_name)) != null
                || isHeaderViewExistsFallback(itemView)) {
            return;
        }
        if (versionCode >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_18) {
            //整个设置界面的class 都是 com.tencent.mm.ui.vas.VASCommonActivity...
            if (targetClassName.contains("com.tencent.mm.ui.vas.VASCommonActivity")
                || targetClassName.contains("com.tencent.mm.ui.vas.VASCommonFragment") /** 8.0.20 */) {
                if (ViewUtils.findViewByText(itemView, Lang.getString(R.id.wechat_general),
                        "通用", "一般", "General") == null) {
                    return;
                }
            }
        }

        boolean isDarkMode = StyleUtils.isDarkMode(context);

        LinearLayout settingsItemRootLLayout = new LinearLayout(context);
        settingsItemRootLLayout.setOrientation(LinearLayout.VERTICAL);
        settingsItemRootLLayout.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (versionCode >= Constant.WeChat.WECHAT_VERSION_CODE_8_0_20) {
            // 减少页面跳动
            settingsItemRootLLayout.setPadding(0, 0, 0, 0);
        } else {
            settingsItemRootLLayout.setPadding(0, DpUtils.dip2px(context, 20), 0, 0);
        }

        LinearLayout settingsItemLinearLayout = new LinearLayout(context);
        settingsItemLinearLayout.setOrientation(LinearLayout.VERTICAL);

        settingsItemLinearLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        LinearLayout itemHlinearLayout = new LinearLayout(context);
        itemHlinearLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemHlinearLayout.setWeightSum(1);

        itemHlinearLayout.setBackground(new XDrawable.Builder()
                .defaultColor(isDarkMode ? 0xFF191919 : Color.WHITE)
                .pressedColor(isDarkMode ? 0xFF1D1D1D : 0xFFE5E5E5)
                .create());
        itemHlinearLayout.setGravity(Gravity.CENTER_VERTICAL);
        itemHlinearLayout.setClickable(true);
        itemHlinearLayout.setOnClickListener(view -> new SettingsView(context).showInDialog());

        int defHPadding = DpUtils.dip2px(context, 15);

        TextView itemNameText = new TextView(context);
        itemNameText.setTextColor(isDarkMode ? 0xFFD3D3D3 : 0xFF353535);
        itemNameText.setText(Lang.getString(R.id.app_settings_name));
        itemNameText.setGravity(Gravity.CENTER_VERTICAL);
        itemNameText.setPadding(DpUtils.dip2px(context, 16), 0, 0, 0);
        itemNameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, StyleUtils.TEXT_SIZE_BIG);

        TextView itemSummerText = new TextView(context);
        StyleUtils.apply(itemSummerText);
        itemSummerText.setText(BuildConfig.VERSION_NAME);
        itemSummerText.setGravity(Gravity.CENTER_VERTICAL);
        itemSummerText.setPadding(0, 0, defHPadding, 0);
        itemSummerText.setTextColor(isDarkMode ? 0xFF656565 : 0xFF999999);

        //try use WeChat style
        try {
            View generalView = ViewUtils.findViewByText(itemView, "通用", "一般", "General", "服务管理", "服務管理", "Manage Services");
            L.d("generalView", generalView);
            if (generalView instanceof TextView) {
                TextView generalTextView = (TextView) generalView;
                float scale = itemNameText.getTextSize() / generalTextView.getTextSize();
                itemNameText.setTextSize(TypedValue.COMPLEX_UNIT_PX, generalTextView.getTextSize());

                itemSummerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, itemSummerText.getTextSize() / scale);
                View generalItemView = (View) generalView.getParent().getParent().getParent().getParent().getParent();
                if (generalItemView != null) {
                    Drawable background = generalItemView.getBackground();
                    if (background != null) {
                        Drawable.ConstantState constantState = background.getConstantState();
                        if (constantState != null) {
                            itemHlinearLayout.setBackground(constantState.newDrawable());
                        }
                    }
                }
                itemNameText.setTextColor(generalTextView.getCurrentTextColor());
            }
        } catch (Exception e) {
            L.e(e);
        }

        itemHlinearLayout.addView(itemNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        itemHlinearLayout.addView(itemSummerText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View lineView = new View(context);
        lineView.setBackgroundColor(isDarkMode ? 0xFF2E2E2E : 0xFFD5D5D5);
        settingsItemLinearLayout.addView(lineView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        settingsItemLinearLayout.addView(itemHlinearLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DpUtils.dip2px(context, 55)));

        settingsItemRootLLayout.addView(settingsItemLinearLayout);
        settingsItemRootLLayout.setTag(BuildConfig.APPLICATION_ID);

        itemView.addHeaderView(settingsItemRootLLayout);
    }

    /**
     * 8.0.67+ 新版设置页面注入
     * 新版设置使用 MVVM 框架 + RecyclerView，不再有 android:list ListView
     * 方案：找到 RecyclerView 的父容器，在 RecyclerView 上方插入设置入口
     */
    protected void doSettingsMenuInjectNewUI(Context context, View targetView) {
        // 检查是否已经注入
        View existingView = targetView.findViewWithTag(BuildConfig.APPLICATION_ID);
        if (existingView != null) {
            return;
        }

        // 在新版设置页面中查找 RecyclerView 或主要的滚动容器
        List<View> recyclerViews = new ArrayList<>();
        if (targetView instanceof ViewGroup) {
            findRecyclerViews((ViewGroup) targetView, recyclerViews);
        }

        if (recyclerViews.isEmpty()) {
            L.d("No RecyclerView found in new settings UI, trying ScrollView fallback");
            // 查找 ScrollView 或其他滚动容器
            if (targetView instanceof ViewGroup) {
                findScrollableViews((ViewGroup) targetView, recyclerViews);
            }
        }

        ViewGroup parentView = null;
        View targetChild = null;

        if (!recyclerViews.isEmpty()) {
            // 使用第一个可见的可滚动视图
            View scrollView = null;
            for (View rv : recyclerViews) {
                if (ViewUtils.isShown(rv)) {
                    scrollView = rv;
                    break;
                }
            }
            if (scrollView == null) {
                scrollView = recyclerViews.get(0);
            }
            parentView = (ViewGroup) scrollView.getParent();
            targetChild = scrollView;
        }

        if (parentView == null) {
            // 最终 fallback：使用 android.R.id.content 作为容器
            L.d("No scrollable view found, using content fallback");
            if (context instanceof Activity) {
                View contentView = ((Activity) context).findViewById(android.R.id.content);
                if (contentView instanceof ViewGroup) {
                    parentView = (ViewGroup) contentView;
                    targetChild = parentView.getChildCount() > 0 ? parentView.getChildAt(0) : null;
                }
            }
        }

        if (parentView == null) {
            L.d("Cannot find suitable parent for settings injection");
            return;
        }

        boolean isDarkMode = StyleUtils.isDarkMode(context);
        int defHPadding = DpUtils.dip2px(context, 15);

        // 创建设置入口视图
        LinearLayout settingsItemRootLayout = new LinearLayout(context);
        settingsItemRootLayout.setOrientation(LinearLayout.VERTICAL);
        settingsItemRootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsItemRootLayout.setTag(BuildConfig.APPLICATION_ID);

        LinearLayout itemHlinearLayout = new LinearLayout(context);
        itemHlinearLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemHlinearLayout.setWeightSum(1);
        itemHlinearLayout.setBackground(new XDrawable.Builder()
                .defaultColor(isDarkMode ? 0xFF191919 : Color.WHITE)
                .pressedColor(isDarkMode ? 0xFF1D1D1D : 0xFFE5E5E5)
                .create());
        itemHlinearLayout.setGravity(Gravity.CENTER_VERTICAL);
        itemHlinearLayout.setClickable(true);
        itemHlinearLayout.setOnClickListener(view -> new SettingsView(context).showInDialog());

        TextView itemNameText = new TextView(context);
        itemNameText.setTextColor(isDarkMode ? 0xFFD3D3D3 : 0xFF353535);
        itemNameText.setText(Lang.getString(R.id.app_settings_name));
        itemNameText.setGravity(Gravity.CENTER_VERTICAL);
        itemNameText.setPadding(DpUtils.dip2px(context, 16), 0, 0, 0);
        itemNameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, StyleUtils.TEXT_SIZE_BIG);

        TextView itemSummerText = new TextView(context);
        StyleUtils.apply(itemSummerText);
        itemSummerText.setText(BuildConfig.VERSION_NAME);
        itemSummerText.setGravity(Gravity.CENTER_VERTICAL);
        itemSummerText.setPadding(0, 0, defHPadding, 0);
        itemSummerText.setTextColor(isDarkMode ? 0xFF656565 : 0xFF999999);

        // 尝试匹配微信设置项样式
        try {
            View styleRefView = ViewUtils.findViewByText(targetView,
                    "通用", "一般", "General", "帮助与反馈", "幫助與意見回饋", "Help & Feedback",
                    "关于微信", "關於微信", "About WeChat",
                    "消息通知", "訊息通知", "Notifications");
            L.d("styleRefView for new UI", styleRefView);
            if (styleRefView instanceof TextView) {
                TextView refTextView = (TextView) styleRefView;
                float scale = itemNameText.getTextSize() / refTextView.getTextSize();
                itemNameText.setTextSize(TypedValue.COMPLEX_UNIT_PX, refTextView.getTextSize());
                itemSummerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, itemSummerText.getTextSize() / scale);
                itemNameText.setTextColor(refTextView.getCurrentTextColor());

                // 尝试复制设置项的背景
                try {
                    View refItemView = (View) refTextView.getParent();
                    // 向上查找带有背景的父视图
                    for (int i = 0; i < 5 && refItemView != null; i++) {
                        Drawable bg = refItemView.getBackground();
                        if (bg != null) {
                            Drawable.ConstantState cs = bg.getConstantState();
                            if (cs != null) {
                                itemHlinearLayout.setBackground(cs.newDrawable());
                                break;
                            }
                        }
                        if (refItemView.getParent() instanceof View) {
                            refItemView = (View) refItemView.getParent();
                        } else {
                            break;
                        }
                    }
                } catch (Exception e) {
                    L.e(e);
                }
            }
        } catch (Exception e) {
            L.e(e);
        }

        itemHlinearLayout.addView(itemNameText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        itemHlinearLayout.addView(itemSummerText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View lineView = new View(context);
        lineView.setBackgroundColor(isDarkMode ? 0xFF2E2E2E : 0xFFD5D5D5);

        settingsItemRootLayout.addView(lineView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        settingsItemRootLayout.addView(itemHlinearLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, DpUtils.dip2px(context, 55)));

        // 在目标视图上方插入设置入口
        if (targetChild != null) {
            int targetIndex = ViewUtils.findChildViewPosition(parentView, targetChild);
            if (targetIndex >= 0) {
                parentView.addView(settingsItemRootLayout, targetIndex);
            } else {
                parentView.addView(settingsItemRootLayout, 0);
            }
        } else {
            parentView.addView(settingsItemRootLayout, 0);
        }
        L.d("Settings entry injected into new UI successfully");
    }

    private void findRecyclerViews(ViewGroup parent, List<View> outList) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null) continue;
            // 检查类名及所有父类名，覆盖微信自定义 RecyclerView 子类
            if (isRecyclerView(child)) {
                outList.add(child);
            }
            if (child instanceof ViewGroup) {
                findRecyclerViews((ViewGroup) child, outList);
            }
        }
    }

    private void findScrollableViews(ViewGroup parent, List<View> outList) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null) continue;
            if (child instanceof ScrollView || child instanceof HorizontalScrollView) {
                outList.add(child);
            } else {
                // 检查类名（可能是 NestedScrollView 或其他自定义滚动容器）
                String className = child.getClass().getName();
                if (className.contains("ScrollView") || className.contains("NestedScroll")) {
                    outList.add(child);
                }
            }
            if (child instanceof ViewGroup) {
                findScrollableViews((ViewGroup) child, outList);
            }
        }
    }

    private boolean isRecyclerView(View view) {
        Class<?> clazz = view.getClass();
        while (clazz != null && clazz != View.class && clazz != Object.class) {
            String name = clazz.getName();
            if (name.contains("RecyclerView") || name.contains("recyclerview")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
}
