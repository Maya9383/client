package com.example.server;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.ViewFlipper;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.server.manager.RobotManager;
import com.example.server.manager.VoiceManager;
import com.example.server.manager.ChatWebSocketManager;
import com.example.server.manager.LlmAudioPlayer;
import com.example.server.utils.AudioRecorderHelper;
import com.google.android.material.card.MaterialCardView;

public class MainActivity extends AppCompatActivity {
    private ViewFlipper viewFlipper;
    private VideoView videoView;
    private FrameLayout layoutVisualizer;
    private TextView tvRunningStatus;
    private TextView tvCountdown;
    private LinearLayout bottomButtonContainer;
    private View pauseOverlay;

    private final String serverIp = "192.168.0.110";
    private final String serverPort = "8000";
    private final String ROBOT_ID = "pennybot-a7fe96";

    // 🌟 新增：用來儲存當前任務的 ID，用於後續的取消動作
    private String currentMissionId = "";

    private RobotManager robotManager;
    private VoiceManager voiceManager;
    private ChatWebSocketManager wsManager;
    private LlmAudioPlayer audioPlayer;
    private AudioRecorderHelper recorderHelper;

    private boolean isChatPaused = false;
    private final Handler logicHandler = new Handler(Looper.getMainLooper());

    private CountDownTimer waitTimer;
    private Runnable statusPollingRunnable;
    private Runnable hideControlsRunnable;

    // 🌟 新增：一個專門用來「延遲啟動輪詢」的 Runnable，避免讀到上一次的舊狀態
    private final Runnable delayedPollingRunnable = this::startStatusPolling;

    private final int PAGE_IDLE = 0;
    private final int PAGE_CONTROL = 1;
    private final int PAGE_CHAT = 2;
    private final int PAGE_RUNNING = 3;
    private final int PAGE_RETURNING = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_main);

        initManagers();
        initUI();
        checkPermissions();

        robotManager.playNextPromoVideo(videoView, () -> viewFlipper.getDisplayedChild() == PAGE_IDLE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int currentPage = viewFlipper.getDisplayedChild();
                if (currentPage == PAGE_CHAT) closeChatPage();
                else if (currentPage == PAGE_CONTROL) { resetControlLogic(); switchPage(PAGE_IDLE); }
                else if (currentPage != PAGE_RUNNING && currentPage != PAGE_RETURNING) finish();
            }
        });
    }
    // 新增：重置對話邏輯 (停止播放、停止收音，並重啟連接與收音)
    private void resetConversation() {
        // 停止正在播放的語音
        if (audioPlayer != null) audioPlayer.stopAll();
        // 停止當前的錄音
        if (recorderHelper != null) recorderHelper.stopRecording();
        stopVisualizerUI(false);

        // 斷開並重新連接 WebSocket 以清空伺服器端的上下文 (依據你的後端實作而定)
        if (wsManager != null) {
            wsManager.disconnect();
            wsManager.connect();
        }

        // 重新開始收音
        startChatRecording(true);
    }

    // 新增：切換對話暫停 / 繼續狀態
    private void toggleChatPause() {
        isChatPaused = !isChatPaused;

        if (isChatPaused) {
            // 進入暫停：顯示遮罩、停止播放與收音
            if (pauseOverlay != null) pauseOverlay.setVisibility(View.VISIBLE);
            if (audioPlayer != null) audioPlayer.stopAll();
            if (recorderHelper != null) recorderHelper.stopRecording();
            stopVisualizerUI(false);
        } else {
            // 恢復繼續：隱藏遮罩、重新開始收音
            if (pauseOverlay != null) pauseOverlay.setVisibility(View.GONE);
            startChatRecording(true);
        }
    }
    private void initUI() {
        viewFlipper = findViewById(R.id.viewFlipper);
        videoView = findViewById(R.id.videoView);
        layoutVisualizer = findViewById(R.id.layoutVisualizer);
        tvRunningStatus = findViewById(R.id.tvRunningStatus);
        tvCountdown = findViewById(R.id.tvCountdown);
        bottomButtonContainer = findViewById(R.id.bottomButtonContainer);
        pauseOverlay = findViewById(R.id.pauseOverlay);

        videoView.setOnCompletionListener(mp -> {
            if (viewFlipper.getDisplayedChild() == PAGE_IDLE) robotManager.playNextPromoVideo(videoView, () -> true);
        });

        findViewById(R.id.btnControl).setOnClickListener(v -> switchPage(PAGE_CONTROL));
        findViewById(R.id.btnStartChat).setOnClickListener(v -> openChatPage(false));
        findViewById(R.id.btnDestLobby).setOnClickListener(v -> sendNewMission("T1", false));
        findViewById(R.id.btnDestStandby).setOnClickListener(v -> sendNewMission("T2", false));
        findViewById(R.id.btnCharge).setOnClickListener(v -> performCharge());
        findViewById(R.id.btnBackFromControl).setOnClickListener(v -> { resetControlLogic(); switchPage(PAGE_IDLE); });

        // 🌟 修正：點擊取消按鈕時，呼叫 handleCancelAction() 並帶上儲存的 missionId
        findViewById(R.id.btnCancelMission).setOnClickListener(v -> handleCancelAction());

        // ======== 新增：對話頁面 (Chat Page) 按鈕與互動邏輯 ========

        // 1. 左上角返回首頁按鈕
        findViewById(R.id.btnBackFromChat).setOnClickListener(v -> closeChatPage());

        // 2. 下方右側叉叉按鈕：終止對話 (行為同返回)
        findViewById(R.id.btnBottomStopCard).setOnClickListener(v -> closeChatPage());

        // 3. 下方左側麥克風按鈕：停止輸出並重置對話
        findViewById(R.id.btnBottomMicCard).setOnClickListener(v -> resetConversation());

        // 4. 點擊畫面空白處：暫停 / 繼續對話
        findViewById(R.id.chatRootLayout).setOnClickListener(v -> {
            if (viewFlipper.getDisplayedChild() == PAGE_CHAT) toggleChatPause();
        });

        // 點擊暫停遮罩層：恢復對話
        if (pauseOverlay != null) {
            pauseOverlay.setOnClickListener(v -> {
                if (viewFlipper.getDisplayedChild() == PAGE_CHAT) toggleChatPause();
            });
        }
        // =========================================================

        findViewById(R.id.videoClickLayer).setOnClickListener(v -> {
            if (bottomButtonContainer != null) {
                logicHandler.removeCallbacks(hideControlsRunnable);
                if (bottomButtonContainer.getVisibility() == View.VISIBLE) {
                    bottomButtonContainer.animate().alpha(0f).setDuration(300)
                            .withEndAction(() -> bottomButtonContainer.setVisibility(View.GONE)).start();
                } else {
                    bottomButtonContainer.setVisibility(View.VISIBLE);
                    bottomButtonContainer.animate().alpha(1f).setDuration(300).start();
                    logicHandler.postDelayed(hideControlsRunnable, 5000);
                }
            }
        });
    }

    private void sendNewMission(String d, boolean isAutoReturn) {
        resetControlLogic();
        robotManager.sendMission(d, missionId -> {
            this.currentMissionId = missionId;
            runOnUiThread(() -> {
                if (isAutoReturn) {
                    tvRunningStatus.setText("超時未操作，自動返回中...");
                    switchPage(PAGE_RETURNING);
                } else {
                    tvRunningStatus.setText("機器人前往 " + d + " 中...");
                    switchPage(PAGE_RUNNING);
                }
                // 🌟 修正：不要立刻呼叫 startStatusPolling()！
                // 延遲 3 秒後再開始問狀態，讓伺服器有時間把狀態從上一次的 SUCCEEDED 切換成 RUNNING
                logicHandler.postDelayed(delayedPollingRunnable, 3000);
            });
        });
    }

    private void handleCancelAction() {
        robotManager.cancelMission(currentMissionId, () -> {
            runOnUiThread(() -> {
                currentMissionId = "";
                resetControlLogic();
                switchPage(PAGE_CONTROL);
                startWaitTimer();
            });
        });
    }

    private void startWaitTimer() {
        stopWaitTimer();
        if (tvCountdown != null) tvCountdown.setVisibility(View.VISIBLE);
        waitTimer = new CountDownTimer(30000, 1000) {
            public void onTick(long millisUntilFinished) {
                if (tvCountdown != null) tvCountdown.setText((millisUntilFinished / 1000) + "s 後自動返回 T2");
            }
            public void onFinish() {
                if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                sendNewMission("T2", true);
            }
        }.start();
    }

    private void startStatusPolling() {
        stopStatusPolling();
        statusPollingRunnable = new Runnable() {
            @Override
            public void run() {
                robotManager.checkRobotStatus(state -> {
                    runOnUiThread(() -> {
                        Log.d("RobotStatus", "目前底層狀態 (data.state): " + state);

                        if (state == null) {
                            logicHandler.postDelayed(statusPollingRunnable, 1000);
                            return;
                        }

                        switch (state) {
                            case "STATE_SUCCEEDED":
                            case "STATE_IDLE":
                                // 🌟 抵達目的：清空任務 ID、重置邏輯並回到首頁
                                currentMissionId = "";
                                resetControlLogic();
                                switchPage(PAGE_IDLE);
                                break;

                            case "STATE_RUNNING":
                            case "STATE_MOVING":
                            case "STATE_NAVIGATING":
                                // 🌟 執行中：隔 3 秒再問一次，讓畫面停留在 running
                                logicHandler.postDelayed(statusPollingRunnable, 3000);
                                break;

                            case "STATE_CANCELED":
                            case "STATE_CANCELLED":
                                // 🌟 任務取消：回到控制頁並啟動 30 秒倒數計時
                                resetControlLogic();
                                switchPage(PAGE_CONTROL);
                                startWaitTimer();
                                break;

                            case "STATE_FAILED":
                                // 🌟 任務失敗
                                Log.e("RobotStatus", "車子回報任務失敗！");
                                currentMissionId = "";
                                resetControlLogic();
                                switchPage(PAGE_IDLE);
                                break;

                            case "INITIALIZING":
                            case "STATE_PAUSED":
                            default:
                                logicHandler.postDelayed(statusPollingRunnable, 1000);
                                break;
                        }
                    });
                });
            }
        };
        logicHandler.post(statusPollingRunnable);
    }

    private void performCharge() {
        resetControlLogic();
        robotManager.sendChargeRequest(() -> {
            runOnUiThread(() -> {
                tvRunningStatus.setText("正在返回充電座...");
                switchPage(PAGE_RUNNING);
                // 🌟 修正：充電指令也一樣，給它 3 秒鐘更新狀態
                logicHandler.postDelayed(delayedPollingRunnable, 3000);
            });
        });
    }

    private void resetControlLogic() {
        stopWaitTimer();
        stopStatusPolling();
        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
    }

    private void stopWaitTimer() {
        if (waitTimer != null) { waitTimer.cancel(); waitTimer = null; }
    }

    private void stopStatusPolling() {
        // 🌟 修正：停止輪詢時，也要記得把「等待啟動的延遲任務」給取消掉，避免畫面切換時發生錯亂
        logicHandler.removeCallbacks(delayedPollingRunnable);
        if (statusPollingRunnable != null) {
            logicHandler.removeCallbacks(statusPollingRunnable);
        }
    }

    private void switchPage(int p) {
        if (viewFlipper != null) viewFlipper.setDisplayedChild(p);
        if (p == PAGE_IDLE) { if (videoView != null && !videoView.isPlaying()) videoView.start(); }
        else { if (videoView != null && videoView.isPlaying()) videoView.pause(); }
        if (p != PAGE_CHAT) { if (voiceManager != null) voiceManager.startListeningWakeWord(); }
        else { if (voiceManager != null) voiceManager.stopListening(); }
    }

    private void initManagers() {
        robotManager = new RobotManager(this, serverIp, serverPort, ROBOT_ID);
        audioPlayer = new LlmAudioPlayer(this, () -> {
            if (viewFlipper.getDisplayedChild() == PAGE_CHAT && !isChatPaused) startChatRecording(false);
        });
        wsManager = new ChatWebSocketManager(serverIp, serverPort, ROBOT_ID, audioPlayer);
        recorderHelper = new AudioRecorderHelper(this, new AudioRecorderHelper.AudioCallback() {
            @Override public void onVolumeChanged(double v) { updateVisualizerUI(v); }
            @Override public void onRecordingFinished(byte[] data) {
                stopVisualizerUI(false);
                if (wsManager != null && !isChatPaused) wsManager.sendAudio(data);
            }
        });
        voiceManager = new VoiceManager(this, () -> runOnUiThread(() -> {
            if (viewFlipper.getDisplayedChild() != PAGE_CHAT) openChatPage(false);
        }));
        voiceManager.startListeningWakeWord();
    }

    private void openChatPage(boolean playBeep) {
        switchPage(PAGE_CHAT);
        isChatPaused = false;
        if (pauseOverlay != null) pauseOverlay.setVisibility(View.GONE);
        if (wsManager != null) wsManager.connect();
        startChatRecording(playBeep);
    }

    private void closeChatPage() {
        switchPage(PAGE_IDLE);
        if (wsManager != null) wsManager.disconnect();
        if (audioPlayer != null) audioPlayer.stopAll();
        if (recorderHelper != null) recorderHelper.stopRecording();
        stopVisualizerUI(false);
    }

    private void startChatRecording(boolean playBeep) {
        if (isChatPaused) return;
        runOnUiThread(() -> {
            if (layoutVisualizer != null) {
                layoutVisualizer.setVisibility(View.VISIBLE);
                Animation pulse = AnimationUtils.loadAnimation(this, R.anim.tech_wave_alpha);
                layoutVisualizer.startAnimation(pulse);
            }
        });
        logicHandler.postDelayed(() -> {
            if (recorderHelper != null && !isChatPaused) recorderHelper.startRecording(5000);
        }, 100);
    }

    private void stopVisualizerUI(boolean playBeep) {
        runOnUiThread(() -> {
            if (layoutVisualizer != null) {
                layoutVisualizer.clearAnimation();
                layoutVisualizer.setScaleX(1.0f);
                layoutVisualizer.setScaleY(1.0f);
            }
        });
    }

    private void updateVisualizerUI(double volumePercent) {
        runOnUiThread(() -> {
            if (layoutVisualizer != null && layoutVisualizer.getVisibility() == View.VISIBLE) {
                float scale = 1.0f + (float) (volumePercent * 0.6f);
                layoutVisualizer.animate().scaleX(scale).scaleY(scale).setDuration(50).start();
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetControlLogic();
        closeChatPage();
        if (voiceManager != null) voiceManager.stopListening();
    }
}