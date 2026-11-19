package com.example.mutetoggle;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

final class MuteController {
    private static final String TAG = "MuteToggle";
    private static final int RECOVERY_VOLUME = 3;
    private static final int[] VOLUME_STREAMS = {
            AudioManager.STREAM_VOICE_CALL
    };

    private final AudioManager audio;
    private final Context context;

    MuteController(AudioManager audio, Context context) {
        this.audio = audio;
        this.context = context;
    }

    int getTargetRingerMode() {
        int broadcastMode = getBroadcastRingerMode();
        int current = broadcastMode >= 0 ? broadcastMode : audio.getRingerMode();
        return current == AudioManager.RINGER_MODE_NORMAL
                ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
    }

    void setRingerMode(int target) {
        audio.setRingerMode(target);
    }

    boolean hasRingerMode(int expected) {
        return audio.getRingerMode() == expected || getBroadcastRingerMode() == expected;
    }

    private int getBroadcastRingerMode() {
        try {
            Intent state = context.registerReceiver(null,
                    new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION));
            if (state != null) {
                int mode = state.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
                if (mode >= AudioManager.RINGER_MODE_SILENT
                        && mode <= AudioManager.RINGER_MODE_NORMAL) {
                    return mode;
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot read ringer mode broadcast", exception);
        }
        return -1;
    }

    void recoverZeroVolumes() {
        for (int stream : VOLUME_STREAMS) {
            try {
                if (audio.getStreamVolume(stream) != 0) {
                    continue;
                }
                int maximum = audio.getStreamMaxVolume(stream);
                int minimum = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? audio.getStreamMinVolume(stream) : 0;
                int volume = Math.min(maximum, Math.max(minimum, RECOVERY_VOLUME));
                if (volume > 0) {
                    audio.setStreamVolume(stream, volume, 0);
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot recover stream " + stream, exception);
            }
        }
    }

    boolean areVolumesRecovered() {
        boolean recovered = true;
        for (int stream : VOLUME_STREAMS) {
            try {
                if (audio.getStreamVolume(stream) == 0) {
                    Log.w(TAG, "Stream is still zero: " + stream);
                    recovered = false;
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot verify stream " + stream, exception);
                recovered = false;
            }
        }
        return recovered;
    }
}
