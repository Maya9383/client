package com.example.server.manager;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.widget.VideoView;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 負責所有的 HTTP 請求（OkHttp）與機器人動作指令
 */
public class RobotManager {
    private static final String TAG = "RobotManager";
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String robotId;
    private final Activity activity;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public RobotManager(Activity activity, String ip, String port, String robotId) {
        this.activity = activity;
        this.httpClient = new OkHttpClient();
        this.baseUrl = "http://" + ip + ":" + port;
        this.robotId = robotId;
    }

    // --- 影片相關邏輯 ---

    /**
     * 播放下一部宣傳影片 (首頁循環用)
     */
    public void playNextPromoVideo(VideoView videoView, VideoCheckCallback check) {
        if (!check.shouldPlay()) return;

        String url = baseUrl + "/api/client/play_next?robot_id=" + robotId;
        Request request = new Request.Builder().url(url).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                activity.runOnUiThread(() -> videoView.postDelayed(() -> playNextPromoVideo(videoView, check), 5000));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject json = new JSONObject(response.body().string());
                        if ("success".equals(json.getString("status"))) {
                            String fullUrl = baseUrl + json.getJSONObject("video_info").getString("download_url");
                            activity.runOnUiThread(() -> {
                                videoView.setVideoURI(Uri.parse(fullUrl));
                                videoView.start();
                            });
                        }
                    } catch (Exception e) { Log.e(TAG, "解析宣傳影片錯誤", e); }
                }
            }
        });
    }

    /**
     * 獲取並播放衛教影片 (按鈕觸發)
     */
    public void fetchTrainingVideo(VideoView videoView) {
        String url = baseUrl + "/api/client/playlist/training";
        Request request = new Request.Builder().url(url).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "衛教影片 API 請求失敗: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject json = new JSONObject(response.body().string());
                        if ("success".equals(json.getString("status")) && json.getBoolean("has_video")) {
                            String downloadUrl = json.getJSONObject("video_info").getString("download_url");
                            String fullUrl = baseUrl + downloadUrl;

                            activity.runOnUiThread(() -> {
                                videoView.setVideoURI(Uri.parse(fullUrl));
                                videoView.start();
                            });
                        }
                    } catch (Exception e) { Log.e(TAG, "解析衛教影片錯誤", e); }
                }
            }
        });
    }

    // --- 機器人任務指令邏輯 ---

    /**
     * 發送移動任務
     */
    public void sendMission(String destId, Runnable onSuccess) {
        postRequest("/api/mission/append", "destination_id", destId, onSuccess);
    }

    /**
     * 取消任務：已修正為只接收一個 onSuccess 參數
     */
    public void cancelMission(Runnable onSuccess) {
        postRequest("/api/mission/cancel", null, null, onSuccess);
    }

    /**
     * 發送充電請求 (API 4)
     */
    public void sendChargeRequest(Runnable onSuccess) {
        postRequest("/api/robot/charge", null, null, onSuccess);
    }

    /**
     * 輪詢機器人狀態
     */
    public void checkRobotStatus(StatusCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/robot/status").post(body).build();

            httpClient.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                    activity.runOnUiThread(() -> callback.onResult("ERROR"));
                }
                @Override
                public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response) throws java.io.IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            JSONObject res = new JSONObject(response.body().string());
                            String status = res.optString("status", "MOVING");
                            activity.runOnUiThread(() -> callback.onResult(status));
                        } catch (Exception e) { activity.runOnUiThread(() -> callback.onResult("ERROR")); }
                    }
                }
            });
        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- 通用的 POST 請求工具 ---
// --- 修改 RobotManager.java 的 postRequest ---
    private void postRequest(String path, String key, String value, Runnable onSuccess) {
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            if (key != null) json.put(key, value);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + path).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "請求失敗: " + path, e);
                    // ★ 新增：網路失敗時在畫面上顯示提示
                    activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, "網路連線失敗，請檢查伺服器 IP", android.widget.Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful() && onSuccess != null) {
                        activity.runOnUiThread(onSuccess);
                    } else {
                        // ★ 新增：伺服器報錯時在畫面上顯示提示
                        String errorMsg = response.body() != null ? response.body().string() : "未知錯誤";
                        Log.e(TAG, "API 伺服器錯誤: " + response.code() + " - " + errorMsg);
                        activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, "操作失敗，伺服器回傳錯誤代碼: " + response.code(), android.widget.Toast.LENGTH_LONG).show());
                    }
                }
            });
        } catch (Exception e) { Log.e(TAG, "JSON 錯誤", e); }
    }

    // --- 介面定義 ---
    public interface VideoCheckCallback { boolean shouldPlay(); }

    public interface StatusCallback {
        void onResult(String status);
    }
}