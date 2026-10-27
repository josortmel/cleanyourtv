package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.LauncherApps;
import android.os.Bundle;
import android.widget.Toast;

/**
 * An app asks to pin a shortcut to Home (pinned_shortcuts), as Moonlight does for "Create shortcut" on a game. The
 * owner already chose it in that app, so it is accepted without a second question; Home rebuilds through
 * LauncherApps' shortcut callback and the new tile appears.
 */
public class PinShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LauncherApps la = getSystemService(LauncherApps.class);
        LauncherApps.PinItemRequest request = la == null ? null : la.getPinItemRequest(getIntent());
        boolean on = Config.load(this).pinnedShortcuts;
        if (request != null && on && request.isValid()
                && request.getRequestType() == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
                && request.getShortcutInfo() != null) {
            try {
                if (request.accept()) {
                    Toast.makeText(this, getString(R.string.shortcut_pinned,
                            PinnedShortcuts.label(request.getShortcutInfo())), Toast.LENGTH_SHORT).show();
                }
            } catch (IllegalStateException ignored) {
                // The request was answered or withdrawn in the meantime: nothing to add.
            }
        }
        finish();
    }
}
