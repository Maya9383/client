package com.example.server.manager;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.widget.VideoView;
import android.widget.Toast;
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

public class RobotManager {
    private static final String TAG = "RobotManager";
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String robotId;
    private final Activity activity;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private long currentVideoRequestId = 0;

    public RobotManager(Activity activity, String ip, String port, String robotId) {
        this.activity = activity;
        this.httpClient = new OkHttpClient();
        this.baseUrl = "http://" + ip + ":" + port;
        this.robotId = robotId;
    }

    // --- 影音播放 API ---
    public void playNextPromoVideo(VideoView videoView, VideoCheckCallback check) {
        if (!check.shouldPlay()) return;
        final long requestId = ++currentVideoRequestId;
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
                            String downloadUrl = json.getJSONObject("video_info").getString("download_url");
                            String fullUrl = baseUrl + downloadUrl;
                            activity.runOnUiThread(() -> {
                                if (requestId != currentVideoRequestId) return;
                                videoView.stopPlayback();
                                videoView.setVideoURI(Uri.parse(fullUrl));
                                videoView.start();
                            });
                        }
                    } catch (Exception e) { Log.e(TAG, "解析影片錯誤", e); }
                }
            }
        });
    }

    // --- 機器人控制 API ---

    // 🌟 修改：成功後回傳 mission_id 給 MainActivity
    public void sendMission(String destId, MissionCallback callback) {
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            json.put("destination_id", destId);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/mission/append").post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "派車失敗", Toast.LENGTH_SHORT).show());
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            JSONObject res = new JSONObject(response.body().string());
                            String missionId = res.optString("mission_id", "");
                            activity.runOnUiThread(() -> callback.onSuccess(missionId));
                        } catch (Exception e) { Log.e(TAG, "解析派車回應錯誤", e); }
                    }
                }
            });
        } catch (Exception e) { Log.e(TAG, "JSON錯誤", e); }
    }

    // 🌟 修改：取消任務時必須帶入 mission_id
    public void cancelMission(String missionId, Runnable onSuccess) {
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            json.put("mission_id", missionId);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/mission/cancel").post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) { Log.e(TAG, "網路失敗", e); }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    if (response.isSuccessful() && onSuccess != null) activity.runOnUiThread(onSuccess);
                }
            });
        } catch (Exception e) { Log.e(TAG, "JSON錯誤", e); }
    }

    public void sendChargeRequest(Runnable onSuccess) {
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/robot/charge").post(body).build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {}
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    if (response.isSuccessful() && onSuccess != null) activity.runOnUiThread(onSuccess);
                }
            });
        } catch (Exception e) { Log.e(TAG, "JSON錯誤", e); }
    }

    // 🌟 精準抓取 data.state 的邏輯更新
    public void checkRobotStatus(StatusCallback callback) {
        String url = baseUrl + "/api/robot/status";
        JSONObject json = new JSONObject();
        try { json.put("robot_id", robotId); } catch (Exception ignored) {}
        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder().url(url).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // 網路錯誤時回傳 null，讓 MainActivity 繼續重試輪詢
                activity.runOnUiThread(() -> callback.onResult(null));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject res = new JSONObject(response.body().string());

                        // 1. 確認 API 執行成功
                        if (res.optBoolean("success", false)) {
                            // 2. 取得內層的 data 物件
                            JSONObject dataObj = res.optJSONObject("data");

                            if (dataObj != null && dataObj.has("state") && !dataObj.isNull("state")) {
                                // 3. 精準拿出裡面的 state
                                String state = dataObj.getString("state");
                                activity.runOnUiThread(() -> callback.onResult(state));
                                return; // 成功解析並回傳後結束
                            }
                        }

                        // 若無 data 或 state 欄位，回傳 UNKNOWN 讓 MainActivity 繼續輪詢等候
                        activity.runOnUiThread(() -> callback.onResult("UNKNOWN"));

                    } catch (Exception e) {
                        Log.e(TAG, "解析狀態回應錯誤", e);
                        // 解析失敗時回傳 null，MainActivity 會視為容錯問題持續輪詢
                        activity.runOnUiThread(() -> callback.onResult(null));
                    }
                } else {
                    activity.runOnUiThread(() -> callback.onResult(null));
                }
            }
        });
    }

    public interface VideoCheckCallback { boolean shouldPlay(); }
    public interface StatusCallback { void onResult(String status); }
    public interface MissionCallback { void onSuccess(String missionId); }
}