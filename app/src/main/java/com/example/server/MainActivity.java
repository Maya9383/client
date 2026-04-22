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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private ViewFlipper viewFlipper;
    private VideoView videoView;
    private FrameLayout layoutVisualizer;
    private TextView tvRunningStatus;
    private TextView tvCountdown;
    private LinearLayout bottomButtonContainer;
    private View pauseOverlay;

    // 廣播用的 UI 變數
    private View layoutBroadcastOverlay;
    private TextView tvBroadcastContent;

    // 🌟 新增：衛教網頁用的 WebView
    private WebView educationWebView;

    // 🌟 確保這裡是新主機的 IP
    private final String serverIp = "192.168.0.25";
    private final String serverPort = "8000";
    private final String ROBOT_ID = "pennybot-a7fe96";

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

    private final Runnable delayedPollingRunnable = this::startStatusPolling;

    // 頁面索引常數
    private final int PAGE_IDLE = 0;
    private final int PAGE_CONTROL = 1;
    private final int PAGE_CHAT = 2;
    private final int PAGE_RUNNING = 3;
    private final int PAGE_RETURNING = 4;
    private final int PAGE_BROADCAST = 5; // 假設你在 XML 中廣播頁面是第 6 個
    private final int PAGE_EDUCATION = 6; // 🌟 衛教網頁頁面 (第 7 個)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("MainActivity", "🚀 系統啟動，準備初始化元件...");
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

                if (currentPage == PAGE_CHAT) {
                    closeChatPage();
                } else if (currentPage == PAGE_CONTROL) {
                    resetControlLogic();
                    switchPage(PAGE_IDLE);
                } else if (currentPage == PAGE_EDUCATION) {
                    // 🌟 當使用者在看衛教影片時按下平板實體返回鍵
                    if (educationWebView != null) {
                        educationWebView.loadUrl("about:blank"); // 清空網頁，強制停止 YouTube 背景播放
                    }
                    switchPage(PAGE_IDLE); // 切換回首頁 (待機影片頁)
                } else if (currentPage != PAGE_RUNNING && currentPage != PAGE_RETURNING) {
                    finish();
                }
            }
        });
    }

    // 🌟 動態生成目的地按鈕
    private void loadDynamicDestinations() {
        robotManager.fetchDestinations(destinations -> {
            if (destinations == null || destinations.length() == 0) {
                Log.w("MainActivity", "⚠️ 抓取失敗，3秒後自動重試...");
                logicHandler.postDelayed(this::loadDynamicDestinations, 3000);
                return;
            }
            LinearLayout container = findViewById(R.id.llDestinationsContainer);
            if (container == null) return;

            // 清除除了標題 (TextView) 以外的舊按鈕
            int childCount = container.getChildCount();
            for (int i = childCount - 1; i >= 1; i--) {
                container.removeViewAt(i);
            }

            if (destinations == null || destinations.length() == 0) {
                Log.w("MainActivity", "⚠️ 無法取得動態點位或點位為空");
                return;
            }

            // 準備轉換 dp 到 pixel 以設定按鈕尺寸
            int heightPx = (int) android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 90, getResources().getDisplayMetrics());
            int marginPx = (int) android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());

            for (int i = 0; i < destinations.length(); i++) {
                try {
                    JSONObject dest = destinations.getJSONObject(i);
                    String name = dest.optString("name", "未知點位");
                    String id = dest.optString("id", "");

                    com.google.android.material.button.MaterialButton btn = new com.google.android.material.button.MaterialButton(
                            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);

                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, heightPx);
                    params.bottomMargin = marginPx;
                    btn.setLayoutParams(params);

                    btn.setText(name);
                    btn.setTextSize(26f);
                    btn.setCornerRadius(45);

                    btn.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
                    btn.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.md_theme_surfaceVariant));

                    btn.setOnClickListener(v -> sendNewMission(id, name, false));

                    container.addView(btn);

                } catch (Exception e) {
                    Log.e("MainActivity", "❌ 動態生成按鈕失敗", e);
                }
            }
        });
    }

    private void resetConversation() {
        Log.i("MainActivity", "🔄 使用者手動重置對話");
        if (audioPlayer != null) audioPlayer.stopAll();
        if (recorderHelper != null) recorderHelper.stopRecording();
        stopVisualizerUI(false);

        if (wsManager != null) {
            wsManager.disconnect();
            wsManager.connect();
        }
        startChatRecording(true);
    }

    private void toggleChatPause() {
        isChatPaused = !isChatPaused;
        Log.i("MainActivity", "⏸️ 聊天暫停狀態切換: " + isChatPaused);

        if (isChatPaused) {
            if (pauseOverlay != null) pauseOverlay.setVisibility(View.VISIBLE);
            if (audioPlayer != null) audioPlayer.stopAll();
            if (recorderHelper != null) recorderHelper.stopRecording();
            stopVisualizerUI(false);
        } else {
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

        // 🌟 廣播用的文字變數 (layoutBroadcastOverlay 已經不需要在這裡綁定了，因為改用 ViewFlipper 切換)
        tvBroadcastContent = findViewById(R.id.tvBroadcastContent);

        videoView.setOnCompletionListener(mp -> {
            if (viewFlipper.getDisplayedChild() == PAGE_IDLE) robotManager.playNextPromoVideo(videoView, () -> true);
        });

        findViewById(R.id.btnControl).setOnClickListener(v -> switchPage(PAGE_CONTROL));
        findViewById(R.id.btnStartChat).setOnClickListener(v -> openChatPage(false));
        findViewById(R.id.btnCharge).setOnClickListener(v -> performCharge());
        findViewById(R.id.btnBackFromControl).setOnClickListener(v -> { resetControlLogic(); switchPage(PAGE_IDLE); });
        findViewById(R.id.btnCancelMission).setOnClickListener(v -> handleCancelAction());

        findViewById(R.id.btnBackFromChat).setOnClickListener(v -> closeChatPage());
        findViewById(R.id.btnBottomStopCard).setOnClickListener(v -> closeChatPage());
        findViewById(R.id.btnBottomMicCard).setOnClickListener(v -> resetConversation());

        // ✅ 修正：改用 page_chat，並加上防呆檢查
        View chatPage = findViewById(R.id.page_chat);
        if (chatPage != null) {
            chatPage.setOnClickListener(v -> {
                if (viewFlipper.getDisplayedChild() == PAGE_CHAT) toggleChatPause();
            });
        } else {
            Log.e("MainActivity", "⚠️ 找不到 page_chat，請確認 activity_main.xml 中的 include 設定！");
        }

        if (pauseOverlay != null) {
            pauseOverlay.setOnClickListener(v -> {
                if (viewFlipper.getDisplayedChild() == PAGE_CHAT) toggleChatPause();
            });
        }

        findViewById(R.id.videoClickLayer).setOnClickListener(v -> {
            if (bottomButtonContainer != null) {
                logicHandler.removeCallbacks(hideControlsRunnable);
                if (bottomButtonContainer.getVisibility() == View.VISIBLE) {
                    bottomButtonContainer.animate().alpha(0f).setDuration(300)
                            .withEndAction(() -> bottomButtonContainer.setVisibility(View.GONE)).start();
                } else {
                    bottomButtonContainer.setVisibility(View.VISIBLE);
                    bottomButtonContainer.animate().alpha(1f).setDuration(300).start();
                    hideControlsRunnable = () -> bottomButtonContainer.setVisibility(View.GONE);
                    logicHandler.postDelayed(hideControlsRunnable, 5000);
                }
            }
        });

        // ==========================================
        // 🌟 衛教網頁 WebView 綁定與「按鈕劫持」設定
        // ==========================================
        educationWebView = findViewById(R.id.educationWebView);
        if (educationWebView != null) {
            WebSettings webSettings = educationWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);

            // 1. 掛上溝通橋樑，暗號叫做 "RobotApp"
            educationWebView.addJavascriptInterface(new WebAppInterface(), "RobotApp");

            // 2. 設定 WebViewClient 來注入攔截程式
            educationWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    // 偷偷塞入 JavaScript，劫持「回上一頁」按鈕
                    String injectJs = "javascript:(function() {" +
                            "  var elements = document.querySelectorAll('a, button, div, span');" +
                            "  for(var i=0; i<elements.length; i++) {" +
                            "    if(elements[i].innerText && elements[i].innerText.includes('回上一頁')) {" +
                            "      elements[i].onclick = function(event) {" +
                            "        event.preventDefault();" +
                            "        event.stopPropagation();" +
                            "        RobotApp.closeWebPage();" +
                            "      };" +
                            "    }" +
                            "  }" +
                            "})()";
                    view.loadUrl(injectJs);
                }
            });
        }

        // 綁定首頁的「衛教影片」按鈕
        View btnTrainingVideo = findViewById(R.id.btnTrainingVideo);
        if (btnTrainingVideo != null) {
            btnTrainingVideo.setOnClickListener(v -> {
                if (educationWebView != null) {
                    // 載入慈濟多國語言衛教網址
                    educationWebView.loadUrl("https://taipei.tzuchi.com.tw/ai智能小幫手-多國語言衛教影音測試/?openExternalBrowser=1");
                }
                switchPage(PAGE_EDUCATION); // 切換到衛教網頁畫面
            });
        }
    }

    public void showBroadcastUI(String text) {
        Log.i("MainActivity", "📢 收到指令，強制顯示廣播畫面，文字內容: " + text);
        runOnUiThread(() -> {
            // 1. 設定廣播文字 (這個 ID 是子元件，沒有被 include 蓋掉，所以抓得到)
            if (tvBroadcastContent != null) {
                tvBroadcastContent.setText(text);
            } else {
                Log.e("MainActivity", "⚠️ 找不到 tvBroadcastContent，請確認 XML 內的 ID");
            }

            // 2. 🌟 關鍵修改：直接叫 ViewFlipper 切換到廣播分頁 (PAGE_BROADCAST = 5)
            switchPage(PAGE_BROADCAST);

            // 3. 暫停待機影片，避免聲音跟廣播打架
            if (videoView != null && videoView.isPlaying()) {
                Log.i("MainActivity", "⏸️ 已暫停背景影片播放，避免聲音干擾");
                videoView.pause();
            }
        });
    }

    public void hideBroadcastUI() {
        Log.i("MainActivity", "✅ 廣播結束，準備隱藏廣播畫面");
        runOnUiThread(() -> {
            // 🌟 關鍵修改：直接叫 ViewFlipper 切回首頁待機畫面
            switchPage(PAGE_IDLE);

            // switchPage(PAGE_IDLE) 裡面已經有自動恢復播放影片的邏輯了，所以這裡不用多寫！
        });
    }

    private void sendNewMission(String destId, String destName, boolean isAutoReturn) {
        resetControlLogic();
        robotManager.sendMission(destId, missionId -> {
            this.currentMissionId = missionId;
            runOnUiThread(() -> {
                if (isAutoReturn) {
                    tvRunningStatus.setText("超時未操作，自動返回中...");
                    switchPage(PAGE_RETURNING);
                } else {
                    tvRunningStatus.setText("機器人前往 " + destName + " 中...");
                    switchPage(PAGE_RUNNING);
                }
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
                sendNewMission("DEST_ID_STANDBY", "待命點", true);
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
                        if (state == null) {
                            logicHandler.postDelayed(statusPollingRunnable, 1000);
                            return;
                        }

                        switch (state) {
                            case "STATE_SUCCEEDED":
                            case "STATE_IDLE":
                                currentMissionId = "";
                                resetControlLogic();
                                switchPage(PAGE_IDLE);
                                break;
                            case "STATE_RUNNING":
                            case "STATE_MOVING":
                            case "STATE_NAVIGATING":
                                logicHandler.postDelayed(statusPollingRunnable, 3000);
                                break;
                            case "STATE_CANCELED":
                            case "STATE_CANCELLED":
                                resetControlLogic();
                                switchPage(PAGE_CONTROL);
                                startWaitTimer();
                                break;
                            case "STATE_FAILED":
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
        logicHandler.removeCallbacks(delayedPollingRunnable);
        if (statusPollingRunnable != null) {
            logicHandler.removeCallbacks(statusPollingRunnable);
        }
    }

    private void switchPage(int p) {
        Log.i("MainActivity", "📺 切換畫面，目標分頁代號: " + p);
        if (viewFlipper != null) viewFlipper.setDisplayedChild(p);

        if (p == PAGE_IDLE) {
            if (videoView != null && !videoView.isPlaying()) videoView.start();
        } else {
            if (videoView != null && videoView.isPlaying()) videoView.pause();
        }

        if (p == PAGE_CONTROL) {
            loadDynamicDestinations();
        }
    }

    private void initManagers() {
        Log.i("MainActivity", "⚙️ 開始初始化各項背景服務 Manager...");
        robotManager = new RobotManager(this, serverIp, serverPort, ROBOT_ID);

        audioPlayer = new LlmAudioPlayer(this, () -> {
            if (viewFlipper.getDisplayedChild() == PAGE_CHAT && !isChatPaused) startChatRecording(false);

            // 🌟 修正：改用 ViewFlipper 的當前頁面來判斷廣播是不是講完了
            if (viewFlipper.getDisplayedChild() == PAGE_BROADCAST) {
                hideBroadcastUI();
            }
        });

        wsManager = new ChatWebSocketManager(serverIp, serverPort, ROBOT_ID, audioPlayer, this);
        Log.i("MainActivity", "🔌 初始化 wsManager，並立刻發起 WebSocket 背景連線！");
        wsManager.connect();

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
    }

    private void openChatPage(boolean playBeep) {
        Log.i("MainActivity", "💬 進入對話模式");
        switchPage(PAGE_CHAT);
        isChatPaused = false;
        if (pauseOverlay != null) pauseOverlay.setVisibility(View.GONE);

        if (wsManager != null) wsManager.connect();
        startChatRecording(playBeep);
    }

    private void closeChatPage() {
        Log.i("MainActivity", "🚪 離開對話模式，回到待機首頁");
        switchPage(PAGE_IDLE);

        Log.i("MainActivity", "🔌 保持 WebSocket 暢通，持續在背景監聽指令...");

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
        Log.i("MainActivity", "🛑 App 完全關閉，釋放所有硬體與連線資源");
        resetControlLogic();
        closeChatPage();

        if (wsManager != null) wsManager.disconnect();
        if (voiceManager != null) voiceManager.stopListening();
    }
    // ==========================================
    // 🌟 網頁與 Android 的溝通橋樑
    // ==========================================
    public class WebAppInterface {
        @android.webkit.JavascriptInterface
        public void closeWebPage() {
            runOnUiThread(() -> {
                Log.i("MainActivity", "🌐 攔截到網頁『回上一頁』按鈕，關閉網頁並回到首頁");
                if (educationWebView != null) {
                    educationWebView.loadUrl("about:blank"); // 清空網頁，避免背景繼續播影片
                }
                switchPage(PAGE_IDLE); // 切換回首頁
            });
        }
    }
}