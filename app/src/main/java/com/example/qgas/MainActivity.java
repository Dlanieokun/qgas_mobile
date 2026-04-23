package com.example.qgas;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private LinearLayout btnHome, btnGas, btnMap, btnSettings;
    private View activeView = null;

    private static final String PREFS_NAME = "AppConfig";
    private static final String KEY_WHITELIST_STATUS = "whitelist_status";

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
    }

    private void checkAndRequestPermissions() {
        // Define the permissions needed for gas station OCR and mapping
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(permission);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            permissionLauncher.launch(listPermissionsNeeded.toArray(new String[0]));
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
}