/*
 * Copyright (C) 2019 CypherOS
 * Copyright (C) 2020 Paranoid Android
 * Copyright (C) 2020 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.tristate;

import static android.view.Surface.ROTATION_90;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManagerGlobal;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tristate.TriStateUiController;
import com.android.systemui.tristate.TriStateUiController.UserActivityListener;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.Callbacks;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

public class TriStateUiControllerImpl implements TriStateUiController,
        ConfigurationController.ConfigurationListener, TunerService.Tunable {


    private static String TAG = "TriStateUiControllerImpl";

    public static final String ALERT_SLIDER_NOTIFICATIONS =
            "system:" + Settings.System.ALERT_SLIDER_NOTIFICATIONS;

    private static final int MSG_DIALOG_SHOW = 1;
    private static final int MSG_DIALOG_DISMISS = 2;
    private static final int MSG_RESET_SCHEDULE = 3;
    private static final int MSG_STATE_CHANGE = 4;

    private static final int MODE_NORMAL = AudioManager.RINGER_MODE_NORMAL;
    private static final int MODE_SILENT = AudioManager.RINGER_MODE_SILENT;
    private static final int MODE_VIBRATE = AudioManager.RINGER_MODE_VIBRATE;

    private static final int POSITION_TOP = 0;
    private static final int POSITION_MIDDLE = 1;
    private static final int POSITION_BOTTOM = 2;

    private static final String EXTRA_SLIDER_POSITION = "position";

    private static final int TRI_STATE_UI_POSITION_LEFT = 0;
    private static final int TRI_STATE_UI_POSITION_RIGHT = 1;

    private static final long DIALOG_TIMEOUT = 2000;
    private static final long DIALOG_DELAY = 300;
    private int mTextColor = 0;
    private int mIconColor = 0;

    private Context mContext;
    private final VolumeDialogController mVolumeDialogController;
    private final Callbacks mVolumeDialogCallback = new Callbacks() {
        @Override
        public void onShowRequested(int reason) { }

        @Override
        public void onDismissRequested(int reason) { }

        @Override
        public void onScreenOff() { }

        @Override
        public void onStateChanged(State state) { }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) { }

        @Override
        public void onShowVibrateHint() { }

        @Override
        public void onShowSilentHint() { }

        @Override
        public void onShowSafetyWarning(int flags) { }

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) { }

        @Override
        public void onCaptionComponentStateChanged(
                Boolean isComponentEnabled, Boolean fromTooltip) {}

        @Override
        public void onConfigurationChanged() {
            updateThemeColor();
        }
    };

    private int mDensity;
    private Dialog mDialog;
    private int mDialogPosition;
    private ViewGroup mDialogView;
    private final H mHandler;
    private UserActivityListener mListener;
    private int mBackgroundColor = 0;
    private ImageView mTriStateIcon;
    private TextView mTriStateText;
    private int mTriStateMode = -1;
    private int mPosition = -1;
    private Window mWindow;
    private LayoutParams mWindowLayoutParams;
    private int mWindowType;
    private String mIntentAction;
    private boolean mIntentActionSupported;
    private boolean mRingModeChanged, mSliderPositionChanged;
    private boolean mAlertSliderNotification;

    private final BroadcastReceiver mRingerStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mAlertSliderNotification) {
                mRingModeChanged = false;
                mSliderPositionChanged = false;
                return;
            }

            String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
                mHandler.sendEmptyMessage(MSG_STATE_CHANGE);
                mRingModeChanged = true;
            } else if (action.equals(mIntentAction)) {
                mSliderPositionChanged = true;
                mPosition = intent.getIntExtra(EXTRA_SLIDER_POSITION, -1);
            }

            if (mRingModeChanged && mAlertSliderNotification &&
                        (mSliderPositionChanged || !mIntentActionSupported)) {
                mRingModeChanged = false;
                mSliderPositionChanged = false;
                if (mTriStateMode != -1) {
                    mHandler.sendEmptyMessageDelayed(MSG_DIALOG_SHOW, (long) DIALOG_DELAY); 
               }
            }
        }
    };

    private final class H extends Handler {
        private TriStateUiControllerImpl mUiController;

        public H(TriStateUiControllerImpl uiController) {
            super(Looper.getMainLooper());
            mUiController = uiController;
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DIALOG_SHOW:
                    mUiController.handleShow();
                    return;
                case MSG_DIALOG_DISMISS:
                    mUiController.handleDismiss();
                    return;
                case MSG_RESET_SCHEDULE:
                    mUiController.handleResetTimeout();
                    return;
                case MSG_STATE_CHANGE:
                    mUiController.handleStateChanged();
                    return;
                default:
                    return;
            }
        }
    }

    public TriStateUiControllerImpl(Context context) {
        mContext =
                new ContextThemeWrapper(context, R.style.qs_theme);
        mHandler = new H(this);
        mVolumeDialogController = (VolumeDialogController) Dependency.get(VolumeDialogController.class);
        mIntentAction = mContext.getResources().getString(com.android.internal.R.string.config_alertSliderIntent);
        mIntentActionSupported = mIntentAction != null && !mIntentAction.isEmpty();

        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        if (mIntentActionSupported)
            filter.addAction(mIntentAction);
        mContext.registerReceiver(mRingerStateReceiver, filter);

        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, ALERT_SLIDER_NOTIFICATIONS);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case ALERT_SLIDER_NOTIFICATIONS:
                mAlertSliderNotification
                        = TunerService.parseIntegerSwitch(newValue, true);
                mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
                break;
            default:
                break;
        }
    }

    @Override
    public void onUiModeChanged() {
        mContext.getTheme().applyStyle(mContext.getThemeResId(), true);
    }

    public void init(int windowType, UserActivityListener listener) {
        mWindowType = windowType;
        mDensity = mContext.getResources().getConfiguration().densityDpi;
        mListener = listener;
        ((ConfigurationController) Dependency.get(ConfigurationController.class)).addCallback(this);
        mVolumeDialogController.addCallback(mVolumeDialogCallback, mHandler);
        initDialog();
    }

    public void destroy() {
        ((ConfigurationController) Dependency.get(ConfigurationController.class)).removeCallback(this);
        mVolumeDialogController.removeCallback(mVolumeDialogCallback);
        mContext.unregisterReceiver(mRingerStateReceiver);
    }

    private void initDialog() {
        mDialog = new Dialog(mContext, R.style.qs_theme);
        mWindow = mDialog.getWindow();
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mWindow.clearFlags(LayoutParams.FLAG_DIM_BEHIND
                | LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mWindow.setType(LayoutParams.TYPE_VOLUME_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        mDialog.setCanceledOnTouchOutside(false);
        mWindowLayoutParams = mWindow.getAttributes();
        mWindowLayoutParams.type = mWindowType;
        mWindowLayoutParams.format = -3;
        mWindowLayoutParams.setTitle(TriStateUiControllerImpl.class.getSimpleName());
        mWindowLayoutParams.gravity = 53;
        mWindowLayoutParams.y = mDialogPosition;
        mWindow.setAttributes(mWindowLayoutParams);
        mWindow.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        mDialog.setContentView(R.layout.tri_state_dialog);
        mDialogView = (ViewGroup) mDialog.findViewById(R.id.tri_state_layout);
        mTriStateIcon = (ImageView) mDialog.findViewById(R.id.tri_state_icon);
        mTriStateText = (TextView) mDialog.findViewById(R.id.tri_state_text);
        updateThemeColor();
    }

    private void updateTriStateLayout() {
        if (mContext != null) {
            int iconId = 0;
            int textId = 0;
            int bg = 0;
            Resources res = mContext.getResources();
            if (res != null) {
                int positionY;
                int positionY2 = mWindowLayoutParams.y;
                int positionX = mWindowLayoutParams.x;
                int gravity = mWindowLayoutParams.gravity;
                switch (mTriStateMode) {
                    case MODE_SILENT:
                        iconId = R.drawable.ic_volume_ringer_mute;
                        textId = R.string.volume_ringer_status_silent;
                        break;
                    case MODE_VIBRATE:
                        iconId = R.drawable.ic_volume_ringer_vibrate;
                        textId = R.string.volume_ringer_status_vibrate;
                        break;
                    case MODE_NORMAL:
                        iconId = R.drawable.ic_volume_ringer;
                        textId = R.string.volume_ringer_status_normal;
                        break;
                }
                int triStatePos = res.getInteger(com.android.internal.R.integer.config_alertSliderLocation);
                boolean isTsKeyRight = true;
                if (triStatePos == TRI_STATE_UI_POSITION_LEFT) {
                    isTsKeyRight = false;
                } else if (triStatePos == TRI_STATE_UI_POSITION_RIGHT) {
                    isTsKeyRight = true;
                }

                Display display = DisplayManagerGlobal.getInstance().getRealDisplay(0);
                int orientationType = -1;
                if (display != null) {
                    orientationType = display.getRotation();
                }

                switch (orientationType) {
                    case ROTATION_90:
                        if (isTsKeyRight) {
                            gravity = 51;
                        } else {
                            gravity = 83;
                        }
                        positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep_land);
                        if (isTsKeyRight) {
                            positionY2 += res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        }
                        if (mPosition == POSITION_TOP) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (mPosition == POSITION_MIDDLE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (mPosition == POSITION_BOTTOM) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        } else if (mTriStateMode == MODE_SILENT) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (mTriStateMode == MODE_VIBRATE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (mTriStateMode == MODE_NORMAL) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        }
                        bg = R.drawable.dialog_tri_state_middle_bg;
                        break;
                    case ROTATION_180:
                        if (isTsKeyRight) {
                            gravity = 83;
                        } else {
                            gravity = 85;
                        }
                        positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep);
                        positionY = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position)
                                + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        if (mPosition >= 0) {
                            if (mPosition != POSITION_TOP) {
                                if (mPosition != POSITION_MIDDLE) {
                                    if (mPosition == POSITION_BOTTOM) {
                                        positionY = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position)
                                            + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                                    }
                                    bg = R.drawable.dialog_tri_state_middle_bg;
                                    break;
                                }
                                positionY = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position)
                                    + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                            }
                        } else if (mTriStateMode != MODE_SILENT) {
                            if (mTriStateMode != MODE_VIBRATE) {
                                if (mTriStateMode == MODE_NORMAL) {
                                    positionY = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position)
                                        + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                                }
                                bg = R.drawable.dialog_tri_state_middle_bg;
                                break;
                            }
                            positionY = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position)
                                + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        }
                        positionY2 = positionY;
                        bg = R.drawable.dialog_tri_state_middle_bg;
                        break;
                    case ROTATION_270:
                        if (isTsKeyRight) {
                            gravity = 85;
                        } else {
                            gravity = 53;
                        }
                        positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep_land);
                        if (!isTsKeyRight) {
                            positionY2 += res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        }
                        if (mPosition == POSITION_TOP) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (mPosition == POSITION_MIDDLE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (mPosition == POSITION_BOTTOM) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        } else if (mTriStateMode == MODE_SILENT) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (mTriStateMode == MODE_VIBRATE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (mTriStateMode == MODE_NORMAL) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        }
                        bg = R.drawable.dialog_tri_state_middle_bg;
                        break;
                    default:
                        if (isTsKeyRight) {
                            gravity = 53;
                        } else {
                            gravity = 51;
                        }
                        positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep);

                        if (mPosition >= 0) {
                            if (mPosition != POSITION_TOP) {
                                if (mPosition != POSITION_MIDDLE) {
                                    if (mPosition == POSITION_BOTTOM) {
                                        positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position)
                                            + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                                        bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_down_bg : R.drawable.left_dialog_tri_state_down_bg;
                                        break;
                                    }
                                }
                                positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position)
                                    + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                                bg = R.drawable.dialog_tri_state_middle_bg;
                                break;
                            }
                        } else if (mTriStateMode != MODE_SILENT) {
                            if (mTriStateMode != MODE_VIBRATE) {
                                if (mTriStateMode == MODE_NORMAL) {
                                    positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position)
                                        + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                                    bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_down_bg : R.drawable.left_dialog_tri_state_down_bg;
                                    break;
                                }
                            }
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position)
                                + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                            bg = R.drawable.dialog_tri_state_middle_bg;
                            break;
                        }
                        positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position)
                            + res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_up_bg : R.drawable.left_dialog_tri_state_up_bg;
                        break;
                }
                if (mTriStateMode != -1) {
                    if (mTriStateIcon != null) {
                        mTriStateIcon.setImageResource(iconId);
                    }
                    if (mTriStateText != null) {
                        String inputText = res.getString(textId);
                        if (inputText != null && mTriStateText.length() == inputText.length()) {
                            StringBuilder sb = new StringBuilder();
                            sb.append(inputText);
                            sb.append(" ");
                            inputText = sb.toString();
                        }
                        mTriStateText.setText(inputText);
                    }
                    if (mDialogView != null) {
                        mDialogView.setBackgroundDrawable(res.getDrawable(bg));
                        mBackgroundColor = getAttrColor(android.R.attr.colorPrimary);
                        mDialogView.setBackgroundTintList(ColorStateList.valueOf(mBackgroundColor));
                    }
                    mDialogPosition = positionY2;
                }
                positionY = res.getDimensionPixelSize(R.dimen.tri_state_dialog_padding);
                mWindowLayoutParams.gravity = gravity;
                mWindowLayoutParams.y = positionY2 - positionY;
                mWindowLayoutParams.x = positionX - positionY;
                mWindow.setAttributes(mWindowLayoutParams);
                mHandler.sendEmptyMessageDelayed(MSG_RESET_SCHEDULE, DIALOG_TIMEOUT);
            }
        }
    }

    private void handleShow() {
        mHandler.removeMessages(MSG_DIALOG_SHOW);
        if (!mDialog.isShowing()) {
            updateTriStateLayout();
            mDialog.show();
            if (mListener != null) {
                mListener.onTriStateUserActivity();
            }
            mHandler.sendEmptyMessageDelayed(MSG_RESET_SCHEDULE, DIALOG_TIMEOUT);
        }
    }

    private void handleDismiss() {
        mHandler.removeMessages(MSG_DIALOG_DISMISS);
        if (mDialog == null) {
            return;
        }
        if (mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }

    private void handleStateChanged() {
        mHandler.removeMessages(MSG_STATE_CHANGE);
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = am.getRingerModeInternal();
        if (ringerMode != mTriStateMode) {
            mTriStateMode = ringerMode;
            if (mListener != null) {
                mListener.onTriStateUserActivity();
            }
        }
    }

    public void handleResetTimeout() {
        mHandler.removeMessages(MSG_RESET_SCHEDULE);
        mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
        if (mListener != null) {
            mListener.onTriStateUserActivity();
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        initDialog();
    }

    public int getAttrColor(int attr) {
        TypedArray ta = mContext.obtainStyledAttributes(new int[]{attr});
        int colorAccent = ta.getColor(0, 0);
        ta.recycle();
        return colorAccent;
    }
    
    public void updateThemeColor() {
        mIconColor = getAttrColor(android.R.attr.colorAccent);
        mTextColor = getAttrColor(android.R.attr.textColorPrimary);
        if (mTriStateText != null) {
            mTriStateText.setTextColor(mTextColor);
        }
        if (mTriStateIcon != null) {
            mTriStateIcon.setColorFilter(mIconColor);
        }
    }

    @Override
    public void onOverlayChanged() {
        updateThemeColor();
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        updateTriStateLayout();
        updateThemeColor();
    }
}
