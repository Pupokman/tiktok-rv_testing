package app.revanced.extension.tiktok.settings.preference;

import android.content.Context;
import android.text.InputType;

import app.revanced.extension.shared.settings.StringSetting;

@SuppressWarnings("deprecation")
public class PasswordPreference extends InputTextPreference {
    public PasswordPreference(Context context, String title, String summary, StringSetting setting) {
        super(context, title, summary, setting);
        getEditText().setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
    }
}
