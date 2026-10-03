package app.revanced.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.revanced.extension.tiktok.settings.Settings;
import app.revanced.extension.tiktok.settings.SettingsStatus;
import app.revanced.extension.tiktok.settings.preference.InputTextPreference;
import app.revanced.extension.tiktok.settings.preference.PasswordPreference;
import app.revanced.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class SmartProxyPreferenceCategory extends ConditionalPreferenceCategory {
    public SmartProxyPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Smart proxy (API only)");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.smartProxyEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new TogglePreference(
                context,
                "Enable smart proxy",
                "Routes TikTok API/control traffic through the proxy while video/photo traffic stays direct.",
                Settings.SMART_PROXY_ENABLED
        ));
        addPreference(new InputTextPreference(
                context,
                "Proxy type",
                "http or socks5",
                Settings.SMART_PROXY_TYPE
        ));
        addPreference(new InputTextPreference(
                context,
                "Proxy host",
                "Hostname or IP of the upstream proxy.",
                Settings.SMART_PROXY_HOST
        ));
        addPreference(new InputTextPreference(
                context,
                "Proxy port",
                "For example 1080 or 8080.",
                Settings.SMART_PROXY_PORT
        ));
        addPreference(new InputTextPreference(
                context,
                "Proxy username",
                "Leave empty when authentication is not required.",
                Settings.SMART_PROXY_USERNAME
        ));
        addPreference(new PasswordPreference(
                context,
                "Proxy password",
                "Stored locally in TikTok app preferences.",
                Settings.SMART_PROXY_PASSWORD
        ));
        addPreference(new TogglePreference(
                context,
                "Direct fallback",
                "If the upstream proxy is unavailable, retry API traffic directly instead of breaking TikTok.",
                Settings.SMART_PROXY_FALLBACK_DIRECT
        ));
        addPreference(new InputTextPreference(
                context,
                "Extra API hosts",
                "Optional comma/space separated domains to proxy. Subdomains are included automatically.",
                Settings.SMART_PROXY_EXTRA_API_HOSTS
        ));
    }
}
