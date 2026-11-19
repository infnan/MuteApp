package com.example.mutetoggle;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

public final class ToggleActivity extends Activity {
    private static final String TAG = "MuteToggle";
    private static final long SETTLE_DELAY_MS = 250;
    private static final long MAX_SOUND_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MuteController controller;
    private Ringtone ringtone;
    private ToneGenerator fallbackTone;
    private long soundStartedAt;
    private boolean volumeWarning;

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
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.isNotificationPolicyAccessGranted()) {
            requestPolicyAccess();
            return;
        }
        controller = new MuteController(audio);
        try {
            int target = controller.toggle();
            handler.postDelayed(() -> verifyToggle(target), SETTLE_DELAY_MS);
        } catch (SecurityException exception) {
            Log.w(TAG, "Ringer permission denied", exception);
            requestPolicyAccess();
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void requestPolicyAccess() {
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
        try {
            if (!controller.hasRingerMode(target)) {
                showAndFinish(R.string.toggle_failed);
            } else if (target == AudioManager.RINGER_MODE_SILENT) {
                showAndFinish(R.string.mute_enabled);
            } else {
                controller.recoverZeroVolumes();
                handler.postDelayed(this::retryVolumeRecovery, SETTLE_DELAY_MS);
            }
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    private void retryVolumeRecovery() {
        try {
            if (!controller.hasRingerMode(AudioManager.RINGER_MODE_NORMAL)) {
                showAndFinish(R.string.toggle_failed);
                return;
            }
            controller.recoverZeroVolumes();
            handler.postDelayed(this::finishRecovery, SETTLE_DELAY_MS);
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    private void finishRecovery() {
        try {
            if (!controller.hasRingerMode(AudioManager.RINGER_MODE_NORMAL)) {
                showAndFinish(R.string.toggle_failed);
                return;
            }
            volumeWarning = !controller.areVolumesRecovered();
            show(volumeWarning ? R.string.volume_failed : R.string.mute_disabled);
            playSystemSound();
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    private void playSystemSound() {
        int[] defaults = {
                RingtoneManager.TYPE_NOTIFICATION,
                RingtoneManager.TYPE_RINGTONE,
                RingtoneManager.TYPE_ALARM
        };
        for (int type : defaults) {
            try {
                if (RingtoneManager.getActualDefaultRingtoneUri(this, type) == null) {
                    continue;
                }
                Uri uri = RingtoneManager.getDefaultUri(type);
                ringtone = RingtoneManager.getRingtone(getApplicationContext(), uri);
                if (ringtone == null) {
                    continue;
                }
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setLooping(false);
                }
                soundStartedAt = SystemClock.uptimeMillis();
                ringtone.play();
                handler.postDelayed(this::checkSound, 200);
                return;
            } catch (RuntimeException exception) {
                Log.w(TAG, "Default sound unavailable: " + type, exception);
                stopSound();
            }
        }
        playFallbackTone();
    }

    private void checkSound() {
        try {
            if (ringtone == null || !ringtone.isPlaying()) {
                finish();
            } else if (SystemClock.uptimeMillis() - soundStartedAt >= MAX_SOUND_MS) {
                stopSound();
                finish();
            } else {
                handler.postDelayed(this::checkSound, 150);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot monitor system sound", exception);
            stopSound();
            playFallbackTone();
        }
    }

    private void playFallbackTone() {
        try {
            fallbackTone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            if (!fallbackTone.startTone(ToneGenerator.TONE_PROP_ACK, 200)) {
                throw new IllegalStateException("System tone rejected");
            }
            handler.postDelayed(this::finish, 350);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot play fallback tone", exception);
            if (!volumeWarning) {
                show(R.string.sound_failed);
            }
            finish();
        }
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

    private void stopSound() {
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot stop system sound", exception);
            }
            ringtone = null;
        }
        if (fallbackTone != null) {
            fallbackTone.release();
            fallbackTone = null;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopSound();
        super.onDestroy();
    }
}
