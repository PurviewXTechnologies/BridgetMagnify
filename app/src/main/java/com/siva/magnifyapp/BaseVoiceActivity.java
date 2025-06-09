package com.siva.magnifyapp;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;


import com.digilens.digios_voiceui_api.VoiceUI_Interface;
import com.digilens.digios_voiceui_api.utils.VoiceUI_Model;
import com.digilens.digios_voiceui_api.utils.VoiceUI_Listener;

import java.util.HashMap;
import java.util.Map;

import static com.digilens.digios_voiceui_api.utils.Constants.Voice_Command_CONFIG_TYPE_FEEDBACK_ONLY;

public abstract class BaseVoiceActivity extends AppCompatActivity {
    private VoiceUI_Interface voiceUIInterface;
    private VoiceUI_Model     voiceUIModel;
    private Map<String, Runnable> commands = new HashMap<>();

    /** Call in subclasses to map phrase → action */
    protected void registerVoiceCommand(@NonNull String phrase, @NonNull Runnable action) {
        commands.put(phrase, action);
    }

    /** Subclasses override to register their commands */
    protected abstract void setupVoiceCommands();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) Init the interface + model
        voiceUIInterface = new VoiceUI_Interface();
        try {
            voiceUIModel = new VoiceUI_Model("en"); // or "es", etc.
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // 2) Let subclass register commands
        setupVoiceCommands();

        // 3) Turn each into a VoiceUI_Listener and add to model
        for (Map.Entry<String, Runnable> e : commands.entrySet()) {
            String phrase = e.getKey();
            Runnable action = e.getValue();

            VoiceUI_Listener listener = null;
            try {
                listener = new VoiceUI_Listener(
                        phrase,
                        Voice_Command_CONFIG_TYPE_FEEDBACK_ONLY
                ) {
                    @Override
                    public void onReceive() {
                        action.run();
                    }
                    @Override
                    public void onReceive(int value) {
                        // unused for FEEDBACK_ONLY
                    }
                };
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            try {
                voiceUIModel.addVoiceUI_Listener(listener);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // 4) Register the model with the interface
        try {
            voiceUIInterface.add_model(voiceUIModel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 5) Start listening
        try {
            voiceUIInterface.start(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        // 6) Stop listening
        try {
            voiceUIInterface.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onPause();
    }
}
