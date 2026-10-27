package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.service.dreams.DreamService;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.File;

/**
 * Screensaver: a page of the owner's own, dream/index.html pushed beside config.json, shown full screen in a WebView,
 * such as a live animated scene with a clock. Local files only: the launcher has no network permission, so the page
 * can load nothing from outside the TV. Without that page it is the launcher's clock screensaver (ClockDream).
 */
public class WebDream extends DreamService {

    static final String DIR = "dream";

    private WebView web;
    private ClockScene scene;

    /** The page this screensaver shows, or null when none was pushed. */
    static File page(android.content.Context ctx) {
        File dir = ctx.getExternalFilesDir(DIR);
        File f = dir == null ? null : new File(dir, "index.html");
        return f != null && f.isFile() ? f : null;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        File f = page(this);
        if (f == null) {
            setScreenBright(false);
            setContentView(R.layout.dream_clock);
            scene = new ClockScene(getWindow().getDecorView());
            scene.useHomeWallpaper(this);
            return;
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // The page and its scripts are files next to it; nothing else is reachable (no INTERNET permission).
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setFocusable(false);
        // The page's console goes to the TV's log (tag WebDream): the only way to see a scene's errors on a screensaver.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("WebDream", m.messageLevel() + " " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(Uri.fromFile(f).toString());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
        if (web != null) web.onResume();
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        if (web != null) web.onPause();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDetachedFromWindow();
    }
}
