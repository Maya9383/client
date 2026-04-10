package com.example.server.manager;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LlmAudioPlayer {
    private static final String TAG = "LlmAudioPlayer";
    private final Context context;
    private MediaPlayer mediaPlayer;
    private final ConcurrentLinkedQueue<File> audioQueue = new ConcurrentLinkedQueue<>();
    private boolean isPlaying = false;
    private final Runnable onAllFinished;

    // 用來處理「防斷線緩衝」的計時器
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable finishRunnable;

    public LlmAudioPlayer(Context context, Runnable onAllFinished) {
        this.context = context;
        this.onAllFinished = onAllFinished;
    }

    public void addToQueue(byte[] audioData) {
        if (audioData == null || audioData.length == 0) return;

        try {
            // 🌟 收到新聲音時，立刻取消「結束計時器」，避免提早中斷
            if (finishRunnable != null) {
                mainHandler.removeCallbacks(finishRunnable);
                finishRunnable = null;
            }

            // 存成暫存檔交給 MediaPlayer 處理（這會自動完美解析 MP3 或 WAV 格式，絕無雜音）
            File tempFile = File.createTempFile("ai_voice_", ".wav", context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }
            audioQueue.offer(tempFile);

            if (!isPlaying) {
                playNext();
            }
        } catch (Exception e) {
            Log.e(TAG, "儲存音訊失敗", e);
        }
    }

    private void playNext() {
        File nextFile = audioQueue.poll();

        if (nextFile == null) {
            // 🌟 核心修復：佇列空了不代表 AI 講完話了（可能是網路稍微卡住）
            // 我們給予 1.5 秒的緩衝期。如果 1.5 秒內沒有新的聲音進來，才判定 AI 講完話。
            finishRunnable = () -> {
                isPlaying = false;
                if (onAllFinished != null) {
                    onAllFinished.run(); // 觸發繼續錄音
                }
            };
            mainHandler.postDelayed(finishRunnable, 1500);
            return;
        }

        isPlaying = true;
        try {
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            mediaPlayer.reset();
            mediaPlayer.setDataSource(nextFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            // 播完後立刻刪除暫存檔，並嘗試播下一首
            mediaPlayer.setOnCompletionListener(mp -> {
                if (nextFile.exists()) nextFile.delete();
                playNext();
            });
        } catch (Exception e) {
            Log.e(TAG, "播放檔案失敗", e);
            if (nextFile.exists()) nextFile.delete();
            playNext();
        }
    }

    public void pauseAudio() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public void resumeAudio() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        } else if (!isPlaying && !audioQueue.isEmpty()) {
            playNext();
        }
    }

    public void stopAll() {
        isPlaying = false;
        // 清除結束計時器
        if (finishRunnable != null) {
            mainHandler.removeCallbacks(finishRunnable);
            finishRunnable = null;
        }
        // 停止播放並釋放資源
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "釋放 MediaPlayer 失敗", e);
            }
            mediaPlayer = null;
        }
        // 清理殘留檔案
        while (!audioQueue.isEmpty()) {
            File f = audioQueue.poll();
            if (f != null && f.exists()) f.delete();
        }
    }
    // 在 LlmAudioPlayer 類別裡面加入這個方法
    public boolean isPlaying() {
        // 假設你內部的 MediaPlayer 變數叫做 mediaPlayer
        // 或者如果你內部有一個 boolean 叫 isPlaying，就回傳它
        return (mediaPlayer != null && mediaPlayer.isPlaying());
    }
}