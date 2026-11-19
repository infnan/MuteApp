package com.example.mutetoggle;

import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

final class MuteController {
    private static final String TAG = "MuteToggle";
    private static final int RECOVERY_VOLUME = 3;
    private static final int[] VOLUME_STREAMS = {
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM
    };

    private final AudioManager audio;

    MuteController(AudioManager audio) {
        this.audio = audio;
    }

    int toggle() {
        int target = audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
        audio.setRingerMode(target);
        return target;
    }

    boolean hasRingerMode(int expected) {
        return audio.getRingerMode() == expected;
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
