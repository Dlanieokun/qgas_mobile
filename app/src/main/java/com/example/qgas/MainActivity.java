package com.example.qgas;

import static androidx.core.content.ContentProviderCompat.requireContext;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private LinearLayout btnHome, btnGas, btnMap, btnSettings;
    private View activeView = null;

    private final OkHttpClient client = getUnsafeOkHttpClient();

    private static final String PREFS_NAME = "AppConfig";
    private static final String KEY_WHITELIST_STATUS = "whitelist_status";

    private static String version = "";
    private static String latestVersionFromServer = "";
    private static String UPDATE_URL = "";

    // Launcher for handling multiple runtime permissions
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Map.Entry<String, Boolean> entry : result.entrySet()) {
                    if (!entry.getValue()) {
                        allGranted = false;
                        break;
                    }
                }

                if (allGranted) {
                    Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Permissions are required for OCR and Location features", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        hideSystemUI();

        // Check for Camera and Location permissions on startup
        checkAndRequestPermissions();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showExitDialog();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });

        btnHome = findViewById(R.id.nav_item_home);
        btnGas = findViewById(R.id.nav_item_center_gas);
        btnMap = findViewById(R.id.nav_item_map);
        btnSettings = findViewById(R.id.nav_item_settings);

        btnHome.setAlpha(0.6f);
        btnGas.setAlpha(0.6f);
        btnMap.setAlpha(0.6f);
        btnSettings.setAlpha(0.6f);

        updateNavVisibility();

        btnHome.setOnClickListener(v -> { loadFragment(new HomeFragment()); setActive(btnHome); });
        btnGas.setOnClickListener(v -> { loadFragment(new GasFragment()); setActive(btnGas); });
        btnMap.setOnClickListener(v -> { loadFragment(new MapFragment()); setActive(btnMap); });
        btnSettings.setOnClickListener(v -> { loadFragment(new SettingsFragment()); setActive(btnSettings); });

        try {
            android.content.pm.PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pInfo.versionName;


            SharedPreferences sp = getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String baseUrl = sp.getString("api_base_url", "https://qgas.site");
            String url = baseUrl + "/public/api/mobile-version";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("Response","Error :" + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response resp = response) {
                        if (resp.isSuccessful()) {
                            // Move the Toast to the UI Thread
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Server Connected", Toast.LENGTH_SHORT).show();
                            });

                            try {
                                String responseData = resp.body().string();
                                JSONObject json = new JSONObject(responseData);
                                JSONArray ver = json.getJSONArray("data");
                                latestVersionFromServer = ver.getJSONObject(0).getString("version");
                                UPDATE_URL = ver.getJSONObject(0).getString("link");

                                runOnUiThread(() -> checkForUpdate(latestVersionFromServer));

                            } catch (Exception e) {
                                Log.e("Response", "JSON Parsing error", e);
                            }
                        } else {
                            Log.e("Response", "Server Error: " + resp.code());
                        }
                    }
                }
            });
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }


    private void checkAndRequestPermissions() {
        // Define the permissions needed for gas station OCR, mapping, and updates
        List<String> listPermissionsNeeded = new ArrayList<>();

        // Existing permissions from your manifest
        listPermissionsNeeded.add(Manifest.permission.CAMERA);
        listPermissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        listPermissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        // Handle Storage Permissions based on Android Version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires specific media permissions instead of generic READ_EXTERNAL_STORAGE
            listPermissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            // For Android 12 and below, use the permissions declared in your manifest
            listPermissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            // Note: WRITE_EXTERNAL_STORAGE is often required for older update/download flows
            listPermissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> remainingPermissions = new ArrayList<>();
        for (String permission : listPermissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                remainingPermissions.add(permission);
            }
        }

        if (!remainingPermissions.isEmpty()) {
            permissionLauncher.launch(remainingPermissions.toArray(new String[0]));
        }
    }

    public void updateNavVisibility() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String status = prefs.getString(KEY_WHITELIST_STATUS, "NOT WHITELISTED");

        if ("WHITELISTED".equals(status)) {
            btnHome.setVisibility(View.VISIBLE);
            btnGas.setVisibility(View.VISIBLE);
            btnMap.setVisibility(View.VISIBLE);

            if (activeView == null) {
                loadFragment(new HomeFragment());
                setActive(btnHome);
            }
        } else {
            btnHome.setVisibility(View.GONE);
            btnGas.setVisibility(View.GONE);
            btnMap.setVisibility(View.GONE);

            loadFragment(new SettingsFragment());
            setActive(btnSettings);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        updateNavVisibility();
    }

    private void showExitDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_exit, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnExit = dialogView.findViewById(R.id.btn_exit);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnExit.setOnClickListener(v -> finish());

        dialog.show();
        dialog.setOnDismissListener(d -> hideSystemUI());
    }

    private void loadFragment(Fragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        ft.replace(R.id.fragment_container, fragment);
        ft.commit();
    }

    private void setActive(View newActive) {
        if (activeView == newActive) return;
        if (activeView != null) {
            animateScale(activeView, 1.0f, 0.6f);
        }
        animateScale(newActive, 1.7f, 1.0f);
        activeView = newActive;
    }

    private void animateScale(View view, float scale, float alpha) {
        view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .alpha(alpha)
                .setDuration(250)
                .start();
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    public void navigateToHome() {
        loadFragment(new HomeFragment());
        setActive(btnHome);
    }

    public void navigateToMap() {
        loadFragment(new MapFragment());
        setActive(btnMap);
    }

    private void checkForUpdate(String latestVersion) {
        try {
            double currentVer = Double.parseDouble(version);
            double newVer = Double.parseDouble(latestVersion);

            if (newVer > currentVer) {
                showUpdateDialog();
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
    }

    private void showUpdateDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("The Version you have is old version do you want to update your app?")
                .setCancelable(false) // Prevents closing by clicking outside
                .setPositiveButton("Yes", (d, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(UPDATE_URL));
                    startActivity(intent);
                    finish(); // Close app so they must install update
                })
                .setNegativeButton("No", (d, which) -> {
                    finishAffinity(); // Shutdown the app entirely
                    System.exit(0);
                })
                .create();

        dialog.show();
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