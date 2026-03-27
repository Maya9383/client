package com.example.server.manager;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.Queue;

// AI 語音播放器 //
public class LlmAudioPlayer {
    private final Context context;
    private MediaPlayer mediaPlayer;
    private final Queue<File> audioQueue = new LinkedList<>();
    private boolean isPlaying = false;
    private final Runnable onAllFinished;

    public LlmAudioPlayer(Context context, Runnable onAllFinished) {
        this.context = context;
        this.onAllFinished = onAllFinished;
    }

    public void addToQueue(byte[] audioData) {
        try {
            File tempFile = File.createTempFile("ai_voice_", ".mp3", context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }
            audioQueue.offer(tempFile);
            if (!isPlaying) playNext();
        } catch (Exception e) { Log.e("Player", "儲存失敗", e); }
    }

    private void playNext() {
        File nextFile = audioQueue.poll();
        if (nextFile == null) {
            isPlaying = false;
            if (onAllFinished != null) onAllFinished.run();
            return;
        }

        isPlaying = true;
        try {
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            mediaPlayer.reset();
            mediaPlayer.setDataSource(nextFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(mp -> {
                nextFile.delete();
                playNext();
            });
        } catch (Exception e) { playNext(); }
    }

    public void stopAll() {
        isPlaying = false;
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        audioQueue.clear();
    }
}