package com.example.qgas;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HomeFragment extends Fragment {

    private RecyclerView recyclerView;
    private QueueAdapter adapter;
    private List<JSONObject> queueList = new ArrayList<>();
    private final OkHttpClient client = getUnsafeOkHttpClient();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        recyclerView = view.findViewById(R.id.rv_home_queue);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new QueueAdapter(queueList);
        recyclerView.setAdapter(adapter);

        loadQueue();
        return view;
    }

    private void loadQueue() {
        SharedPreferences prefs = requireContext().getSharedPreferences("GasQueue", Context.MODE_PRIVATE);
        String data = prefs.getString("queue_data", "[]");
        try {
            JSONArray jsonArray = new JSONArray(data);
            queueList.clear();
            for (int i = 0; i < jsonArray.length(); i++) {
                queueList.add(jsonArray.getJSONObject(i));
            }
            adapter.notifyDataSetChanged();

            // Start the sequential sync if the list is not empty
            if (!queueList.isEmpty()) {
                syncNextItem(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Uploads items one by one recursively.
     * @param index The current index in the queueList to upload.
     */
    private void syncNextItem(int index) {
        // Base case: if we have reached the end of the list, stop.
        if (index >= queueList.size()) {
            return;
        }
        Log.e("TEST", ": " + queueList.get(index).toString());
        SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
        String apiUrl = settings.getString("api_base_url", "https://services.leyteprovince.gov.ph:8282");
        String device_id = settings.getString("device_id", "");

        JSONObject scan = queueList.get(index);

        try {
            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("station_name", scan.getString("station"))
                    .addFormDataPart("latitude", String.valueOf(scan.getDouble("latitude")))
                    .addFormDataPart("longitude", String.valueOf(scan.getDouble("longitude")))
                    .addFormDataPart("date_captured", scan.getString("timestamp"))
                    .addFormDataPart("fuels", scan.getJSONArray("prices").toString())
                    .addFormDataPart("device_id", device_id);

            if (scan.has("gasImagePath")) {
                String path = scan.getString("gasImagePath");
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        builder.addFormDataPart("photo", file.getName(),
                                RequestBody.create(file, MediaType.parse("image/jpeg")));
                    }
                }
            }

            if (scan.has("stationImagePath")) {
                String path = scan.getString("stationImagePath");
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        builder.addFormDataPart("station_photo", file.getName(),
                                RequestBody.create(file, MediaType.parse("image/jpeg")));
                    }
                }
            }

            Request request = new Request.Builder()
                    .url(apiUrl + "/qgas/public/api/station-fuel")
                    .addHeader("Accept", "application/json")
                    .post(builder.build())
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    saveErrorToFile("Network failure: " + e.getMessage());
                    showToast("Upload failed: Network Error");

                    // Proceed to next item even if current fails (optional logic)
                    syncNextItem(index + 1);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";

                        if (response.isSuccessful()) {
                            showToast("Uploaded successfully!");

                            // Remove from queue and update UI on Main Thread
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    removeItemFromQueue(scan);
                                    // Since item is removed, the "next" item is now at the same 'index'
                                    syncNextItem(index);
                                });
                            }
                        } else {
                            String logEntry = "URL: " + request.url() +
                                    "\nStatus Code: " + response.code() +
                                    "\nResponse: " + responseBody;
                            saveErrorToFile(logEntry);
                            showToast("Server Error " + response.code());

                            // Move to next item on server-side error
                            syncNextItem(index + 1);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        syncNextItem(index + 1);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            saveErrorToFile("JSON/Request Exception: " + e.getMessage());
            syncNextItem(index + 1);
        }
    }

    private void saveErrorToFile(String content) {
        new Thread(() -> {
            try {
                // Access the app's internal "Documents" directory
                // This does not require READ/WRITE_EXTERNAL_STORAGE permissions
                File docsDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);

                if (docsDir != null && !docsDir.exists()) {
                    docsDir.mkdirs();
                }

                File logFile = new File(docsDir, "error.txt");
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                String logMessage = "\n--- ERROR LOG [" + timestamp + "] ---\n" + content + "\n----------------------------\n";

                // Open in append mode (true)
                FileOutputStream fos = new FileOutputStream(logFile, true);
                fos.write(logMessage.getBytes(StandardCharsets.UTF_8));
                fos.close();

                Log.d("FileLogger", "Error saved to: " + logFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e("FileLogger", "Could not write to file", e);
            }
        }).start();
    }

    private void showToast(String message) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void removeItemFromQueue(JSONObject item) {
        queueList.remove(item);
        adapter.notifyDataSetChanged();
        SharedPreferences prefs = requireContext().getSharedPreferences("GasQueue", Context.MODE_PRIVATE);
        prefs.edit().putString("queue_data", new JSONArray(queueList).toString()).apply();
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.ViewHolder> {
        private List<JSONObject> items;
        QueueAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                JSONObject obj = items.get(position);
                holder.tvStation.setText(obj.getString("station"));
                holder.tvTime.setText(obj.getString("timestamp"));
                holder.tvLat.setText("Lat: " + obj.getDouble("latitude"));
                holder.tvLong.setText("Long: " + obj.getDouble("longitude"));
                holder.tvDetails.setText("Fuels: " + obj.getJSONArray("prices").length());
            } catch (Exception e) { e.printStackTrace(); }
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvStation, tvTime, tvDetails, tvLat, tvLong;
            ViewHolder(View itemView) {
                super(itemView);
                tvStation = itemView.findViewById(R.id.tv_q_station);
                tvTime = itemView.findViewById(R.id.tv_q_datetime);
                tvDetails = itemView.findViewById(R.id.tv_q_details);
                tvLat = itemView.findViewById(R.id.tv_q_lat);
                tvLong = itemView.findViewById(R.id.tv_q_long);
            }
        }
    }

    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}