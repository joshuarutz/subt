package com.openai.livesubtitles;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private EditText apiKey;
    private Spinner modelSpinner;
    private EditText customModel;
    private EditText outputLanguage;
    private TextView statusText;
    private boolean pendingStart = false;

    private static final String[] MODEL_NAMES = {
            "Deepgram Nova-3",
            "Microsoft MAI Transcribe 1.5",
            "Google Chirp 3",
            "Custom OpenRouter STT model"
    };

    private static final String[] MODEL_IDS = {
            "deepgram/nova-3",
            "microsoft/mai-transcribe-1.5",
            "google/chirp-3",
            ""
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apiKey = findViewById(R.id.apiKey);
        modelSpinner = findViewById(R.id.modelSpinner);
        customModel = findViewById(R.id.customModel);
        outputLanguage = findViewById(R.id.outputLanguage);
        statusText = findViewById(R.id.statusText);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                MODEL_NAMES
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);

        modelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                customModel.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        startButton.setOnClickListener(v -> beginStartFlow());
        stopButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SubtitleService.class);
            intent.setAction(SubtitleService.ACTION_STOP);
            startService(intent);
            statusText.setText("Stopped");
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void beginStartFlow() {
        String key = apiKey.getText().toString().trim();
        if (key.isEmpty()) {
            apiKey.setError("Enter your OpenRouter API key");
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true;
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQ_OVERLAY);
            return;
        }

        startSubtitleService();
    }

    private void startSubtitleService() {
        int position = modelSpinner.getSelectedItemPosition();
        String model = MODEL_IDS[position];
        if (position == 3) {
            model = customModel.getText().toString().trim();
            if (model.isEmpty()) {
                customModel.setError("Enter an OpenRouter model ID");
                return;
            }
        }

        String language = outputLanguage.getText().toString().trim();
        if (language.isEmpty()) language = "German";

        Intent intent = new Intent(this, SubtitleService.class);
        intent.setAction(SubtitleService.ACTION_START);
        intent.putExtra(SubtitleService.EXTRA_API_KEY, apiKey.getText().toString().trim());
        intent.putExtra(SubtitleService.EXTRA_STT_MODEL, model);
        intent.putExtra(SubtitleService.EXTRA_OUTPUT_LANGUAGE, language);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        statusText.setText("Running — speak near the microphone. The overlay can be dragged.");
        pendingStart = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingStart) beginStartFlow();
            } else {
                Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show();
                pendingStart = false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                if (pendingStart) beginStartFlow();
            } else {
                Toast.makeText(this, "Overlay permission is required to show subtitles over other apps.", Toast.LENGTH_LONG).show();
                pendingStart = false;
            }
        }
    }
}
