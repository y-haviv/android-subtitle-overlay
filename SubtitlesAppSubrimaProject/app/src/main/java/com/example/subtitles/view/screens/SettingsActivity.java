package com.example.subtitles.view.screens;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.subtitles.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
/**
 * Activity for configuring application settings:
 * - Source language
 * - Subtitle language
 * - Smart correction toggle
 * <p>
 * Provides help dialogs for each setting and respects device locale
 * and layout direction (LTR/RTL).
 */
public class SettingsActivity extends AppCompatActivity {
    // SharedPreferences file name
    private static final String PREFS = "subrima_prefs";
    // Default value for smart correction toggle
    private boolean smartDefault = false;
    // Container layout for all settings rows
    private LinearLayout container;

    /**
     * Called when activity is created.
     * Initializes layout, preferences, language spinners, and smart correction switch.
     *
     * @param savedInstanceState saved state bundle
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Set default subtitle language if not already set
        if (!prefs.contains("pref_subtitle_lang")) {
            String deviceLang = Locale.getDefault().getLanguage(); // e.g., "he","en"
            String[] supported = getResources().getStringArray(R.array.lang_codes);
            boolean ok = Arrays.asList(supported).contains(deviceLang);
            String def = ok ? deviceLang : "en";
            prefs.edit().putString("pref_subtitle_lang", def).apply();
        }
        // Apply layout direction based on subtitle language
        String subLang = prefs.getString("pref_subtitle_lang", "en");
        int dir = TextUtils.getLayoutDirectionFromLocale(new Locale(subLang));
        getWindow().getDecorView().setLayoutDirection(dir);


        setContentView(R.layout.activity_settings);
        // Back button listener
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        container = findViewById(R.id.llSettingsContainer);

        // Setup language spinners
        Spinner srcSpinner = findViewById(R.id.sourceLangRow)
                .findViewById(R.id.spinnerLanguages);
        setupLanguageSpinner(srcSpinner, "pref_source_lang", true);

        Spinner subSpinner = findViewById(R.id.subtitleLangRow)
                .findViewById(R.id.spinnerLanguages);
        setupLanguageSpinner(subSpinner, "pref_subtitle_lang", false);

        // Setup smart correction toggle
        Switch smartSwitch = findViewById(R.id.smartCorrectionRow)
                .findViewById(R.id.switchValue);
        setupSmartSwitch(smartSwitch, "pref_smart_correction");
    }
    /**
     * Configures a language spinner with available languages.
     *
     * @param spinner     the spinner view to setup
     * @param prefKey     SharedPreferences key to store selected language
     * @param includeAuto whether to include "Auto" option
     */
    private void setupLanguageSpinner(Spinner spinner,
                                      String prefKey,
                                      boolean includeAuto) {
        String[] codes = getResources().getStringArray(R.array.lang_codes);
        String[] names = getResources().getStringArray(R.array.lang_names);
        List<String> codeList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        // Build filtered lists (exclude "auto" if needed)
        for (int i = 0; i < codes.length; i++) {
            if (!includeAuto && "auto".equals(codes[i])) continue;
            codeList.add(codes[i]);
            nameList.add(names[i]);
        }
        // Create adapter for spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, nameList
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // Load saved preference and set initial selection
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String def = includeAuto ? "auto" : "en";
        String saved = prefs.getString(prefKey, def);
        int idx = codeList.indexOf(saved);
        spinner.setSelection(idx >= 0 ? idx : 0);
        // Handle selection changes
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putString(prefKey, codeList.get(pos)).apply();
                // Update all labels if subtitle language changed
                if (prefKey.equals("pref_subtitle_lang")) {
                    updateAllLabelsTo(codeList.get(pos));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // Setup help dialog for this spinner
        View parentRow = (View) spinner.getParent();
        ImageButton btnHelp = parentRow.findViewById(R.id.btnHelp);
        btnHelp.setOnClickListener(v -> {
            String title = includeAuto
                    ? getString(R.string.help_source_title)
                    : getString(R.string.help_subtitle_title);
            String msgKey = includeAuto ? "help_source" : "help_subtitle";

            String text = getTranslatedHelpText(msgKey);
            View dialogView = getLayoutInflater().inflate(R.layout.custom_alert_dialog, null);
            TextView titleView = dialogView.findViewById(R.id.dialogTitle);
            TextView messageView = dialogView.findViewById(R.id.dialogMessage);

            titleView.setText(title);
            titleView.setGravity(Gravity.CENTER);
            messageView.setText(text);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();

            dialog.show();


        });
    }
    /**
     * Retrieves help text from resources dynamically.
     *
     * @param key string resource name
     * @return translated help text
     */
    private String getTranslatedHelpText(String key) {
        int resId = getResources().getIdentifier(key, "string", getPackageName());
        return resId != 0
                ? getString(resId)
                : getString(R.string.help_default_text);
    }

    /**
     * Updates all labels in the settings screen to a new language.
     *
     * @param newLangCode new language code ("en", "ru", etc.)
     */
    private void updateAllLabelsTo(String newLangCode) {
        // Apply locale
        Locale locale = new Locale(newLangCode);
        Locale.setDefault(locale);
        Resources res = getResources();
        Configuration cfg = new Configuration(res.getConfiguration());
        cfg.setLocale(locale);
        Context locCtx = createConfigurationContext(cfg);

        // Update each label
        TextView srcLbl = findViewById(R.id.sourceLangRow)
                .findViewById(R.id.tvLabel);
        TextView subLbl = findViewById(R.id.subtitleLangRow)
                .findViewById(R.id.tvLabel);
        TextView smartLbl = findViewById(R.id.smartCorrectionRow)
                .findViewById(R.id.tvLabel);

        srcLbl.setText(locCtx.getString(R.string.source_language));
        subLbl.setText(locCtx.getString(R.string.subtitle_language));
        smartLbl.setText(locCtx.getString(R.string.smart_correction));
        // Update layout direction (LTR/RTL)
        int dir = TextUtils.getLayoutDirectionFromLocale(new Locale(newLangCode));
        getWindow().getDecorView().setLayoutDirection(dir);

    }
    /**
     * Configures the smart correction switch with saved preferences and help dialog.
     *
     * @param smartSwitch the switch view
     * @param prefKey     SharedPreferences key to save switch state
     */
    private void setupSmartSwitch(Switch smartSwitch, String prefKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean isOn = prefs.getBoolean(prefKey, smartDefault);
        smartSwitch.setChecked(isOn);
        // Update preference when switch is toggled
        smartSwitch.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(prefKey, checked).apply()
        );
        // Setup help dialog
        View parentRow = (View) smartSwitch.getParent();
        ImageButton btnHelp = parentRow.findViewById(R.id.btnHelp);
        btnHelp.setOnClickListener(v -> {
            String title = getString(R.string.help_correction_title);
            String msgKey = "help_correction";

            String text = getTranslatedHelpText(msgKey);
            View dialogView = getLayoutInflater().inflate(R.layout.custom_alert_dialog, null);
            TextView titleView = dialogView.findViewById(R.id.dialogTitle);
            TextView messageView = dialogView.findViewById(R.id.dialogMessage);

            titleView.setText(title);
            titleView.setGravity(Gravity.CENTER);
            messageView.setText(text);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();

            dialog.show();


        });
    }
}
