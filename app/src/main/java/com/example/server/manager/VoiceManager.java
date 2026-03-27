package com.example.server.manager;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * 負責 SpeechRecognizer 的生命週期與「無聲啟動」監聽邏輯
 */
public class VoiceManager {
    private static final String TAG = "VoiceManager";
    private final Context context;
    private SpeechRecognizer speechRecognizer;
    private final Intent recognizerIntent;
    private final VoiceWakeupCallback callback;
    private final AudioManager audioManager;

    public interface VoiceWakeupCallback { void onWakeWordDetected(); }

    public VoiceManager(Context context, VoiceWakeupCallback callback) {
        this.context = context;
        this.callback = callback;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
    }

    /**
     * 初始化辨識器並設定監聽關鍵字邏輯
     */
    private void initRecognizer() {
        if (speechRecognizer != null) {
            stopListening();
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    for (String text : matches) {
                        // 檢查辨識結果是否包含關鍵字
                        if (text.contains("小美") || text.contains("美")) {
                            Log.d(TAG, "偵測到關鍵字: " + text);
                            callback.onWakeWordDetected(); // 通知 MainActivity 啟動正式錄音
                            return;
                        }
                    }
                }
                // 若沒聽到關鍵字，重啟監聽（持續等待）
                if (speechRecognizer != null) startListeningWithoutBeep();
            }

            @Override
            public void onError(int error) {
                // 排除正在辨識中的錯誤，其餘狀況延遲重啟
                Log.e(TAG, "辨識錯誤代碼: " + error);
                if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY && speechRecognizer != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (speechRecognizer != null) startListeningWithoutBeep();
                    }, 500);
                }
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    /**
     * 無聲啟動關鍵字監聽 (Keyword Spotting)
     */
    public void startListeningWithoutBeep() {
        if (speechRecognizer == null) {
            initRecognizer();
        }

        // 修改：只靜音系統音量（嗶聲），不影響音樂聲道
        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);

        speechRecognizer.startListening(recognizerIntent);

        // 短暫延遲後恢復系統音量
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
        }, 800);
    }

    /**
     * 徹底關閉監聽並釋放麥克風
     */
    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
            speechRecognizer.destroy(); // 關鍵：銷毀實例才能讓其他錄音模組存取麥克風
            speechRecognizer = null;
        }
    }

    /**
     * 播放錄音開始/結束的自定義提示音 (STREAM_MUSIC 聲道)
     */
    public void playCustomBeep(boolean isStart) {
        ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        int tone = isStart ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_SUP_RADIO_ACK;
        toneGen.startTone(tone, 200);
        new Handler(Looper.getMainLooper()).postDelayed(toneGen::release, 300);
    }
}