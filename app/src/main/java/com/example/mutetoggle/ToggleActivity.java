package com.example.mutetoggle;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

public final class ToggleActivity extends Activity {
    private static final String TAG = "MuteToggle";
    private static final String POLICY_ACCESS_REQUESTED = "policy_access_requested";
    private static final long SETTLE_DELAY_MS = 250;
    private static final long CONFIRM_TIMEOUT_MS = 2000;
    private static final long VIBRATION_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MuteController controller;
    private RecoverySound recoverySound;
    private RuntimeException commandFailure;
    private long confirmationDeadline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (savedInstanceState != null) {
            finish();
            return;
        }
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) {
            showAndFinish(R.string.audio_unavailable);
            return;
        }
        if (audio.isVolumeFixed()) {
            showAndFinish(R.string.fixed_volume);
            return;
        }
        controller = new MuteController(audio, getApplicationContext());
        try {
            int target = controller.getTargetRingerMode();
            try {
                controller.setRingerMode(target);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Ringer command reported an error; checking actual state", exception);
                commandFailure = exception;
            }
            confirmationDeadline = SystemClock.uptimeMillis() + CONFIRM_TIMEOUT_MS;
            handler.postDelayed(() -> verifyToggle(target), SETTLE_DELAY_MS);
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void requestPolicyAccessOnce() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        try {
            if (notifications != null && notifications.isNotificationPolicyAccessGranted()) {
                showAndFinish(R.string.toggle_failed);
                return;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot query policy permission", exception);
        }
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        if (preferences.getBoolean(POLICY_ACCESS_REQUESTED, false)) {
            showAndFinish(R.string.permission_denied);
            return;
        }
        preferences.edit().putBoolean(POLICY_ACCESS_REQUESTED, true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            show(R.string.permission_required);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.w(TAG, "Policy access settings unavailable", exception);
            show(R.string.permission_unavailable);
        }
        finish();
    }

    private void verifyToggle(int target) {
        boolean confirmed = false;
        try {
            confirmed = controller.hasRingerMode(target);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot confirm ringer mode yet", exception);
        }
        if (confirmed) {
            if (target == AudioManager.RINGER_MODE_SILENT) {
                show(R.string.mute_enabled);
                vibrateConfirmation();
                handler.postDelayed(this::finish, VIBRATION_MS);
            } else {
                controller.recoverZeroVolumes();
                handler.postDelayed(this::retryVolumeRecovery, SETTLE_DELAY_MS);
            }
        } else if (SystemClock.uptimeMillis() < confirmationDeadline) {
            handler.postDelayed(() -> verifyToggle(target), SETTLE_DELAY_MS);
        } else if (commandFailure instanceof SecurityException) {
            requestPolicyAccessOnce();
        } else if (commandFailure != null) {
            fail(commandFailure);
        } else {
            Log.w(TAG, "Ringer command returned normally but state was not confirmed: " + target);
            showAndFinish(R.string.toggle_unconfirmed);
        }
    }

    private void vibrateConfirmation() {
        try {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "Vibrator unavailable");
                return;
            }
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE), attributes);
            } else {
                vibrator.vibrate(VIBRATION_MS, attributes);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Mute succeeded but confirmation vibration failed", exception);
        }
    }

    private void retryVolumeRecovery() {
        if (!isStillNormal()) {
            return;
        }
        controller.recoverZeroVolumes();
        handler.postDelayed(this::finishRecovery, SETTLE_DELAY_MS);
    }

    private void finishRecovery() {
        if (!isStillNormal()) {
            return;
        }
        boolean volumeWarning = !controller.areVolumesRecovered();
        show(volumeWarning ? R.string.volume_failed : R.string.mute_disabled);
        recoverySound = new RecoverySound(getApplicationContext(), this::finish, () -> {
            if (!volumeWarning) {
                show(R.string.sound_failed);
            }
        });
        recoverySound.play();
    }

    private boolean isStillNormal() {
        try {
            if (controller.hasRingerMode(AudioManager.RINGER_MODE_NORMAL)) {
                return true;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot recheck normal mode", exception);
        }
        showAndFinish(R.string.toggle_unconfirmed);
        return false;
    }

    private void show(int message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    private void showAndFinish(int message) {
        show(message);
        finish();
    }

    private void fail(RuntimeException exception) {
        Log.w(TAG, "Cannot toggle mute", exception);
        showAndFinish(R.string.toggle_failed);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recoverySound != null) {
            recoverySound.stop();
        }
        super.onDestroy();
    }
}
