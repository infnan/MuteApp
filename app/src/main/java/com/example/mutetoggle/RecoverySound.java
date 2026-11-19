package com.example.mutetoggle;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

final class RecoverySound {
    private static final String TAG = "MuteToggle";
    private static final int BEEP_DURATION_MS = 120;

    private final Runnable onComplete;
    private final Runnable onFailure;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ToneGenerator beep;
    private boolean finished;

    RecoverySound(Runnable onComplete, Runnable onFailure) {
        this.onComplete = onComplete;
        this.onFailure = onFailure;
    }

    void play() {
        if (finished) {
            return;
        }
        clearResources();
        try {
            beep = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            if (!beep.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)) {
                throw new IllegalStateException("System beep rejected");
            }
            handler.postDelayed(this::complete, BEEP_DURATION_MS + 100);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot play recovery beep", exception);
            onFailure.run();
            complete();
        }
    }

    private void complete() {
        if (finished) {
            return;
        }
        stop();
        onComplete.run();
    }

    void stop() {
        finished = true;
        clearResources();
    }

    private void clearResources() {
        handler.removeCallbacksAndMessages(null);
        if (beep != null) {
            beep.release();
            beep = null;
        }
    }
}
