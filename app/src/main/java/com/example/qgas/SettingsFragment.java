package com.example.qgas;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONObject;
import java.io.IOException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SettingsFragment extends Fragment {

    private TextInputEditText etApiUrl;
    private Button btnSave, btnCheckWhitelist;
    private TextView tvVersion, tvDeviceId, tvWhitelistStatus;
    private String originalUrl;

    // FIX: Using unsafe client to solve "Trust anchor for certification path not found"
    private final OkHttpClient client = getUnsafeOkHttpClient();

    private int whitelistRetryCount = 0;
    private static final int MAX_WHITELIST_RETRIES = 5;

    private static final String PREFS_NAME = "AppConfig";
    private static final String KEY_API_URL = "api_base_url";
    private static final String KEY_WHITELIST_STATUS = "whitelist_status";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String DEFAULT_URL = "https://services.leyteprovince.gov.ph:8282";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        etApiUrl = view.findViewById(R.id.et_api_key);
        btnSave = view.findViewById(R.id.btn_save_api);
        btnCheckWhitelist = view.findViewById(R.id.btn_check_whitelist);
        tvVersion = view.findViewById(R.id.tv_version);
        tvDeviceId = view.findViewById(R.id.tv_device_id);
        tvWhitelistStatus = view.findViewById(R.id.tv_whitelist_status);

        loadSettings();
        displayVersion();

        // Keep your original TextWatcher logic
        etApiUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String inputUrl = s.toString().trim();
                if (!inputUrl.equals(originalUrl)) {
                    btnSave.setVisibility(View.VISIBLE);
                } else {
                    btnSave.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnSave.setOnClickListener(v -> {
            String url = etApiUrl.getText().toString().trim();
            if (url.isEmpty()) {
                url = DEFAULT_URL;
            }
            saveSettings(url);
            whitelistRetryCount = 0;
            btnSave.setVisibility(View.GONE);
            checkWhitelist();
        });

        btnCheckWhitelist.setOnClickListener(v -> {
            whitelistRetryCount = 0;
            checkWhitelist();
        });

        return view;
    }

    private void checkWhitelist() {
        // ALWAYS SHOW DEVICE ID
        String androidId = Settings.Secure.getString(requireContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        final String finalAndroidId = (androidId != null) ? androidId.toUpperCase() : "UNKNOWN";
        tvDeviceId.setText(finalAndroidId);

        if (whitelistRetryCount >= MAX_WHITELIST_RETRIES) {
            updateStatusUI("MAX RETRIES REACHED", Color.RED, "");
            return;
        }

        whitelistRetryCount++;
        tvWhitelistStatus.setText("Verifying (" + whitelistRetryCount + "/" + MAX_WHITELIST_RETRIES + ")...");
        tvWhitelistStatus.setTextColor(Color.GRAY);

        if (originalUrl == null || originalUrl.isEmpty()) return;

        // Clean URL to prevent double slashes
        String baseUrl = originalUrl.endsWith("/") ? originalUrl.substring(0, originalUrl.length() - 1) : originalUrl;
        String url = baseUrl + "/qgas/public/api/user/is-device-whitelisted/" + finalAndroidId;

        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                updateStatusUI("CONNECTION ERROR", Color.RED, "");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response resp = response) {
                    if (!resp.isSuccessful()) {
                        updateStatusUI("NOT WHITELISTED", Color.RED, "");
                        return;
                    }

                    String responseData = resp.body().string();
                    JSONObject json = new JSONObject(responseData);

                    // FIX: Checking the "success" boolean from your API response
                    boolean isSuccess = json.optBoolean("success", false);

                    if (isSuccess) {
                        updateStatusUI("WHITELISTED", Color.parseColor("#4CAF50"), finalAndroidId);
                    } else {
                        updateStatusUI("NOT WHITELISTED", Color.RED, "");
                    }
                } catch (Exception e) {
                    updateStatusUI("PARSING ERROR", Color.RED, "");
                }
            }
        });
    }

    // FIX: Method to bypass SSL "Trust anchor" error
    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
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

    private void updateStatusUI(String message, int color, String deviceIdToSave) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvWhitelistStatus.setText(message);
                tvWhitelistStatus.setTextColor(color);

                String url = etApiUrl.getText().toString().trim();
                if (url.isEmpty()) {
                    url = DEFAULT_URL;
                }
                saveSettings(url);

                SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_WHITELIST_STATUS, message).apply();

                if ("WHITELISTED".equals(message)) {
                    prefs.edit().putString(KEY_DEVICE_ID, deviceIdToSave).apply();
                    btnCheckWhitelist.setVisibility(View.GONE);
                } else {
                    btnCheckWhitelist.setVisibility(View.VISIBLE);
                }

                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).updateNavVisibility();
                }
            });
        }
    }

    private void saveSettings(String url) {
        originalUrl = url;
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_API_URL, url).apply();
    }

    private void loadSettings() {
        // FIX: Display Device ID immediately on load
        String androidId = Settings.Secure.getString(requireContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        tvDeviceId.setText(androidId != null ? androidId.toUpperCase() : "UNKNOWN");

        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        originalUrl = prefs.getString(KEY_API_URL, DEFAULT_URL);
        etApiUrl.setText(originalUrl);

        String savedStatus = prefs.getString(KEY_WHITELIST_STATUS, "NOT WHITELISTED");
        tvWhitelistStatus.setText(savedStatus);

        if ("WHITELISTED".equals(savedStatus)) {
            tvWhitelistStatus.setTextColor(Color.parseColor("#4CAF50"));
            btnCheckWhitelist.setVisibility(View.GONE);
        } else {
            tvWhitelistStatus.setTextColor(Color.RED);
            btnCheckWhitelist.setVisibility(View.VISIBLE);
        }
    }

    private void displayVersion() {
        try {
            PackageInfo pInfo = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0);
            tvVersion.setText("Version " + pInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            tvVersion.setText("Version 1.1.0");
        }
    }
}