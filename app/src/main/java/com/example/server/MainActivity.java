package com.example.server;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.ViewFlipper;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.server.manager.RobotManager;
import com.example.server.manager.VoiceManager;
import com.example.server.manager.ChatWebSocketManager;
import com.example.server.manager.LlmAudioPlayer;
import com.example.server.utils.AudioRecorderHelper;

public class MainActivity extends AppCompatActivity {
    // --- UI 組件 ---
    private ViewFlipper viewFlipper;
    private VideoView videoView;
    private CardView cardRecord;
    private LinearLayout layoutVisualizer;
    private Animation pulse;
    private View[] vBars;
    private TextView tvRunningStatus;
    private TextView tvCountdown;

    // ★ UI 特效與控制組件 ★
    private LinearLayout bottomButtonContainer; // 影片頁按鈕容器
    private View pauseOverlay; // 對話暫停遮罩層

    // --- 設定值 ---
    private final String serverIp = "192.168.0.157";
    private final String serverPort = "8000";
    private final String ROBOT_ID = "robot_b";

    // --- 邏輯模組 ---
    private RobotManager robotManager;
    private VoiceManager voiceManager;
    private ChatWebSocketManager wsManager;
    private LlmAudioPlayer audioPlayer;
    private AudioRecorderHelper recorderHelper;

    // ★ 暫停狀態控制 ★
    private boolean isChatPaused = false;

    // --- 控車邏輯專用 Handler 與計時器 ---
    private final Handler logicHandler = new Handler(Looper.getMainLooper());
    private Runnable waitingTimerRunnable;
    private Runnable statusPollingRunnable;
    private int countdownSeconds = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initManagers();
        initUI();
        checkPermissions();

        // 啟動首頁宣傳影片
        robotManager.playNextPromoVideo(videoView, () -> viewFlipper.getDisplayedChild() == 0);

        // ★ 現代化的 Android 返回鍵與手勢處理邏輯 (取代舊的 onBackPressed) ★
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int currentPage = viewFlipper.getDisplayedChild();

                if (currentPage == 2) {
                    // 正在「語音對話頁」，呼叫離開對話的方法 (會切回影片頁)
                    closeChatPage();
                } else if (currentPage == 1) {
                    // 正在「控車頁」，重置邏輯並切回影片頁
                    resetControlLogic();
                    switchPage(0);
                } else if (currentPage == 3 || currentPage == 4) {
                    // 正在「執行任務中」，鎖定返回鍵不給按，避免中斷任務
                    // 故意留空，什麼都不做
                } else {
                    // 已經在首頁 (影片頁) 了，退出 App
                    finish();
                }
            }
        });
    }

    private void initManagers() {
        robotManager = new RobotManager(this, serverIp, serverPort, ROBOT_ID);

        // 初始化 AI 播放器
        audioPlayer = new LlmAudioPlayer(this, () -> {
            // ★ 伺服器回覆播完，且仍在對話頁且無暫停時，重啟監聽等待需求 ★
            if (viewFlipper.getDisplayedChild() == 2 && voiceManager != null && !isChatPaused) {
                runOnUiThread(() -> voiceManager.startListeningWakeWord());
            }
        });

        wsManager = new ChatWebSocketManager(serverIp, serverPort, ROBOT_ID, audioPlayer);

        recorderHelper = new AudioRecorderHelper(this, new AudioRecorderHelper.AudioCallback() {
            @Override
            public void onVolumeChanged(double volumePercent) {
                updateVisualizerUI(volumePercent);
            }

            @Override
            public void onRecordingFinished(byte[] wavData) {
                // 錄音結束，停止特效並送出資料
                stopVisualizerUI();
                if (wsManager != null && !isChatPaused) wsManager.sendAudio(wavData);
            }
        });

        // ★ 「全局喚醒小美」回呼邏輯 ★
        voiceManager = new VoiceManager(this, () -> {
            runOnUiThread(() -> {
                // 位於任何頁面聽到小美時，自動跳轉至對話頁
                if (viewFlipper.getDisplayedChild() != 2) {
                    openChatPage(); // 自動跳轉並連線
                } else {
                    // 如果已在對話頁，且沒暫停，就直接開啟錄音
                    if (!isChatPaused) {
                        startChatRecording();
                    }
                }
            });
        });

        // ★ App 啟動時就開始監聽喚醒詞 ★
        voiceManager.startListeningWakeWord();
    }

    private void initUI() {
        viewFlipper = findViewById(R.id.viewFlipper);
        videoView = findViewById(R.id.videoView);
        cardRecord = findViewById(R.id.cardRecord);
        layoutVisualizer = findViewById(R.id.layoutVisualizer);
        pulse = AnimationUtils.loadAnimation(this, R.anim.pulse_animation);
        tvRunningStatus = findViewById(R.id.tvRunningStatus);
        tvCountdown = findViewById(R.id.tvCountdown);

        bottomButtonContainer = findViewById(R.id.bottomButtonContainer);
        pauseOverlay = findViewById(R.id.pauseOverlay);

        vBars = new View[]{
                findViewById(R.id.vBar1), findViewById(R.id.vBar2), findViewById(R.id.vBar3),
                findViewById(R.id.vBar4), findViewById(R.id.vBar5), findViewById(R.id.vBar6),
                findViewById(R.id.vBar7), findViewById(R.id.vBar8), findViewById(R.id.vBar9)
        };

        // 影片循環播放邏輯
        videoView.setOnCompletionListener(mp -> {
            if (viewFlipper.getDisplayedChild() == 0) robotManager.playNextPromoVideo(videoView, () -> true);
        });

        // --- 頁面 0: 首頁 ---
        findViewById(R.id.btnTrainingVideo).setOnClickListener(v -> robotManager.fetchTrainingVideo(videoView));
        findViewById(R.id.btnControl).setOnClickListener(v -> switchPage(1));
        findViewById(R.id.btnStartChat).setOnClickListener(v -> openChatPage());

        // 點擊影片區域切換按鈕顯示/隱藏
        findViewById(R.id.videoClickLayer).setOnClickListener(v -> {
            if (bottomButtonContainer != null) {
                if (bottomButtonContainer.getVisibility() == View.VISIBLE) {
                    bottomButtonContainer.setVisibility(View.GONE);
                } else {
                    bottomButtonContainer.setVisibility(View.VISIBLE);
                }
            }
        });

        // --- 頁面 1: 控車頁 ---
        findViewById(R.id.btnBackFromControl).setOnClickListener(v -> {
            resetControlLogic();
            switchPage(0);
        });

        findViewById(R.id.btnDestLobby).setOnClickListener(v -> sendNewMission("Lobby"));
        findViewById(R.id.btnDestWashroom).setOnClickListener(v -> sendNewMission("Washroom"));
        findViewById(R.id.btnDestStandby).setOnClickListener(v -> sendNewMission("待機點"));
        findViewById(R.id.btnCharge).setOnClickListener(v -> performCharge());

        // --- 頁面 2: 對話頁 ---
        findViewById(R.id.btnBackFromChat).setOnClickListener(v -> closeChatPage());
        findViewById(R.id.btnSendChat).setOnClickListener(v -> {
            if (!isChatPaused) startChatRecording();
        });

        // 點擊螢幕暫停/恢復對話
        View.OnClickListener togglePauseListener = v -> {
            if (viewFlipper.getDisplayedChild() == 2) {
                isChatPaused = !isChatPaused;
                if (isChatPaused) {
                    pauseOverlay.setVisibility(View.VISIBLE);
                    if (audioPlayer != null) audioPlayer.pauseAudio();
                    recorderHelper.stopRecording();
                    stopVisualizerUI();
                } else {
                    pauseOverlay.setVisibility(View.GONE);
                    if (audioPlayer != null) audioPlayer.resumeAudio();
                }
            }
        };
        findViewById(R.id.chatRootLayout).setOnClickListener(togglePauseListener);
        pauseOverlay.setOnClickListener(togglePauseListener);

        // --- 頁面 3: 運行頁 ---
        findViewById(R.id.btnCancelMission).setOnClickListener(v -> handleCancelAction());
    }

    // --- 對話功能控制 ---

    private void openChatPage() {
        switchPage(2);
        isChatPaused = false;
        pauseOverlay.setVisibility(View.GONE);
        if (wsManager != null) wsManager.connect();
        startChatRecording(); // 進入對話頁立刻啟動錄音
    }

    private void closeChatPage() {
        switchPage(0); // 切換回影片首頁

        if (wsManager != null) wsManager.disconnect();
        if (audioPlayer != null) audioPlayer.stopAll();
        stopVisualizerUI();

        // 重新啟動「全局關鍵字監聽」
        if (voiceManager != null) voiceManager.startListeningWakeWord();
    }

    private void startChatRecording() {
        if (isChatPaused) return;

        // 播放開始提示音
        voiceManager.playCustomBeep(true);

        runOnUiThread(() -> {
            if (cardRecord != null) cardRecord.startAnimation(pulse);
            if (layoutVisualizer != null) layoutVisualizer.setVisibility(View.VISIBLE);
        });

        // 延遲開始錄音，避開提示音
        logicHandler.postDelayed(() -> {
            if (recorderHelper != null && !isChatPaused) recorderHelper.startRecording(5000);
        }, 500);
    }

    private void stopVisualizerUI() {
        runOnUiThread(() -> {
            if (cardRecord != null) cardRecord.clearAnimation();
            if (layoutVisualizer != null) layoutVisualizer.setVisibility(View.INVISIBLE);
            // 播放結束提示音
            voiceManager.playCustomBeep(false);
        });
    }

    // --- 控車核心邏輯 ---

    private void sendNewMission(String destId) {
        resetControlLogic();
        robotManager.sendMission(destId, () -> {
            tvRunningStatus.setText("機器人前往 " + destId + " 中...");
            switchPage(3);
            startStatusPolling();
        });
    }

    private void performCharge() {
        resetControlLogic();
        robotManager.sendChargeRequest(() -> {
            tvRunningStatus.setText("正在返回充電座...");
            switchPage(3);
            startStatusPolling();
        });
    }

    private void handleCancelAction() {
        robotManager.cancelMission(() -> {
            resetControlLogic();
            switchPage(1); // 返回控制頁
            startWaitTimer();
        });
    }

    private void startWaitTimer() {
        stopWaitTimer();
        countdownSeconds = 30;

        if (tvCountdown != null) {
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText(countdownSeconds + "s 後自動返回待機點"); // 更改提示文字
        }

        waitingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (countdownSeconds > 0) {
                    countdownSeconds--;
                    if (tvCountdown != null) {
                        tvCountdown.setText(countdownSeconds + "s 後自動返回待機點");
                    }
                    logicHandler.postDelayed(this, 1000);
                } else {
                    // ★ 倒數 30 秒結束：隱藏倒數文字，並自動呼叫前往待機點的 API ★
                    if (tvCountdown != null) {
                        tvCountdown.setVisibility(View.GONE);
                    }
                    sendNewMission("待機點");
                }
            }
        };
        logicHandler.postDelayed(waitingTimerRunnable, 1000);
    }
    private void startStatusPolling() {
        stopStatusPolling();
        statusPollingRunnable = new Runnable() {
            @Override
            public void run() {
                robotManager.checkRobotStatus(status -> {
                    if ("ARRIVED".equals(status)) {
                        resetControlLogic();
                        switchPage(1);
                    } else {
                        logicHandler.postDelayed(this, 2000);
                    }
                });
            }
        };
        logicHandler.post(statusPollingRunnable);
    }

    // 找到你的 MainActivity.java
    private void resetControlLogic() {
        stopWaitTimer();
        stopStatusPolling();
        // ★ 這裡在初始化時會先隱藏倒數文字，這沒問題，Java 會幫你控制
        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
    }
    private void stopWaitTimer() { if (waitingTimerRunnable != null) logicHandler.removeCallbacks(waitingTimerRunnable); }
    private void stopStatusPolling() { if (statusPollingRunnable != null) logicHandler.removeCallbacks(statusPollingRunnable); }

    private void switchPage(int pageIndex) {
        if (viewFlipper != null) viewFlipper.setDisplayedChild(pageIndex);

        // 首頁才撥放影片，其他頁面暫停
        if (pageIndex == 0) {
            if (videoView != null && !videoView.isPlaying()) videoView.start();
        } else {
            if (videoView != null && videoView.isPlaying()) videoView.pause();
        }
    }

    private void updateVisualizerUI(double volumePercent) {
        runOnUiThread(() -> {
            float scale = getResources().getDisplayMetrics().density;
            float[] weights = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 0.8f, 0.6f, 0.4f, 0.2f};
            for (int i = 0; i < vBars.length; i++) {
                int h = (int) ((10f + (60f * volumePercent * weights[i])) * scale);
                if (vBars[i].getLayoutParams() != null) {
                    vBars[i].getLayoutParams().height = h;
                    vBars[i].requestLayout();
                }
            }
        });
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
    }
}