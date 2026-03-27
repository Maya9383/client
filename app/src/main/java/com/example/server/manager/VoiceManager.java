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
 * 負責 SpeechRecognizer 的生命週期與監聽邏輯 (已加回進階邏輯)
 */
public class VoiceManager {
    private static final String TAG = "VoiceManager";
    private final Context context;
    private SpeechRecognizer speechRecognizer;
    private final Intent recognizerIntent;
    private final VoiceWakeupCallback callback;

    // ★ 關鍵防護：嚴格控制是否允許在背景自動重啟麥克風
    private boolean isListeningAllowed = false;

    public interface VoiceWakeupCallback { void onWakeWordDetected(); }

    public VoiceManager(Context context, VoiceWakeupCallback callback) {
        this.context = context;
        this.callback = callback;

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
        // ★ 加回吵雜環境攔截功能：要求系統即時回傳聽到的片段
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
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
                boolean handled = false;

                if (matches != null) {
                    for (String text : matches) {
                        if (text.contains("小美") || text.contains("美")) {
                            Log.d(TAG, "偵測到關鍵字: " + text);
                            handled = true;
                            stopListening(); // ★ 觸發後立刻銷毀麥克風，防呆
                            callback.onWakeWordDetected(); // 通知 MainActivity
                            break;
                        }
                    }
                }
                // 若沒聽到關鍵字，且目前「允許監聽」，才安全重啟
                if (!handled && isListeningAllowed && speechRecognizer != null) {
                    startListeningWakeWord();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // ★ 加回吵雜環境攔截邏輯：不等整句講完，聽到就立刻觸發
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    for (String text : matches) {
                        if (text.contains("小美") || text.contains("美")) {
                            Log.d(TAG, "【吵雜攔截】即時偵測到關鍵字: " + text);
                            stopListening(); // ★ 攔截後立刻停止
                            callback.onWakeWordDetected();
                            break;
                        }
                    }
                }
            }

            @Override
            public void onError(int error) {
                // 只有在「允許監聽」的狀態下，才處理錯誤重啟
                if (isListeningAllowed && speechRecognizer != null) {
                    // 錯誤碼 6 (Timeout) 或 7 (No Match) 是環境噪音，直接無縫重啟
                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                        startListeningWakeWord();
                    } else if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        // 其他錯誤給予 500ms 緩衝
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isListeningAllowed && speechRecognizer != null) startListeningWakeWord();
                        }, 500);
                    }
                }
            }

            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    /**
     * 啟動關鍵字監聽 (加回無聲啟動)
     */
    public void startListeningWakeWord() {
        isListeningAllowed = true; // ★ 開放重啟權限

        if (speechRecognizer == null) {
            initRecognizer();
        }

        // ★ 加回無聲啟動機制：消除惱人的 Google 提示音
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
        audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);

        try {
            speechRecognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            Log.e(TAG, "啟動監聽失敗", e);
        }

        // 延遲 600 毫秒後恢復音量
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
        }, 600);
    }

    /**
     * 徹底關閉監聽並釋放麥克風
     */
    public void stopListening() {
        isListeningAllowed = false; // ★ 嚴格收回重啟權限，防止 onError 幽靈重啟

        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception e) {
                Log.e(TAG, "銷毀辨識器失敗", e);
            } finally {
                speechRecognizer = null;
            }
        }
    }

    /**
     * 播放開始/結束的自定義提示音
     */
    public void playCustomBeep(boolean isStart) {
        try {
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            int tone = isStart ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_SUP_RADIO_ACK;
            toneGen.startTone(tone, 200);
            new Handler(Looper.getMainLooper()).postDelayed(toneGen::release, 300);
        } catch (Exception e) {
            Log.e(TAG, "播放音效失敗", e);
        }
    }
}