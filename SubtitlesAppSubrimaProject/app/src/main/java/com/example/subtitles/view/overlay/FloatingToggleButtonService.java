package com.example.subtitles.view.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.subtitles.R;

/**
 * Service responsible for displaying a floating toggle button overlay.
 * This button allows the user to enable or disable live subtitles,
 * and communicates with SubtitleOverlayService to show/hide the subtitles.
 * <p>
 * The service runs as a foreground service to remain active while the app
 * is in the background.
 */
public class FloatingToggleButtonService extends Service {
    // Logging tag
    private static final String TAG = "FloatingToggleButtonService";
    // Notification ID for foreground service
    private static final int NOTIFICATION_ID = 2001;
    // Notification channel ID (Android O+)
    private static final String CHANNEL_ID = "toggle_overlay_channel";
    // Singleton instance for static access
    private static FloatingToggleButtonService instance;
    // Handler on main thread for UI updates
    private final Handler handler = new Handler(Looper.getMainLooper());
    // System window manager for overlay
    private WindowManager windowManager;
    // Root view of the floating toggle button overlay
    private View floatingButton;
    // Current toggle state (subtitles on/off)
    private boolean subtitlesOn = false;
    // Layout parameters for positioning overlay
    private WindowManager.LayoutParams params;

    // Device screen height (used for vertical clamping)
    private int screenHeight;

    // Margin in pixels to keep overlay within bounds
    private int marginPx;

    // Initial vertical offset for overlay
    private int initialOffsetPx;

    // Listener interface for toggle state changes
    private OnToggleListener toggleListener;

    /**
     * Registers a listener to be notified when the toggle button is changed.
     * Can be called from any component with a reference to the service.
     *
     * @param listener implementation of OnToggleListener
     */
    public static void registerListener(OnToggleListener listener) {
        if (instance != null) {
            instance.toggleListener = listener;
        }
    }
    /**
     * Shows the floating overlay programmatically.
     */
    public static void showOverlay() {
        if (instance != null) instance.handler.post(() -> instance.setOverlayVisible(true));
    }
    /**
     * Hides the floating overlay programmatically.
     */
    public static void hideOverlay() {
        if (instance != null) instance.handler.post(() -> instance.setOverlayVisible(false));
    }
    /**
     * Adds or removes the floating overlay view to/from the WindowManager.
     *
     * @param visible true to show overlay, false to hide
     */
    private void setOverlayVisible(boolean visible) {
        try {
            if (visible && floatingButton.getParent() == null) {
                windowManager.addView(floatingButton, params);
            } else if (!visible && floatingButton.getParent() != null) {
                windowManager.removeViewImmediate(floatingButton);
            }
        } catch (IllegalArgumentException ignored) {
            Log.w(TAG, "setOverlayVisible view not attached, skipping updateLayout()", ignored);
        }
    }
    /**
     * Service creation callback.
     * Initializes overlay, notification, layout parameters, and drag behavior.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        // Set singleton instance
        instance = this;

        // Create notification channel (for Android O+)
        createNotificationChannel();

        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification());
        // Get system window manager
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Inflate the floating button layout with theme context
        ContextThemeWrapper tc = new ContextThemeWrapper(this, R.style.Theme_Subtitles);
        floatingButton = LayoutInflater.from(tc)
                .inflate(R.layout.floating_toggle_button, null, false);

        // Compute screen height and margin for clamping overlay
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;

        marginPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, dm);
        int marginHorizontal = marginPx;

        // Initialize switch toggle and listener
        SwitchCompat toggle = floatingButton.findViewById(R.id.toggleSwitch);
        toggle.setChecked(subtitlesOn);
        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            subtitlesOn = isChecked;
            // Notify listener if any
            if (toggleListener != null) toggleListener.onToggle(isChecked);
            // Show or hide subtitle overlay accordingly
            if (isChecked) SubtitleOverlayService.showOverlay();
            else SubtitleOverlayService.hideOverlay();
        });

        // Prepare layout params for overlay
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        // Initial position: top-left corner
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        // Initial vertical offset in pixels
        int offsetDp = 200;
        initialOffsetPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, offsetDp, dm);
        params.y = initialOffsetPx;

        // Attach vertical drag behavior to handle view
        View handle = floatingButton.findViewById(R.id.dragHandle);
        makeDraggableVertical(handle, params);

        // Broadcast that service is ready
        Intent ready = new Intent("com.example.subtitles.ACTION_TOGGLE_SERVICE_READY");
        LocalBroadcastManager.getInstance(this).sendBroadcast(ready);
    }

    /**
     * Enables vertical dragging of the floating button within screen bounds.
     *
     * @param view view to attach drag listener
     * @param p    layout params to update during drag
     */
    private void makeDraggableVertical(View view,
                                       WindowManager.LayoutParams p) {
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                // Calculate new Y position centered on touch
                int newY = (int) (event.getRawY() - v.getHeight() / 2);
                // Clamp to screen height with margins
                p.y = Math.max(marginPx,
                        Math.min(newY,
                                screenHeight - floatingButton.getHeight() - marginPx));
                // Update overlay position
                windowManager.updateViewLayout(floatingButton, p);
            }
            return true; // consume touch events
        });
    }
    /**
     * Service start command callback.
     * Ensures overlay is visible.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        setOverlayVisible(true);
        return START_STICKY;
    }
    /**
     * Service destruction callback.
     * Cleans up overlay and handler callbacks.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Remove all pending handler callbacks
        handler.removeCallbacksAndMessages(null);
        // Remove overlay if attached
        if (floatingButton.getParent() != null) {
            windowManager.removeViewImmediate(floatingButton);
        }
        // Clear singleton instance
        instance = null;
    }

    /**
     * This service is not bound; return null.
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    /**
     * Called on configuration changes (e.g., screen rotation).
     * Adjusts overlay position and screen height for correct placement.
     *
     * @param newConfig new configuration
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;

        if (newConfig.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Snap overlay to bottom with margin
            floatingButton.post(() -> {
                params.y = screenHeight - floatingButton.getHeight() - marginPx;
                // extra clamp just in case
                params.y = Math.max(marginPx,
                        Math.min(params.y,
                                screenHeight - floatingButton.getHeight() - marginPx));
                // Update layout if attached
                if (floatingButton.getParent() != null) {
                    try {
                        windowManager.updateViewLayout(floatingButton, params);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "onConfigurationChanged view not attached, skipping updateLayout()", e);
                    }
                }
            });
        } else {
            // Restore initial vertical offset
            params.y = initialOffsetPx;
            if (floatingButton.getParent() != null) {
                try {
                    windowManager.updateViewLayout(floatingButton, params);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Subtitle view not attached, skipping updateLayout()", e);
                }
            }
        }
    }
    /**
     * Creates the foreground service notification.
     *
     * @return Notification instance
     */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("subtitles")
                .setContentText("Overlay service running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    /**
     * Creates a notification channel for Android O+ to support foreground service.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Subtitle Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    /**
     * Listener interface to notify external components when toggle state changes.
     */
    public interface OnToggleListener {
        void onToggle(boolean isOn);
    }
}
