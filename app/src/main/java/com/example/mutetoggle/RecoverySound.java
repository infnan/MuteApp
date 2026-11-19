package com.example.mutetoggle;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;

final class RecoverySound {
    private static final String TAG = "MuteToggle";
    private static final String SOUND_NAME = "zhytera";
    private static final int BEEP_DURATION_MS = 120;

    private final Context context;
    private final Runnable onComplete;
    private final Runnable onFailure;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private ToneGenerator beep;
    private boolean finished;

    RecoverySound(Context context, Runnable onComplete, Runnable onFailure) {
        this.context = context;
        this.onComplete = onComplete;
        this.onFailure = onFailure;
    }

    void play() {
        Uri sound = findSystemSound();
        if (sound == null) {
            playBeep();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setLooping(false);
            player.setOnPreparedListener(prepared -> {
                if (finished || prepared != player) {
                    return;
                }
                handler.removeCallbacksAndMessages(null);
                try {
                    prepared.start();
                    handler.postDelayed(this::complete, 2000);
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Cannot start zhytera", exception);
                    playBeep();
                }
            });
            player.setOnCompletionListener(completed -> complete());
            player.setOnErrorListener((failed, what, extra) -> {
                if (failed == player && !finished) {
                    Log.w(TAG, "Cannot play zhytera: " + what + "/" + extra);
                    playBeep();
                }
                return true;
            });
            player.setDataSource(context, sound);
            player.prepareAsync();
            handler.postDelayed(this::playBeep, 1500);
        } catch (IOException | RuntimeException exception) {
            Log.w(TAG, "Cannot open zhytera", exception);
            playBeep();
        }
    }

    private Uri findSystemSound() {
        Uri collection = MediaStore.Audio.Media.INTERNAL_CONTENT_URI;
        String[] columns = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME
        };
        try (Cursor cursor = context.getContentResolver().query(
                collection, columns, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (matchesSoundName(cursor.getString(1))
                        || matchesSoundName(cursor.getString(2))) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0));
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot search system sounds; using beep", exception);
        }
        return null;
    }

    private static boolean matchesSoundName(String value) {
        if (value == null) {
            return false;
        }
        String name = value.trim();
        int extension = name.lastIndexOf('.');
        if (extension > 0) {
            name = name.substring(0, extension);
        }
        return SOUND_NAME.equalsIgnoreCase(name);
    }

    private void playBeep() {
        if (finished) {
            return;
        }
        clearResources();
        try {
            beep = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
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
        if (player != null) {
            player.setOnPreparedListener(null);
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
            player.release();
            player = null;
        }
        if (beep != null) {
            beep.release();
            beep = null;
        }
    }
}
