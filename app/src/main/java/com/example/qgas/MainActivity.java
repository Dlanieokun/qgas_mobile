package com.example.qgas;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import androidx.activity.EdgeToEdge;
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

        // Enable Fullscreen / Hide System Bars
        hideSystemUI();

        // Adjust bottom padding for system gestures
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });

        btnHome = findViewById(R.id.nav_item_home);
        btnGas = findViewById(R.id.nav_item_center_gas);
        btnSettings = findViewById(R.id.nav_item_settings);

        // --- INITIAL STATE: Set all to 60% opacity ---
        btnHome.setAlpha(0.6f);
        btnGas.setAlpha(0.6f);
        btnSettings.setAlpha(0.6f);

        // --- DEFAULT SELECTION ---
        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
            setActive(btnHome); // This will animate Home to 100% and scale up
        }

        // Click Listeners
        btnHome.setOnClickListener(v -> { loadFragment(new HomeFragment()); setActive(btnHome); });
        btnGas.setOnClickListener(v -> { loadFragment(new GasFragment()); setActive(btnGas); });
        btnSettings.setOnClickListener(v -> { loadFragment(new SettingsFragment()); setActive(btnSettings); });
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

        // Reset previous active view to 60% opacity and normal size
        if (activeView != null) {
            animateScale(activeView, 1.0f, 0.6f);
        }

        // Set new active view to 100% opacity and 1.3x size
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