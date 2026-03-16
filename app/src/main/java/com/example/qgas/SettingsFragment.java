package com.example.qgas;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {

    private TextInputEditText etApiUrl;
    private Button btnSave;
    private TextView tvVersion;

    private static final String PREFS_NAME = "AppConfig";
    private static final String KEY_API_URL = "api_base_url";
    private static final String DEFAULT_URL = "http://localhost:9090/qgas/public/api";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // Initialize Views
        etApiUrl = view.findViewById(R.id.et_api_key);
        btnSave = view.findViewById(R.id.btn_save_api);
        tvVersion = view.findViewById(R.id.tv_version);

        loadSettings();
        loadVersionInfo();

        btnSave.setOnClickListener(v -> {
            String url = etApiUrl.getText().toString().trim();

            if (url.isEmpty()) {
                url = DEFAULT_URL;
                etApiUrl.setText(url);
            }

            saveSettings(url);
        });

        return view;
    }

    private void saveSettings(String url) {
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_API_URL, url).apply();
        Toast.makeText(getContext(), "Saved: " + url, Toast.LENGTH_SHORT).show();
    }

    private void loadSettings() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_API_URL, DEFAULT_URL);
        etApiUrl.setText(savedUrl);
    }

    private void loadVersionInfo() {
        try {
            Context context = requireContext();
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String version = pInfo.versionName;
            tvVersion.setText("Version " + version);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            tvVersion.setText("Version 1.0.0");
        }
    }
}