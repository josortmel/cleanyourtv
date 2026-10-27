package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.function.Consumer;

/**
 * An open folder (config.xml folders) over Home, which stays behind it blurred, as on tvOS. Its tiles are Home's own
 * (an AppAdapter for the folder, given the tiles Home's reload made), so they look, focus and open the same; hold OK
 * on one asks the host for the folder's tile menu. Back closes it; MainActivity closes it when Home leaves the screen.
 */
final class FolderDialog implements AppAdapter.TileListener {

    interface Host {
        /** Hold OK on the folder's tile at this position. */
        void onFolderTileLongPress(FolderDialog folder, int position, View card);

        void onFolderClosed(FolderDialog folder);
    }

    /** Rows shown before the grid scrolls. */
    private static final int MAX_ROWS = 3;
    /** Blur of Home behind the folder, in pixels of GlassBackdrop's small copy. */
    private static final int BLUR = 3;

    final String folderId;
    final AppAdapter adapter;
    private final Dialog dialog;
    private final View root, content, backdrop;
    private final TextView title;
    private final RecyclerView grid;
    private final Host host;
    private final boolean animations;
    private final int columns, itemHeight;
    private boolean closing;

    /**
     * @param home   Home's grid: the folder's grid takes its width and padding, so the tiles fall in its columns
     * @param behind Home blurred (captureBehind), or null for the plain dark backdrop
     */
    FolderDialog(Activity activity, Config cfg, int columns, int tileWidth, View home, String folderId, String name,
                 List<AppAdapter.Entry> tiles, Bitmap behind, Host host) {
        this.folderId = folderId;
        this.host = host;
        this.columns = columns;
        this.animations = cfg.animations && !cfg.reducedMotion;
        float density = activity.getResources().getDisplayMetrics().density;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        root = activity.getLayoutInflater().inflate(R.layout.dialog_folder, new FrameLayout(activity), false);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setWindowAnimations(0);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        backdrop = root.findViewById(R.id.folderBackdrop);
        if (behind != null) ((ImageView) backdrop).setImageBitmap(behind);
        content = root.findViewById(R.id.folderContent);
        title = root.findViewById(R.id.folderName);
        title.setText(name);
        grid = root.findViewById(R.id.folderGrid);
        // Room above and below for the focus zoom and the lens tilt, as Home's grid keeps.
        int room = Math.round(18 * density);
        grid.setPadding(home.getPaddingLeft(), room, home.getPaddingRight(), room);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) grid.getLayoutParams();
        lp.width = home.getWidth() > 0 ? home.getWidth() : ViewGroup.LayoutParams.MATCH_PARENT;
        grid.setLayoutParams(lp);
        itemHeight = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileWidth), cfg.dockRow, density);
        grid.setLayoutManager(new GridLayoutManager(activity, columns));
        adapter = new AppAdapter(activity, this, columns, cfg, true);
        adapter.setTileWidth(tileWidth);
        grid.setAdapter(adapter);
        setTiles(tiles);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close(true);
            return true;
        });
        dialog.setOnDismissListener(d -> host.onFolderClosed(this));
    }

    /**
     * Calls back on the main thread with the window as it is now, blurred, for the folder's backdrop: null when the
     * window cannot be read (not drawn yet).
     */
    static void captureBehind(Activity activity, Consumer<Bitmap> done) {
        View decor = activity.getWindow().getDecorView();
        int w = decor.getWidth() / 2, h = decor.getHeight() / 2;
        if (w <= 0 || h <= 0) {
            done.accept(null);
            return;
        }
        Bitmap copy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), copy, result -> {
                Bitmap blurred = result == PixelCopy.SUCCESS ? GlassBackdrop.blur(copy, BLUR) : null;
                copy.recycle();
                done.accept(blurred);
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {   // the window has no surface yet
            copy.recycle();
            done.accept(null);
        }
    }

    void show() {
        dialog.show();
        if (animations) {
            backdrop.setAlpha(0f);
            backdrop.animate().alpha(1f).setDuration(220).start();
            content.setAlpha(0f);
            content.setScaleX(0.92f);
            content.setScaleY(0.92f);
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        focusAt(0);
    }

    /** The folder's tiles after a change (one taken out); focus stays on the place it was. */
    void setTiles(List<AppAdapter.Entry> tiles) {
        adapter.setTiles(tiles);
        int rows = (tiles.size() + columns - 1) / columns;
        ViewGroup.LayoutParams lp = grid.getLayoutParams();
        lp.height = rows > MAX_ROWS ? MAX_ROWS * itemHeight + grid.getPaddingTop() + grid.getPaddingBottom()
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        grid.setLayoutParams(lp);
    }

    /** The dialog's view, for a menu shown over it. */
    View root() {
        return root;
    }

    /** Puts focus on the tile at this position (or the last one) once the grid has laid it out. */
    void focusAt(int position) {
        grid.post(new Runnable() {
            int frames;

            @Override
            public void run() {
                int n = adapter.getItemCount();
                if (n == 0) return;
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(Math.min(position, n - 1));
                if (vh == null) {
                    if (++frames < 10) grid.postOnAnimation(this);
                    return;
                }
                vh.itemView.findViewById(R.id.card).requestFocus();
            }
        });
    }

    /** Closes the folder; animated, it shrinks back into Home first. */
    void close(boolean animate) {
        if (closing) return;
        closing = true;
        if (!animate || !animations || !dialog.isShowing()) {
            dialog.dismiss();
            return;
        }
        backdrop.animate().alpha(0f).setDuration(180).start();
        content.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(160)
                .withEndAction(dialog::dismiss).start();
    }

    // ---- the folder's tiles (AppAdapter.TileListener) -------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        return false;   // the grid moves focus; nothing beyond it in this window
    }

    @Override
    public void onLibraryRequested() {
    }

    @Override
    public void onLibraryFocused() {
    }

    @Override
    public void onTileLongPress(int position, View card) {
        if (!closing) host.onFolderTileLongPress(this, position, card);
    }

    @Override
    public void onLibraryUp() {
    }
}
