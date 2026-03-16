package com.example.qgas;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {

    private LinearLayout btnHome, btnGas, btnSettings;
    private View activeView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        hideSystemUI();

        // New: Handle Back Press to show modal
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
        btnSettings = findViewById(R.id.nav_item_settings);

        btnHome.setAlpha(0.6f);
        btnGas.setAlpha(0.6f);
        btnSettings.setAlpha(0.6f);

        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
            setActive(btnHome);
        }

        btnHome.setOnClickListener(v -> { loadFragment(new HomeFragment()); setActive(btnHome); });
        btnGas.setOnClickListener(v -> { loadFragment(new GasFragment()); setActive(btnGas); });
        btnSettings.setOnClickListener(v -> { loadFragment(new SettingsFragment()); setActive(btnSettings); });
    }

    private void showExitDialog() {
        // Fix: Use LayoutInflater to get the custom view
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_exit, null);

        // Fix: Removed the specific R.style reference that caused the error
        // Standard AlertDialog.Builder will use your app's default theme
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnExit = dialogView.findViewById(R.id.btn_exit);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnExit.setOnClickListener(v -> finish());

        dialog.show();

        // Re-hide UI if the dialog caused system bars to appear
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
}