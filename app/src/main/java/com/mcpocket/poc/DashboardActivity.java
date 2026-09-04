package com.mcpocket.poc;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Product-facing PickPico dashboard.
 *
 * MainActivity is intentionally retained as the legacy engineering console and can be opened from
 * Developer / Diagnostics. This Activity owns the user-facing information architecture.
 */
public final class DashboardActivity extends Activity {
    private static final int REQUEST_NOTIFICATION = 200;
    private static final int REQUEST_NODE_MEDIA = 201;
    private static final int REQUEST_CAMERA = 202;
    private static final int REQUEST_MICROPHONE = 203;
    private static final int REQUEST_LOCATION = 204;
    private static final int REQUEST_SCREEN_CAPTURE = 205;
    private static final int REQUEST_CONTACTS = 206;
    private static final int REQUEST_CALENDAR = 207;

    static final String EXTRA_PAGE = "dashboardPage";
    static final int PAGE_HOME = 0;
    static final int PAGE_CAPABILITIES = 1;
    static final int PAGE_SETTINGS = 2;
    private static final int PAGE_REMOTE = 3;
    private static final int PAGE_DEVELOPER = 4;

    private static final int BG = Color.rgb(6, 8, 10);
    private static final int TEXT = Color.rgb(242, 246, 248);
    private static final int MUTED = Color.rgb(139, 149, 158);
    private static final int DIM = Color.rgb(91, 101, 111);
    private static final int GREEN = Color.rgb(61, 214, 129);
    private static final int AMBER = Color.rgb(246, 169, 69);
    private static final int RED = Color.rgb(255, 91, 99);
    private static final int BLUE = Color.rgb(92, 177, 255);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    private FrameLayout contentHost;
    private TextView topBack;
    private TextView topTitle;
    private TextView topMeta;
    private TextView topStatusDot;
    private LinearLayout bottomNav;
    private TextView navHome;
    private TextView navCapabilities;
    private TextView navSettings;

    private int currentPage = PAGE_HOME;
    private boolean updatingUi;
    private PickPicoTheme.State theme;
    private PickPicoTheme.BackgroundView themeBackgroundView;
    private final List<ThemedCardRef> themedCards = new ArrayList<>();

    // Home
    private TextView homeReadyTitle;
    private TextView homeReadyDetail;
    private TextView homeRemoteState;
    private TextView homeRemoteDetail;
    private TextView homeApprovalState;
    private TextView homeInboxState;
    private TextView homeCapabilitiesState;
    private TextView homeNodeState;
    private TextView homeNodeAction;
    private TextView homeCopyAction;

    // Capabilities
    private Switch cameraSwitch;
    private Switch microphoneSwitch;
    private Switch locationSwitch;
    private Switch contactsSwitch;
    private Switch calendarSwitch;
    private Switch notificationSwitch;
    private Switch lockPhoneSwitch;
    private Switch hyperModeSwitch;
    private Switch accessibilitySwitch;
    private Switch screenCaptureSwitch;
    private TextView screenCaptureDetail;

    // Settings / update
    private TextView settingsRemoteState;
    private TextView settingsVersionState;
    private TextView settingsUpdateState;
    private TextView updateAction;
    private boolean updateCheckInProgress;
    private String updateCheckError;
    private Switch appearanceGradientSwitch;
    private TextView appearanceColorA;
    private TextView appearanceColorB;
    private SeekBar appearanceGlassOpacity;
    private TextView appearanceGlassValue;
    private SeekBar appearanceHighlight;
    private TextView appearanceHighlightValue;
    private SeekBar appearanceBackgroundIntensity;
    private TextView appearanceBackgroundValue;
    private View appearancePreview;

    // Remote access
    private EditText relayUrlInput;
    private TextView remoteState;
    private TextView remoteEndpointSummary;

    // Developer / diagnostics
    private TextView devLocalEndpoint;
    private TextView devRemoteEndpoint;
    private TextView devRelayStatus;
    private TextView devRelayUrl;
    private TextView devBearer;
    private TextView devRuntime;
    private TextView devRecent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = PickPicoTheme.load(this);
        configureWindow();
        setContentView(buildShell());
        requestNotificationPermissionIfNeeded();
        showPage(requestedPage(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showPage(requestedPage(intent));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (currentPage == PAGE_REMOTE || currentPage == PAGE_DEVELOPER) {
            showPage(PAGE_SETTINGS);
            return;
        }
        if (currentPage != PAGE_HOME) {
            showPage(PAGE_HOME);
            return;
        }
        super.onBackPressed();
    }

    private void configureWindow() {
        applyWindowTheme();
    }

    private void applyWindowTheme() {
        Window window = getWindow();
        boolean light = PickPicoTheme.isLightBackground(theme);
        int barColor = theme != null && !theme.gradient ? theme.colorA : BG;
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(barColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private View buildShell() {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(BG);

        themeBackgroundView = new PickPicoTheme.BackgroundView(this, theme);
        stage.addView(themeBackgroundView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        stage.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        contentHost = new FrameLayout(this);
        shell.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomNav = buildBottomNav();
        shell.addView(bottomNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        return stage;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), 0);

        topBack = text("‹", 34, Typeface.NORMAL, TEXT);
        topBack.setGravity(Gravity.CENTER);
        topBack.setVisibility(View.GONE);
        topBack.setOnClickListener(v -> onBackPressed());
        bar.addView(topBack, new LinearLayout.LayoutParams(dp(38), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        topTitle = text("PickPico", 23, Typeface.BOLD, TEXT);
        titles.addView(topTitle);

        topMeta = text("MOBILE AGENT NODE", 10, Typeface.BOLD, DIM);
        topMeta.setLetterSpacing(0.08f);
        titles.addView(topMeta);

        topStatusDot = text("●", 13, Typeface.BOLD, DIM);
        topStatusDot.setGravity(Gravity.CENTER);
        bar.addView(topStatusDot, new LinearLayout.LayoutParams(dp(28), dp(48)));
        return bar;
    }

    private int requestedPage(Intent intent) {
        int page = intent == null ? PAGE_HOME : intent.getIntExtra(EXTRA_PAGE, PAGE_HOME);
        return page == PAGE_CAPABILITIES || page == PAGE_SETTINGS ? page : PAGE_HOME;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(10), dp(7), dp(10), dp(8));
        nav.setBackground(PickPicoTheme.strongGlass(theme, dp(18)));
        nav.setElevation(dp(16));

        navHome = navItem("HOME", () -> showPage(PAGE_HOME));
        TextView navInbox = navItem("INBOX", () ->
                startActivity(new Intent(this, AgentInboxActivity.class)));
        navCapabilities = navItem("CAPABILITIES", () -> showPage(PAGE_CAPABILITIES));
        navSettings = navItem("SETTINGS", () -> showPage(PAGE_SETTINGS));

        nav.addView(navHome, navParams());
        nav.addView(navInbox, navParams());
        nav.addView(navCapabilities, navParams());
        nav.addView(navSettings, navParams());
        return nav;
    }

    private LinearLayout.LayoutParams navParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private TextView navItem(String label, Runnable action) {
        TextView item = text(label, 10, Typeface.BOLD, MUTED);
        item.setGravity(Gravity.CENTER);
        item.setLetterSpacing(0.04f);
        item.setOnClickListener(v -> action.run());
        return item;
    }

    private void showPage(int page) {
        currentPage = page;
        clearPageReferences();
        themedCards.clear();
        contentHost.removeAllViews();

        View content;
        if (page == PAGE_CAPABILITIES) {
            content = buildCapabilitiesPage();
            configureTopBar("Capabilities", "WHAT AGENTS CAN DO", false);
        } else if (page == PAGE_SETTINGS) {
            content = buildSettingsPage();
            configureTopBar("Settings", "PICKPICO", false);
        } else if (page == PAGE_REMOTE) {
            content = buildRemoteAccessPage();
            configureTopBar("Remote Access", "CONNECT FROM ANYWHERE", true);
        } else if (page == PAGE_DEVELOPER) {
            content = buildDeveloperPage();
            configureTopBar("Developer / Diagnostics", "ADVANCED", true);
        } else {
            content = buildHomePage();
            configureTopBar("PickPico", "MOBILE AGENT NODE", false);
        }

        contentHost.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateBottomNav();
        refreshStatus();
    }

    private void configureTopBar(String title, String meta, boolean back) {
        topTitle.setText(title);
        topMeta.setText(meta);
        topBack.setVisibility(back ? View.VISIBLE : View.GONE);
    }

    private void updateBottomNav() {
        int active = currentPage;
        if (active == PAGE_REMOTE || active == PAGE_DEVELOPER) {
            active = PAGE_SETTINGS;
        }
        setNavActive(navHome, active == PAGE_HOME);
        setNavActive(navCapabilities, active == PAGE_CAPABILITIES);
        setNavActive(navSettings, active == PAGE_SETTINGS);
    }

    private void setNavActive(TextView view, boolean active) {
        applyTextColor(view, active ? GREEN : MUTED);
        view.setBackground(active ? pillDrawable(Color.argb(28, 61, 214, 129), Color.argb(80, 61, 214, 129)) : null);
    }

    private void clearPageReferences() {
        homeReadyTitle = null;
        homeReadyDetail = null;
        homeRemoteState = null;
        homeRemoteDetail = null;
        homeApprovalState = null;
        homeInboxState = null;
        homeCapabilitiesState = null;
        homeNodeState = null;
        homeNodeAction = null;
        homeCopyAction = null;

        cameraSwitch = null;
        microphoneSwitch = null;
        locationSwitch = null;
        contactsSwitch = null;
        calendarSwitch = null;
        notificationSwitch = null;
        lockPhoneSwitch = null;
        hyperModeSwitch = null;
        accessibilitySwitch = null;
        screenCaptureSwitch = null;
        screenCaptureDetail = null;

        settingsRemoteState = null;
        settingsVersionState = null;
        settingsUpdateState = null;
        updateAction = null;

        relayUrlInput = null;
        remoteState = null;
        remoteEndpointSummary = null;

        devLocalEndpoint = null;
        devRemoteEndpoint = null;
        devRelayStatus = null;
        devRelayUrl = null;
        devBearer = null;
        devRuntime = null;
        devRecent = null;
    }

    private View buildHomePage() {
        LinearLayout root = pageRoot();

        LinearLayout readyCard = glassCard(true);
        LinearLayout readyHeading = new LinearLayout(this);
        readyHeading.setOrientation(LinearLayout.HORIZONTAL);
        readyHeading.setGravity(Gravity.CENTER_VERTICAL);
        readyCard.addView(readyHeading);

        TextView readyDot = text("●", 18, Typeface.BOLD, GREEN);
        readyHeading.addView(readyDot, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        homeReadyTitle = text("READY · LOCAL", 19, Typeface.BOLD, GREEN);
        readyHeading.addView(homeReadyTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        homeReadyDetail = text("PickPico is available to agents on this network.", 13, Typeface.NORMAL, MUTED);
        homeReadyDetail.setPadding(0, dp(7), 0, dp(12));
        readyCard.addView(homeReadyDetail);

        homeCopyAction = actionButton("COPY CONNECTION", false, false);
        homeCopyAction.setOnClickListener(v -> copyConnection());
        readyCard.addView(homeCopyAction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        root.addView(readyCard, cardParams(0));

        LinearLayout remote = homeRowCard(
                "REMOTE ACCESS",
                "Let cloud agents reach this phone outside your local network.",
                () -> showPage(PAGE_REMOTE));
        homeRemoteState = rowState(remote, "NOT CONFIGURED", AMBER);
        homeRemoteDetail = findDetail(remote);
        root.addView(remote, cardParams(12));

        LinearLayout approval = homeRowCard(
                "APPROVAL MODE",
                "Controls how much autonomy PickPico gives an agent.",
                this::showApprovalModeDialog);
        homeApprovalState = rowState(approval, "AUTO APPROVE", GREEN);
        root.addView(approval, cardParams(12));

        LinearLayout inbox = homeRowCard(
                "AGENT INBOX",
                "Messages and requests kept by PickPico.",
                () -> startActivity(new Intent(this, AgentInboxActivity.class)));
        homeInboxState = rowState(inbox, "0 MESSAGES", BLUE);
        root.addView(inbox, cardParams(12));

        LinearLayout capabilities = homeRowCard(
                "CAPABILITIES",
                "Manage what agents can use on this device.",
                () -> showPage(PAGE_CAPABILITIES));
        homeCapabilitiesState = rowState(capabilities, "CHECKING", MUTED);
        root.addView(capabilities, cardParams(12));

        LinearLayout node = glassCard(false);
        LinearLayout nodeLine = new LinearLayout(this);
        nodeLine.setOrientation(LinearLayout.HORIZONTAL);
        nodeLine.setGravity(Gravity.CENTER_VERTICAL);
        node.addView(nodeLine);

        LinearLayout nodeText = new LinearLayout(this);
        nodeText.setOrientation(LinearLayout.VERTICAL);
        nodeLine.addView(nodeText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nodeTitle = text("NODE", 12, Typeface.BOLD, TEXT);
        nodeTitle.setLetterSpacing(0.05f);
        nodeText.addView(nodeTitle);
        homeNodeState = text("RUNNING", 13, Typeface.BOLD, GREEN);
        homeNodeState.setPadding(0, dp(5), 0, 0);
        nodeText.addView(homeNodeState);

        homeNodeAction = actionButton("STOP", true, true);
        homeNodeAction.setOnClickListener(v -> toggleNodeFromHome());
        nodeLine.addView(homeNodeAction, new LinearLayout.LayoutParams(dp(92), dp(42)));
        root.addView(node, cardParams(12));

        addBottomSpace(root);
        return pageScroll(root);
    }

    private LinearLayout homeRowCard(String title, String detail, Runnable action) {
        LinearLayout card = glassCard(false);
        card.setOnClickListener(v -> action.run());

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(heading);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        heading.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = text(title, 14, Typeface.BOLD, TEXT);
        titleView.setLetterSpacing(0.04f);
        copy.addView(titleView);

        TextView detailView = text(detail, 12, Typeface.NORMAL, MUTED);
        detailView.setTag("detail");
        detailView.setPadding(0, dp(5), dp(8), 0);
        copy.addView(detailView);

        TextView arrow = text("›", 29, Typeface.NORMAL, DIM);
        arrow.setGravity(Gravity.CENTER);
        heading.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(44)));
        return card;
    }

    private TextView rowState(LinearLayout card, String initial, int color) {
        LinearLayout heading = (LinearLayout) card.getChildAt(0);
        LinearLayout copy = (LinearLayout) heading.getChildAt(0);
        TextView state = text(initial, 11, Typeface.BOLD, color);
        state.setLetterSpacing(0.035f);
        state.setPadding(0, dp(8), 0, 0);
        copy.addView(state, 1);
        return state;
    }

    private TextView findDetail(LinearLayout card) {
        LinearLayout heading = (LinearLayout) card.getChildAt(0);
        LinearLayout copy = (LinearLayout) heading.getChildAt(0);
        for (int i = 0; i < copy.getChildCount(); i++) {
            View child = copy.getChildAt(i);
            if (child instanceof TextView && "detail".equals(child.getTag())) {
                return (TextView) child;
            }
        }
        return null;
    }

    private View buildCapabilitiesPage() {
        LinearLayout root = pageRoot();

        TextView intro = text("PickPico only exposes capabilities you allow. Android system grants remain the final security boundary.", 13, Typeface.NORMAL, MUTED);
        intro.setPadding(dp(2), 0, dp(2), dp(14));
        root.addView(intro);

        TextView deviceHeading = sectionLabel("DEVICE CONTROLS");
        root.addView(deviceHeading);

        LinearLayout deviceCard = glassCard(false);
        cameraSwitch = capabilityRow(deviceCard, "Camera", "Allow agents to capture photos.", checked -> {
            if (updatingUi) return;
            if (checked) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            else openAppPermissionSettings("Disable Camera permission in Android settings.");
        });
        addDivider(deviceCard);

        microphoneSwitch = capabilityRow(deviceCard, "Microphone", "Allow agents to record short audio clips.", checked -> {
            if (updatingUi) return;
            if (checked) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            else openAppPermissionSettings("Disable Microphone permission in Android settings.");
        });
        addDivider(deviceCard);

        locationSwitch = capabilityRow(deviceCard, "Location", "Allow agents to read this phone's location.", checked -> {
            if (updatingUi) return;
            if (checked) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            else openAppPermissionSettings("Disable Location permission in Android settings.");
        });
        addDivider(deviceCard);

        contactsSwitch = capabilityRow(deviceCard, "Contacts", "Allow agents to search and read contacts.", checked -> {
            if (updatingUi) return;
            if (checked) requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS);
            else openAppPermissionSettings("Disable Contacts permission in Android settings.");
        });
        addDivider(deviceCard);

        calendarSwitch = capabilityRow(deviceCard, "Calendar", "Allow agents to read and manage calendar events.", checked -> {
            if (updatingUi) return;
            if (checked) requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, REQUEST_CALENDAR);
            else openAppPermissionSettings("Disable Calendar permission in Android settings.");
        });
        addDivider(deviceCard);

        notificationSwitch = capabilityRow(deviceCard, "Read notifications", "Allow agents to inspect active Android notifications.", checked -> {
            if (updatingUi) return;
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });
        addDivider(deviceCard);

        lockPhoneSwitch = capabilityRow(deviceCard, "Lock phone", "Allow agents to lock this device.", checked -> {
            if (updatingUi) return;
            if (checked) requestDeviceAdmin();
            else disableDeviceAdmin();
        });
        root.addView(deviceCard, cardParams(7));

        TextView advancedHeading = sectionLabel("ADVANCED CAPABILITIES");
        advancedHeading.setPadding(0, dp(20), 0, dp(7));
        root.addView(advancedHeading);

        LinearLayout advancedCard = glassCard(false);
        hyperModeSwitch = capabilityRow(advancedCard, "Hyper Mode", "Advanced UI/screen control plus lock-screen dismissal for urgent Agent handoffs. Secure locks still require Android authentication.", checked -> {
            if (updatingUi) return;
            McpocketPolicySettings.setHyperModeEnabled(this, checked);
            boolean openedUnlockSetup = checked && AgentAttention.requestHyperUnlockAccessIfNeeded(this);
            Toast.makeText(this,
                    openedUnlockSetup
                            ? "Allow full-screen alerts so Hyper Mode can dismiss the lock screen"
                            : checked ? "Hyper Mode enabled" : "Hyper Mode disabled",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        addDivider(advancedCard);

        accessibilitySwitch = capabilityRow(advancedCard, "Accessibility · UI Control", "Inspect and operate accessible UI elements in other apps.", checked -> {
            if (updatingUi) return;
            if (!McpocketPolicySettings.isHyperModeEnabled(this)) {
                Toast.makeText(this, "Turn on Hyper Mode first", Toast.LENGTH_LONG).show();
                refreshStatus();
                return;
            }
            showAccessibilitySetupDialog();
        });
        addDivider(advancedCard);

        screenCaptureSwitch = capabilityRow(advancedCard, "Screen Capture", "User-authorized live screen capture session.", checked -> {
            if (updatingUi) return;
            toggleScreenCapture();
        });
        screenCaptureDetail = text("NOT ACTIVE", 10, Typeface.BOLD, AMBER);
        screenCaptureDetail.setLetterSpacing(0.035f);
        screenCaptureDetail.setPadding(0, dp(8), 0, 0);
        advancedCard.addView(screenCaptureDetail);

        root.addView(advancedCard, cardParams(7));
        addBottomSpace(root);
        return pageScroll(root);
    }

    private Switch capabilityRow(LinearLayout parent, String title, String detail, SwitchListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = text(title, 14, Typeface.BOLD, TEXT);
        copy.addView(titleView);
        TextView detailView = text(detail, 12, Typeface.NORMAL, MUTED);
        detailView.setPadding(0, dp(4), dp(12), 0);
        copy.addView(detailView);

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        tintSwitch(toggle);
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onChanged(isChecked));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(48)));
        return toggle;
    }

    private View buildSettingsPage() {
        LinearLayout root = pageRoot();

        TextView connectionHeading = sectionLabel("CONNECTION");
        root.addView(connectionHeading);

        LinearLayout remote = settingsRow(
                "Remote Access",
                "Connect cloud agents when the phone is outside their local network.",
                () -> showPage(PAGE_REMOTE));
        settingsRemoteState = rowState(remote, "NOT CONFIGURED", AMBER);
        root.addView(remote, cardParams(7));

        TextView appearanceHeading = sectionLabel("APPEARANCE");
        appearanceHeading.setPadding(0, dp(20), 0, dp(7));
        root.addView(appearanceHeading);
        root.addView(buildAppearanceCard(), cardParams(7));

        TextView appHeading = sectionLabel("APP");
        appHeading.setPadding(0, dp(20), 0, dp(7));
        root.addView(appHeading);

        LinearLayout update = glassCard(false);
        TextView updateTitle = text("PickPico Update", 14, Typeface.BOLD, TEXT);
        update.addView(updateTitle);
        settingsVersionState = text("Version —", 12, Typeface.NORMAL, MUTED);
        settingsVersionState.setPadding(0, dp(5), 0, 0);
        update.addView(settingsVersionState);
        settingsUpdateState = text("Checking update state…", 11, Typeface.BOLD, DIM);
        settingsUpdateState.setPadding(0, dp(8), 0, dp(11));
        update.addView(settingsUpdateState);
        updateAction = actionButton("CHECK / INSTALL UPDATE", false, false);
        updateAction.setOnClickListener(v -> checkOrInstallUpdate());
        update.addView(updateAction, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        root.addView(update, cardParams(7));

        TextView advancedHeading = sectionLabel("ADVANCED");
        advancedHeading.setPadding(0, dp(20), 0, dp(7));
        root.addView(advancedHeading);

        LinearLayout diagnostics = settingsRow(
                "Developer / Diagnostics",
                "Endpoints, transport state, credentials, runtime, and debug information.",
                () -> showPage(PAGE_DEVELOPER));
        rowState(diagnostics, "ADVANCED", DIM);
        root.addView(diagnostics, cardParams(7));

        addBottomSpace(root);
        return pageScroll(root);
    }

    private View buildAppearanceCard() {
        LinearLayout card = glassCard(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        header.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        copy.addView(text("THEME", 14, Typeface.BOLD, TEXT));
        TextView note = text("Choose a solid color or gradient, then tune how the glass sits above it.", 12, Typeface.NORMAL, MUTED);
        note.setPadding(0, dp(4), dp(12), 0);
        copy.addView(note);

        appearancePreview = new View(this);
        appearancePreview.setBackground(PickPicoTheme.preview(theme, dp(10)));
        header.addView(appearancePreview, new LinearLayout.LayoutParams(dp(82), dp(38)));

        appearanceGradientSwitch = new Switch(this);
        appearanceGradientSwitch.setText("Gradient background");
        applyTextColor(appearanceGradientSwitch, TEXT);
        appearanceGradientSwitch.setTextSize(13);
        appearanceGradientSwitch.setChecked(theme.gradient);
        appearanceGradientSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            refreshAppearancePreview();
            saveAppearanceFromControls();
        });
        tintSwitch(appearanceGradientSwitch);
        LinearLayout.LayoutParams gradientParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50));
        gradientParams.topMargin = dp(12);
        card.addView(appearanceGradientSwitch, gradientParams);

        appearanceColorA = appearanceColorInput(PickPicoTheme.toHex(theme.colorA));
        appearanceColorB = appearanceColorInput(PickPicoTheme.toHex(theme.colorB));
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.addView(appearanceColorControl("COLOR A", appearanceColorA), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bParams.leftMargin = dp(8);
        colors.addView(appearanceColorControl("COLOR B", appearanceColorB), bParams);
        card.addView(colors);

        LinearLayout solidPresets = new LinearLayout(this);
        solidPresets.setOrientation(LinearLayout.HORIZONTAL);
        solidPresets.setPadding(0, dp(12), 0, 0);
        addSolidBackgroundPreset(solidPresets, "BLACK", "#000000");
        addSolidBackgroundPreset(solidPresets, "WHITE", "#ffffff");
        card.addView(solidPresets);

        TextView styleLabel = sectionLabel("GLASS");
        styleLabel.setPadding(dp(2), dp(16), 0, dp(7));
        card.addView(styleLabel);

        LinearLayout glassPresets = new LinearLayout(this);
        glassPresets.setOrientation(LinearLayout.HORIZONTAL);
        addGlassPreset(glassPresets, "SOFT", 7, 12, 72);
        addGlassPreset(glassPresets, "CLEAR", 3, 30, 96);
        addGlassPreset(glassPresets, "CRISP", 1, 44, 76);
        card.addView(glassPresets);

        LinearLayout glassHeader = new LinearLayout(this);
        glassHeader.setOrientation(LinearLayout.HORIZONTAL);
        glassHeader.setGravity(Gravity.CENTER_VERTICAL);
        glassHeader.setPadding(0, dp(15), 0, 0);
        TextView glassTitle = text("TRANSPARENCY · SEE MORE BACKGROUND", 10, Typeface.BOLD, MUTED);
        glassTitle.setLetterSpacing(0.06f);
        glassHeader.addView(glassTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        appearanceGlassValue = text((100 - theme.glassOpacity) + "%", 11, Typeface.BOLD, TEXT);
        glassHeader.addView(appearanceGlassValue);
        card.addView(glassHeader);

        appearanceGlassOpacity = new SeekBar(this);
        appearanceGlassOpacity.setMax(29);
        appearanceGlassOpacity.setProgress(theme.glassOpacity - 1);
        appearanceGlassOpacity.setProgressTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
        appearanceGlassOpacity.setThumbTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
        appearanceGlassOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (appearanceGlassValue != null) appearanceGlassValue.setText((99 - progress) + "%");
                refreshAppearancePreview();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveAppearanceFromControls(); }
        });
        card.addView(appearanceGlassOpacity, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout highlightHeader = new LinearLayout(this);
        highlightHeader.setOrientation(LinearLayout.HORIZONTAL);
        highlightHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView highlightTitle = text("SHINE · BRIGHTER GLASS EDGE", 10, Typeface.BOLD, MUTED);
        highlightTitle.setLetterSpacing(0.06f);
        highlightHeader.addView(highlightTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        appearanceHighlightValue = text(theme.highlight + "%", 11, Typeface.BOLD, TEXT);
        highlightHeader.addView(appearanceHighlightValue);
        card.addView(highlightHeader);

        appearanceHighlight = new SeekBar(this);
        appearanceHighlight.setMax(44);
        appearanceHighlight.setProgress(theme.highlight - 4);
        appearanceHighlight.setProgressTintList(ColorStateList.valueOf(PickPicoTheme.accentB(theme)));
        appearanceHighlight.setThumbTintList(ColorStateList.valueOf(PickPicoTheme.accentB(theme)));
        appearanceHighlight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (appearanceHighlightValue != null) appearanceHighlightValue.setText((progress + 4) + "%");
                refreshAppearancePreview();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveAppearanceFromControls(); }
        });
        card.addView(appearanceHighlight, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout backgroundHeader = new LinearLayout(this);
        backgroundHeader.setOrientation(LinearLayout.HORIZONTAL);
        backgroundHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView backgroundTitle = text("COLOR STRENGTH · MORE / LESS BACKGROUND", 10, Typeface.BOLD, MUTED);
        backgroundTitle.setLetterSpacing(0.04f);
        backgroundHeader.addView(backgroundTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        appearanceBackgroundValue = text(theme.backgroundIntensity + "%", 11, Typeface.BOLD, TEXT);
        backgroundHeader.addView(appearanceBackgroundValue);
        card.addView(backgroundHeader);

        appearanceBackgroundIntensity = new SeekBar(this);
        appearanceBackgroundIntensity.setMax(70);
        appearanceBackgroundIntensity.setProgress(theme.backgroundIntensity - 30);
        appearanceBackgroundIntensity.setProgressTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
        appearanceBackgroundIntensity.setThumbTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
        appearanceBackgroundIntensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (appearanceBackgroundValue != null) appearanceBackgroundValue.setText((progress + 30) + "%");
                refreshAppearancePreview();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveAppearanceFromControls(); }
        });
        card.addView(appearanceBackgroundIntensity, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        return card;
    }

    private TextView appearanceColorInput(String value) {
        TextView input = new TextView(this);
        input.setSingleLine(true);
        input.setText(value);
        applyTextColor(input, TEXT);
        input.setTextSize(13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setContentDescription("Choose color " + value);
        input.setBackground(PickPicoTheme.control(theme, dp(12), PickPicoTheme.accentA(theme), false));
        return input;
    }

    private View appearanceColorControl(String label, TextView input) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        TextView title = text(label, 9, Typeface.BOLD, DIM);
        title.setLetterSpacing(0.05f);
        title.setPadding(dp(4), 0, 0, dp(5));
        wrapper.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView swatch = text("", 1, Typeface.NORMAL, TEXT);
        int fallback = input == appearanceColorA ? theme.colorA : theme.colorB;
        setColorSwatch(swatch, PickPicoTheme.parseHex(input.getText().toString(), fallback));
        View.OnClickListener chooseColor = v -> showColorPicker(input, swatch);
        swatch.setOnClickListener(chooseColor);
        input.setOnClickListener(chooseColor);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        inputParams.leftMargin = dp(7);
        row.addView(input, inputParams);
        return wrapper;
    }

    private void addSolidBackgroundPreset(LinearLayout parent, String label, String color) {
        TextView preset = text(label, 9, Typeface.BOLD, TEXT);
        preset.setGravity(Gravity.CENTER);
        int presetColor = PickPicoTheme.parseHex(color, theme.colorA);
        preset.setTag(R.id.theme_text_role, null);
        preset.setTextColor(Color.luminance(presetColor) >= 0.56f
                ? Color.rgb(22, 26, 29)
                : Color.rgb(242, 246, 248));
        PickPicoTheme.State previewTheme = new PickPicoTheme.State(
                false,
                presetColor,
                presetColor,
                theme.glassOpacity);
        preset.setBackground(PickPicoTheme.preview(previewTheme, dp(10)));
        preset.setOnClickListener(v -> {
            appearanceColorA.setText(color);
            appearanceColorB.setText(color);
            appearanceGradientSwitch.setChecked(false);
            refreshAppearancePreview();
            saveAppearanceFromControls();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1f);
        if (parent.getChildCount() > 0) params.leftMargin = dp(6);
        parent.addView(preset, params);
    }

    private void addGlassPreset(LinearLayout parent, String label, int opacity, int highlight, int intensity) {
        TextView preset = text(label, 9, Typeface.BOLD, TEXT);
        preset.setGravity(Gravity.CENTER);
        preset.setBackground(PickPicoTheme.control(theme, dp(10), PickPicoTheme.accentB(theme), false));
        preset.setOnClickListener(v -> {
            if (appearanceGlassOpacity != null) appearanceGlassOpacity.setProgress(opacity - 1);
            if (appearanceHighlight != null) appearanceHighlight.setProgress(highlight - 4);
            if (appearanceBackgroundIntensity != null) appearanceBackgroundIntensity.setProgress(intensity - 30);
            refreshAppearancePreview();
            saveAppearanceFromControls();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        if (parent.getChildCount() > 0) params.leftMargin = dp(7);
        parent.addView(preset, params);
    }

    private PickPicoTheme.State appearanceStateFromControls() {
        int opacity = appearanceGlassOpacity == null ? theme.glassOpacity : appearanceGlassOpacity.getProgress() + 1;
        int highlight = appearanceHighlight == null ? theme.highlight : appearanceHighlight.getProgress() + 4;
        int intensity = appearanceBackgroundIntensity == null ? theme.backgroundIntensity : appearanceBackgroundIntensity.getProgress() + 30;
        return new PickPicoTheme.State(
                appearanceGradientSwitch == null ? theme.gradient : appearanceGradientSwitch.isChecked(),
                PickPicoTheme.parseHex(
                        appearanceColorA == null ? PickPicoTheme.toHex(theme.colorA) : appearanceColorA.getText().toString(),
                        theme.colorA),
                PickPicoTheme.parseHex(
                        appearanceColorB == null ? PickPicoTheme.toHex(theme.colorB) : appearanceColorB.getText().toString(),
                        theme.colorB),
                opacity,
                highlight,
                intensity);
    }

    private void refreshAppearancePreview() {
        applyThemeLive(appearanceStateFromControls());
    }

    private void applyThemeLive(PickPicoTheme.State next) {
        theme = next;
        applyWindowTheme();
        if (themeBackgroundView != null) {
            themeBackgroundView.setState(theme);
        }
        if (bottomNav != null) {
            bottomNav.setBackground(PickPicoTheme.strongGlass(theme, dp(18)));
        }
        for (ThemedCardRef ref : themedCards) {
            ref.card.setBackground(PickPicoTheme.card(theme, dp(18), ref.accented));
        }
        if (appearancePreview != null) {
            appearancePreview.setBackground(PickPicoTheme.preview(theme, dp(10)));
        }
        if (appearanceGlassOpacity != null) {
            appearanceGlassOpacity.setProgressTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
            appearanceGlassOpacity.setThumbTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
        }
        if (appearanceHighlight != null) {
            appearanceHighlight.setProgressTintList(ColorStateList.valueOf(PickPicoTheme.accentB(theme)));
            appearanceHighlight.setThumbTintList(ColorStateList.valueOf(PickPicoTheme.accentB(theme)));
        }
        if (appearanceBackgroundIntensity != null) {
            appearanceBackgroundIntensity.setProgressTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
            appearanceBackgroundIntensity.setThumbTintList(ColorStateList.valueOf(PickPicoTheme.accentA(theme)));
        }
        View decor = getWindow().getDecorView();
        if (decor != null) refreshThemeTextColors(decor);
    }

    private void saveAppearanceFromControls() {
        PickPicoTheme.State next = appearanceStateFromControls();
        theme = PickPicoTheme.save(
                this,
                next.gradient,
                PickPicoTheme.toHex(next.colorA),
                PickPicoTheme.toHex(next.colorB),
                next.glassOpacity,
                next.highlight,
                next.backgroundIntensity);
        applyThemeLive(theme);
    }

    private void setColorSwatch(View swatch, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(11));
        drawable.setStroke(dp(1), Color.argb(120, 255, 255, 255));
        swatch.setBackground(drawable);
    }

    private void showColorPicker(TextView target, View externalSwatch) {
        int fallback = target == appearanceColorA ? theme.colorA : theme.colorB;
        int initial = PickPicoTheme.parseHex(target.getText().toString(), fallback);
        float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        int[] selected = new int[]{initial};

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(10), dp(18), dp(4));

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(previewRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        View swatch = new View(this);
        setColorSwatch(swatch, initial);
        previewRow.addView(swatch, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView hex = text(PickPicoTheme.toHex(initial).toUpperCase(), 16, Typeface.BOLD, TEXT);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setPadding(dp(14), 0, 0, 0);
        previewRow.addView(hex, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SeekBar hue = colorPickerSlider(body, "HUE", 360, Math.round(hsv[0]));
        SeekBar saturation = colorPickerSlider(body, "SATURATION", 100, Math.round(hsv[1] * 100f));
        SeekBar value = colorPickerSlider(body, "BRIGHTNESS", 100, Math.round(hsv[2] * 100f));

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                hsv[0] = hue.getProgress();
                hsv[1] = saturation.getProgress() / 100f;
                hsv[2] = value.getProgress() / 100f;
                selected[0] = Color.HSVToColor(hsv);
                setColorSwatch(swatch, selected[0]);
                hex.setText(PickPicoTheme.toHex(selected[0]).toUpperCase());
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
        hue.setOnSeekBarChangeListener(listener);
        saturation.setOnSeekBarChangeListener(listener);
        value.setOnSeekBarChangeListener(listener);

        TextView paletteLabel = text("QUICK COLORS", 9, Typeface.BOLD, MUTED);
        paletteLabel.setLetterSpacing(0.06f);
        paletteLabel.setPadding(0, dp(12), 0, dp(7));
        body.addView(paletteLabel);

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        int[] quickColors = new int[]{
                Color.BLACK, Color.WHITE, Color.rgb(75, 31, 102),
                Color.rgb(23, 52, 77), Color.rgb(21, 93, 74), Color.rgb(170, 80, 146)
        };
        for (int color : quickColors) {
            View chip = new View(this);
            setColorSwatch(chip, color);
            chip.setOnClickListener(v -> {
                Color.colorToHSV(color, hsv);
                hue.setProgress(Math.round(hsv[0]));
                saturation.setProgress(Math.round(hsv[1] * 100f));
                value.setProgress(Math.round(hsv[2] * 100f));
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (quick.getChildCount() > 0) chipParams.leftMargin = dp(6);
            quick.addView(chip, chipParams);
        }
        body.addView(quick, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        new AlertDialog.Builder(this)
                .setTitle("Choose color")
                .setView(body)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use color", (dialog, which) -> {
                    target.setText(PickPicoTheme.toHex(selected[0]));
                    setColorSwatch(externalSwatch, selected[0]);
                    refreshAppearancePreview();
                    saveAppearanceFromControls();
                })
                .show();
    }

    private SeekBar colorPickerSlider(LinearLayout parent, String label, int max, int progress) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        TextView title = text(label, 9, Typeface.BOLD, MUTED);
        title.setLetterSpacing(0.05f);
        row.addView(title);
        SeekBar slider = new SeekBar(this);
        slider.setMax(max);
        slider.setProgress(progress);
        row.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        parent.addView(row);
        return slider;
    }

    private LinearLayout settingsRow(String title, String detail, Runnable action) {
        return homeRowCard(title.toUpperCase(), detail, action);
    }

    private View buildRemoteAccessPage() {
        LinearLayout root = pageRoot();

        LinearLayout stateCard = glassCard(true);
        TextView label = sectionLabel("REMOTE ACCESS");
        label.setPadding(0, 0, 0, dp(7));
        stateCard.addView(label);
        remoteState = text("NOT CONFIGURED", 17, Typeface.BOLD, AMBER);
        stateCard.addView(remoteState);
        remoteEndpointSummary = text("Local access still works. A compatible relay is required for cloud agents outside your LAN.", 12, Typeface.NORMAL, MUTED);
        remoteEndpointSummary.setPadding(0, dp(7), 0, 0);
        stateCard.addView(remoteEndpointSummary);
        root.addView(stateCard, cardParams(0));

        LinearLayout explainer = glassCard(false);
        TextView explainerTitle = text("HOW IT WORKS", 12, Typeface.BOLD, TEXT);
        explainerTitle.setLetterSpacing(0.05f);
        explainer.addView(explainerTitle);
        explainer.addView(stepText("1", "Deploy a PickPico-compatible relay. The Cloudflare Worker + Durable Object in the repository is the reference implementation."));
        explainer.addView(stepText("2", "Paste the relay base URL below. PickPico keeps the outbound connection itself; your PC does not need to stay on."));
        explainer.addView(stepText("3", "Restart PickPico after saving. When the relay connects, cloud agents can use the stable remote MCP URL."));
        root.addView(explainer, cardParams(12));

        TextView urlLabel = sectionLabel("RELAY URL");
        urlLabel.setPadding(0, dp(20), 0, dp(7));
        root.addView(urlLabel);

        relayUrlInput = new EditText(this);
        relayUrlInput.setSingleLine(true);
        relayUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        applyTextColor(relayUrlInput, TEXT);
        applyHintColor(relayUrlInput, DIM);
        relayUrlInput.setTextSize(14);
        relayUrlInput.setHint("https://your-relay.workers.dev");
        relayUrlInput.setPadding(dp(14), 0, dp(14), 0);
        relayUrlInput.setBackground(PickPicoTheme.control(theme, dp(14), PickPicoTheme.accentA(theme), false));
        root.addView(relayUrlInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView save = actionButton("SAVE RELAY", false, false);
        save.setOnClickListener(v -> saveRelayUrl());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        saveParams.topMargin = dp(10);
        root.addView(save, saveParams);

        TextView note = text("Leave the field empty to disable remote access. PickPico does not assume that every open-source installation uses the project relay.", 12, Typeface.NORMAL, MUTED);
        note.setPadding(dp(2), dp(12), dp(2), 0);
        root.addView(note);

        addBottomSpace(root);
        return pageScroll(root);
    }

    private View stepText(String number, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        TextView badge = text(number, 11, Typeface.BOLD, GREEN);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(pillDrawable(Color.argb(26, 61, 214, 129), Color.argb(80, 61, 214, 129)));
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView copy = text(body, 12, Typeface.NORMAL, MUTED);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(10);
        row.addView(copy, copyParams);
        return row;
    }

    private View buildDeveloperPage() {
        LinearLayout root = pageRoot();

        TextView warning = text("Technical information for debugging and advanced setup. Normal PickPico use should not require anything on this page.", 12, Typeface.NORMAL, MUTED);
        warning.setPadding(dp(2), 0, dp(2), dp(14));
        root.addView(warning);

        LinearLayout endpoints = glassCard(false);
        endpoints.addView(sectionLabel("ENDPOINTS"));
        devLocalEndpoint = diagnosticValue(endpoints, "Local MCP", "—", true);
        devRemoteEndpoint = diagnosticValue(endpoints, "Remote MCP", "—", true);
        root.addView(endpoints, cardParams(0));

        LinearLayout relay = glassCard(false);
        relay.addView(sectionLabel("RELAY"));
        devRelayStatus = diagnosticValue(relay, "Status", "—", false);
        devRelayUrl = diagnosticValue(relay, "Relay URL", "—", true);
        root.addView(relay, cardParams(12));

        LinearLayout auth = glassCard(false);
        auth.addView(sectionLabel("AUTHENTICATION"));
        devBearer = diagnosticValue(auth, "Local bearer", "—", true);
        root.addView(auth, cardParams(12));

        LinearLayout runtime = glassCard(false);
        runtime.addView(sectionLabel("RUNTIME"));
        devRuntime = diagnosticValue(runtime, "Node", "—", false);
        devRecent = diagnosticValue(runtime, "Recent action", "—", false);
        root.addView(runtime, cardParams(12));

        TextView legacy = actionButton("OPEN LEGACY ENGINEERING CONSOLE", false, false);
        legacy.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams legacyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        legacyParams.topMargin = dp(14);
        root.addView(legacy, legacyParams);

        addBottomSpace(root);
        return pageScroll(root);
    }

    private TextView diagnosticValue(LinearLayout parent, String label, String value, boolean selectable) {
        TextView labelView = text(label.toUpperCase(), 10, Typeface.BOLD, DIM);
        labelView.setLetterSpacing(0.04f);
        labelView.setPadding(0, dp(13), 0, dp(4));
        parent.addView(labelView);

        TextView valueView = text(value, 12, Typeface.NORMAL, TEXT);
        valueView.setTypeface(Typeface.MONOSPACE);
        valueView.setTextIsSelectable(selectable);
        valueView.setPadding(dp(10), dp(8), dp(10), dp(8));
        valueView.setBackground(pillDrawable(Color.argb(80, 0, 0, 0), Color.argb(34, 255, 255, 255)));
        parent.addView(valueView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return valueView;
    }

    private LinearLayout pageRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(18));
        return root;
    }

    private ScrollView pageScroll(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout glassCard(boolean accented) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(PickPicoTheme.card(theme, dp(18), accented));
        card.setElevation(dp(14));
        themedCards.add(new ThemedCardRef(card, accented));
        return card;
    }

    private LinearLayout.LayoutParams cardParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 10, Typeface.BOLD, MUTED);
        view.setLetterSpacing(0.08f);
        view.setPadding(dp(2), 0, 0, dp(7));
        return view;
    }

    private TextView text(String value, int sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        applyTextColor(view, color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private void applyTextColor(TextView view, int roleColor) {
        view.setTag(R.id.theme_text_role, roleColor);
        view.setTextColor(resolveThemeTextColor(roleColor));
    }

    private void applyHintColor(TextView view, int roleColor) {
        view.setTag(R.id.theme_hint_role, roleColor);
        view.setHintTextColor(resolveThemeTextColor(roleColor));
    }

    private int resolveThemeTextColor(int roleColor) {
        if (!PickPicoTheme.isLightBackground(theme)) return roleColor;
        if (roleColor == TEXT) return Color.rgb(22, 26, 29);
        if (roleColor == MUTED) return Color.rgb(73, 82, 90);
        if (roleColor == DIM) return Color.rgb(108, 117, 125);
        if (roleColor == GREEN) return Color.rgb(18, 121, 67);
        if (roleColor == AMBER) return Color.rgb(166, 94, 0);
        if (roleColor == RED) return Color.rgb(194, 39, 49);
        if (roleColor == BLUE) return Color.rgb(24, 101, 174);
        return roleColor;
    }

    private void refreshThemeTextColors(View view) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            Object role = textView.getTag(R.id.theme_text_role);
            if (role instanceof Integer) {
                textView.setTextColor(resolveThemeTextColor((Integer) role));
            }
            Object hintRole = textView.getTag(R.id.theme_hint_role);
            if (hintRole instanceof Integer) {
                textView.setHintTextColor(resolveThemeTextColor((Integer) hintRole));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshThemeTextColors(group.getChildAt(i));
            }
        }
    }

    private TextView actionButton(String label, boolean danger, boolean compact) {
        int accent = danger ? RED : GREEN;
        TextView button = text(label, compact ? 11 : 11, Typeface.BOLD, danger ? RED : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(0.05f);
        button.setBackground(PickPicoTheme.control(theme, dp(12), accent, true));
        return button;
    }

    private Drawable pillDrawable(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(Color.argb(26, 255, 255, 255));
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void addBottomSpace(LinearLayout root) {
        Space space = new Space(this);
        root.addView(space, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
    }

    private void tintSwitch(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[]{
                Color.rgb(218, 255, 232),
                Color.rgb(130, 139, 147)
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{
                GREEN,
                Color.rgb(55, 61, 67)
        }));
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        boolean running = McpNodeService.isNodeRunning();
        String localEndpoint = prefs.getString(McpNodeService.KEY_ENDPOINT, "");
        String remoteEndpoint = prefs.getString(McpNodeService.KEY_REMOTE_ENDPOINT, "");
        String relayUrl = prefs.getString(McpNodeService.KEY_RELAY_BASE_URL, "");
        String relayStatus = prefs.getString(McpNodeService.KEY_RELAY_STATUS, "disabled");
        String token = prefs.getString(McpNodeService.KEY_TOKEN, "");
        boolean relayConfigured = !TextUtils.isEmpty(relayUrl);
        boolean relayConnected = "connected".equals(relayStatus) && !TextUtils.isEmpty(remoteEndpoint);

        CapabilitySummary capabilitySummary = capabilitySummary();
        int enabledCapabilities = capabilitySummary.available;
        int needSetup = capabilitySummary.total - enabledCapabilities;

        if (topStatusDot != null) {
            int dotColor = !running ? RED : relayConfigured && !relayConnected ? AMBER : GREEN;
            String dotState = !running
                    ? "Node stopped"
                    : relayConfigured && !relayConnected ? "Node local only; relay disconnected" : "Node ready";
            applyTextColor(topStatusDot, dotColor);
            topStatusDot.setContentDescription(dotState);
        }

        if (homeReadyTitle != null) {
            if (!running) {
                homeReadyTitle.setText("OFFLINE");
                applyTextColor(homeReadyTitle, RED);
                homeReadyDetail.setText("PickPico is not available to agents.");
            } else if (relayConnected) {
                homeReadyTitle.setText("READY · LOCAL + RELAY");
                applyTextColor(homeReadyTitle, GREEN);
                homeReadyDetail.setText("Available on this network and remotely.");
            } else {
                homeReadyTitle.setText("READY · LOCAL");
                applyTextColor(homeReadyTitle, GREEN);
                homeReadyDetail.setText("Available to agents on this network.");
            }
            homeCopyAction.setVisibility(running ? View.VISIBLE : View.GONE);
        }

        if (homeRemoteState != null) {
            if (!relayConfigured) {
                setState(homeRemoteState, "NOT CONFIGURED", AMBER);
                if (homeRemoteDetail != null) homeRemoteDetail.setText("Set up a relay to allow remote agents to connect.");
            } else if (relayConnected) {
                setState(homeRemoteState, "CONNECTED", GREEN);
                if (homeRemoteDetail != null) homeRemoteDetail.setText("Cloud agents can reach this phone from outside your LAN.");
            } else {
                setState(homeRemoteState, relayStatus.toUpperCase(), AMBER);
                if (homeRemoteDetail != null) homeRemoteDetail.setText("Relay is configured but not currently connected.");
            }
        }

        if (homeApprovalState != null) {
            String approval = McpocketPolicySettings.approvalMode(this);
            if (McpocketPolicySettings.APPROVAL_ASK.equals(approval)) {
                setState(homeApprovalState, "ASK ME", BLUE);
            } else if (McpocketPolicySettings.APPROVAL_YOLO.equals(approval)) {
                setState(homeApprovalState, "YOLO MODE", RED);
            } else {
                setState(homeApprovalState, "AUTO APPROVE", GREEN);
            }
        }

        if (homeInboxState != null) {
            int inbox = AgentInboxStore.count(this);
            setState(homeInboxState, inbox == 1 ? "1 MESSAGE" : inbox + " MESSAGES", inbox > 0 ? BLUE : DIM);
        }

        if (homeCapabilitiesState != null) {
            String value = enabledCapabilities + " OF " + capabilitySummary.total + " READY"
                    + (needSetup > 0 ? " · " + needSetup + " NEED SETUP" : "");
            setState(homeCapabilitiesState, value, needSetup > 0 ? AMBER : GREEN);
        }

        if (homeNodeState != null) {
            setState(homeNodeState, running ? "RUNNING" : "STOPPED", running ? GREEN : RED);
            homeNodeAction.setText(running ? "STOP" : "START");
            applyTextColor(homeNodeAction, running ? RED : GREEN);
            homeNodeAction.setBackground(pillDrawable(
                    running ? Color.argb(30, 255, 91, 99) : Color.argb(22, 61, 214, 129),
                    running ? Color.argb(95, 255, 91, 99) : Color.argb(95, 61, 214, 129)));
        }

        updatingUi = true;
        try {
            if (cameraSwitch != null) cameraSwitch.setChecked(hasPermission(Manifest.permission.CAMERA));
            if (microphoneSwitch != null) microphoneSwitch.setChecked(hasPermission(Manifest.permission.RECORD_AUDIO));
            if (locationSwitch != null) locationSwitch.setChecked(hasLocationPermission());
            if (contactsSwitch != null) contactsSwitch.setChecked(hasPermission(Manifest.permission.READ_CONTACTS));
            if (calendarSwitch != null) calendarSwitch.setChecked(hasCalendarPermission());
            if (notificationSwitch != null) notificationSwitch.setChecked(McpNotificationListenerService.hasAccess(this));
            if (lockPhoneSwitch != null) lockPhoneSwitch.setChecked(hasDeviceAdmin());
            if (hyperModeSwitch != null) hyperModeSwitch.setChecked(McpocketPolicySettings.isHyperModeEnabled(this));
            if (accessibilitySwitch != null) accessibilitySwitch.setChecked(McpAccessibilityService.hasAccess(this));
            if (screenCaptureSwitch != null) screenCaptureSwitch.setChecked(ScreenCaptureService.isActive());
        } finally {
            updatingUi = false;
        }
        if (screenCaptureDetail != null) {
            setState(screenCaptureDetail,
                    ScreenCaptureService.isActive() ? "ACTIVE SESSION" : "NOT ACTIVE",
                    ScreenCaptureService.isActive() ? GREEN : AMBER);
        }

        if (settingsRemoteState != null) {
            if (!relayConfigured) setState(settingsRemoteState, "NOT CONFIGURED", AMBER);
            else if (relayConnected) setState(settingsRemoteState, "CONNECTED", GREEN);
            else setState(settingsRemoteState, relayStatus.toUpperCase(), AMBER);
        }

        JSONObject updateState = SelfUpdateManager.status(this, 0L);
        String version = updateState.optString("currentVersionName", BuildConfig.VERSION_NAME);
        long versionCode = updateState.optLong("currentVersionCode", BuildConfig.VERSION_CODE);
        String rawUpdateStatus = updateState.optString("status", "idle");
        String candidateVersion = updateState.optString(
                "candidateVersionName",
                updateState.optString("latestVersionName", ""));
        boolean hasCandidate = SelfUpdateManager.hasInstallableCandidate(this);

        if (settingsVersionState != null) {
            settingsVersionState.setText("Version " + version + " · build " + versionCode);
        }
        if (settingsUpdateState != null) {
            setState(
                    settingsUpdateState,
                    formatUpdateStatus(updateState, updateCheckInProgress, updateCheckError),
                    !TextUtils.isEmpty(updateCheckError) ? RED : updateStatusColor(rawUpdateStatus, hasCandidate));
        }
        if (updateAction != null) {
            boolean downloading = "downloading".equals(rawUpdateStatus) || "staging".equals(rawUpdateStatus);
            boolean busy = updateCheckInProgress || downloading;
            updateAction.setEnabled(!busy);
            updateAction.setAlpha(updateAction.isEnabled() ? 1f : 0.45f);
            if (updateCheckInProgress) {
                updateAction.setText("CHECKING FOR UPDATE…");
            } else if (downloading) {
                updateAction.setText("DOWNLOADING UPDATE…");
            } else if (hasCandidate && !TextUtils.isEmpty(candidateVersion)) {
                updateAction.setText("INSTALL " + candidateVersion);
            } else {
                updateAction.setText("CHECK FOR UPDATE");
            }
        }

        if (remoteState != null) {
            if (!relayConfigured) {
                setState(remoteState, "NOT CONFIGURED", AMBER);
                remoteEndpointSummary.setText("Local access still works. A compatible relay is required for cloud agents outside your LAN.");
            } else if (relayConnected) {
                setState(remoteState, "CONNECTED", GREEN);
                remoteEndpointSummary.setText("Remote MCP is ready. This phone maintains the relay connection itself.");
            } else {
                setState(remoteState, relayStatus.toUpperCase(), AMBER);
                remoteEndpointSummary.setText("Relay is configured but not currently connected. Local access remains available.");
            }
            if (relayUrlInput != null && !relayUrlInput.hasFocus() && !TextUtils.equals(relayUrlInput.getText().toString(), relayUrl)) {
                relayUrlInput.setText(relayUrl);
            }
        }

        if (devLocalEndpoint != null) devLocalEndpoint.setText(orDash(localEndpoint));
        if (devRemoteEndpoint != null) devRemoteEndpoint.setText(orDash(remoteEndpoint));
        if (devRelayStatus != null) {
            devRelayStatus.setText(relayStatus.toUpperCase());
            applyTextColor(devRelayStatus, relayConnected ? GREEN : AMBER);
        }
        if (devRelayUrl != null) devRelayUrl.setText(orDash(relayUrl));
        if (devBearer != null) devBearer.setText(maskSecret(token));
        if (devRuntime != null) {
            long calls = prefs.getLong(McpNodeService.KEY_CALL_COUNT, 0L);
            devRuntime.setText((running ? "RUNNING" : "STOPPED") + " · tool calls " + calls + " · app " + version + "/" + versionCode);
            applyTextColor(devRuntime, running ? GREEN : RED);
        }
        if (devRecent != null) devRecent.setText(prefs.getString(McpNodeService.KEY_RECENT, "No tool calls yet"));
    }

    private void setState(TextView view, String value, int color) {
        view.setText(value);
        applyTextColor(view, color);
    }

    private CapabilitySummary capabilitySummary() {
        boolean hyper = McpocketPolicySettings.isHyperModeEnabled(this);
        boolean[] readiness = new boolean[]{
                hasPermission(Manifest.permission.CAMERA),
                hasPermission(Manifest.permission.RECORD_AUDIO),
                hasLocationPermission(),
                hasPermission(Manifest.permission.READ_CONTACTS),
                hasCalendarPermission(),
                McpNotificationListenerService.hasAccess(this),
                hasDeviceAdmin(),
                hyper,
                hyper && McpAccessibilityService.hasAccess(this),
                hyper && ScreenCaptureService.isActive()
        };
        int available = 0;
        for (boolean ready : readiness) {
            if (ready) available++;
        }
        return new CapabilitySummary(available, readiness.length);
    }

    private String formatUpdateStatus(JSONObject state, boolean checking, String transientError) {
        if (checking) return "Checking the official update channel…";
        if (!TextUtils.isEmpty(transientError)) return "Update check failed · " + transientError;

        String status = state.optString("status", "idle");
        String version = state.optString("candidateVersionName", state.optString("latestVersionName", ""));
        if ("downloading".equals(status)) {
            long downloaded = state.optLong("bytesDownloaded", 0L);
            long total = state.optLong("totalBytes", 0L);
            if (total > 0L) {
                long percent = Math.min(100L, downloaded * 100L / total);
                return "Downloading " + percent + "% · " + formatMegabytes(downloaded)
                        + " / " + formatMegabytes(total);
            }
            return "Downloading · " + formatMegabytes(downloaded);
        }
        if ("staging".equals(status) || "verified".equals(status)) {
            return "Download verified · preparing the installer";
        }
        if ("pending_user_action".equals(status)) {
            return TextUtils.isEmpty(version)
                    ? "Update ready · tap Install to continue"
                    : "Version " + version + " is ready to install";
        }
        if ("requires_setup".equals(status)) {
            return "Android permission needed · allow PickPico to install updates";
        }
        if ("failed".equals(status)) {
            String error = state.optString("error", "Unknown update error");
            return "Update failed · " + simplifyUpdateError(error);
        }
        if ("up_to_date".equals(status)) return "PickPico is up to date";
        if ("installed".equals(status)) return "Update installed successfully";
        return "Ready to check the official update channel";
    }

    private int updateStatusColor(String status, boolean hasCandidate) {
        if (updateCheckInProgress || "downloading".equals(status) || "staging".equals(status)) return BLUE;
        if ("failed".equals(status)) return RED;
        if ("requires_setup".equals(status)) return AMBER;
        if (hasCandidate || "installed".equals(status) || "up_to_date".equals(status)) return GREEN;
        return DIM;
    }

    private String simplifyUpdateError(String error) {
        if (error == null) return "Unknown error";
        int separator = error.indexOf(": ");
        return separator > 0 ? error.substring(separator + 2) : error;
    }

    private String formatMegabytes(long bytes) {
        return String.format(Locale.US, "%.1f MB", Math.max(0L, bytes) / (1024d * 1024d));
    }

    private static final class CapabilitySummary {
        final int available;
        final int total;

        CapabilitySummary(int available, int total) {
            this.available = available;
            this.total = total;
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean hasCalendarPermission() {
        return hasPermission(Manifest.permission.READ_CALENDAR)
                && hasPermission(Manifest.permission.WRITE_CALENDAR);
    }

    private boolean hasDeviceAdmin() {
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, McpDeviceAdminReceiver.class);
        return policy != null && policy.isAdminActive(admin);
    }

    private void toggleNodeFromHome() {
        if (McpNodeService.isNodeRunning()) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Stop PickPico?")
                    .setMessage("Agents will not be able to reach this phone until PickPico starts again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Stop", (dialog, which) -> stopNode())
                    .show();
        } else {
            startNode();
        }
    }

    private void startNode() {
        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_NODE_MEDIA);
            return;
        }
        startNodeService();
    }

    private void startNodeService() {
        Intent intent = new Intent(this, McpNodeService.class);
        intent.setAction(McpNodeService.ACTION_START);
        intent.putExtra(McpNodeService.EXTRA_TOKEN, generateToken());
        intent.putExtra(McpNodeService.EXTRA_ENABLE_MEDIA_FGS, true);
        startForegroundService(intent);
        Toast.makeText(this, "Starting PickPico…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshStatus, 400L);
    }

    private void stopNode() {
        Intent intent = new Intent(this, McpNodeService.class);
        intent.setAction(McpNodeService.ACTION_STOP);
        startService(intent);
        handler.postDelayed(this::refreshStatus, 300L);
    }

    private void showApprovalModeDialog() {
        String current = McpocketPolicySettings.approvalMode(this);
        String[] labels = new String[]{"詢問我", "代我核准", "YOLO Mode"};
        String[] values = new String[]{
                McpocketPolicySettings.APPROVAL_ASK,
                McpocketPolicySettings.APPROVAL_AUTO,
                McpocketPolicySettings.APPROVAL_YOLO
        };
        int selected = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) selected = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Approval Mode")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    McpocketPolicySettings.setApprovalMode(this, values[which]);
                    dialog.dismiss();
                    refreshStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveRelayUrl() {
        if (relayUrlInput == null) return;
        String value = relayUrlInput.getText().toString().trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.isEmpty() && !value.startsWith("https://") && !value.startsWith("http://")) {
            Toast.makeText(this, "Relay URL must start with https://", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE)
                .edit()
                .putString(McpNodeService.KEY_RELAY_BASE_URL, value)
                .apply();
        relayUrlInput.setText(value);
        refreshStatus();

        if (McpNodeService.isNodeRunning()) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Relay saved")
                    .setMessage("Restart PickPico now to apply the remote access change?")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Restart", (dialog, which) -> restartNode())
                    .show();
        } else {
            Toast.makeText(this, "Relay saved", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartNode() {
        stopNode();
        handler.postDelayed(this::startNode, 700L);
    }

    private void copyConnection() {
        SharedPreferences prefs = getSharedPreferences(McpNodeService.PREFS, MODE_PRIVATE);
        String remoteEndpoint = prefs.getString(McpNodeService.KEY_REMOTE_ENDPOINT, "");
        String localEndpoint = prefs.getString(McpNodeService.KEY_ENDPOINT, "");
        String endpoint = TextUtils.isEmpty(remoteEndpoint) ? localEndpoint : remoteEndpoint;
        String token = prefs.getString(McpNodeService.KEY_TOKEN, "");
        if (TextUtils.isEmpty(endpoint)) {
            Toast.makeText(this, "Start PickPico first", Toast.LENGTH_SHORT).show();
            return;
        }

        String json;
        if (!TextUtils.isEmpty(remoteEndpoint)) {
            json = "{\n" +
                    "  \"url\": \"" + endpoint + "\",\n" +
                    "  \"authentication\": \"none\"\n" +
                    "}";
        } else {
            if (TextUtils.isEmpty(token)) {
                Toast.makeText(this, "Local bearer is not ready", Toast.LENGTH_SHORT).show();
                return;
            }
            json = "{\n" +
                    "  \"url\": \"" + endpoint + "\",\n" +
                    "  \"headers\": {\n" +
                    "    \"Authorization\": \"Bearer " + token + "\"\n" +
                    "  }\n" +
                    "}";
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PickPico connection", json));
            Toast.makeText(this, "Connection copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestDeviceAdmin() {
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, McpDeviceAdminReceiver.class);
        if (policy != null && policy.isAdminActive(admin)) {
            refreshStatus();
            return;
        }
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Allow PickPico agents to lock this phone when you permit the capability.");
        startActivity(intent);
    }

    private void disableDeviceAdmin() {
        DevicePolicyManager policy = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, McpDeviceAdminReceiver.class);
        if (policy != null && policy.isAdminActive(admin)) {
            policy.removeActiveAdmin(admin);
        }
        handler.postDelayed(this::refreshStatus, 300L);
    }

    private void showAccessibilitySetupDialog() {
        boolean enabled = McpAccessibilityService.hasAccess(this);
        if (enabled) {
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Accessibility UI Control")
                    .setMessage("Android controls this special access. Open Accessibility settings to disable it.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open settings", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Enable UI Control")
                .setMessage("Sideloaded builds may require two Android security steps. First open App info and choose Allow restricted settings if shown. Then enable PickPico in Accessibility settings.")
                .setNeutralButton("App info", (dialog, which) -> openAppDetailsSettings())
                .setPositiveButton("Accessibility", (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleScreenCapture() {
        if (ScreenCaptureService.isActive()) {
            Intent stop = new Intent(this, ScreenCaptureService.class)
                    .setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
            handler.postDelayed(this::refreshStatus, 250L);
            return;
        }
        if (!McpocketPolicySettings.isHyperModeEnabled(this)) {
            Toast.makeText(this, "Turn on Hyper Mode first", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Screen capture is unavailable on this device", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not authorized", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
        startForegroundService(serviceIntent);
        handler.postDelayed(this::refreshStatus, 500L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NODE_MEDIA && !McpNodeService.isNodeRunning()) {
            startNodeService();
        }
        handler.postDelayed(this::refreshStatus, 250L);
    }

    private void openAppPermissionSettings(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        openAppDetailsSettings();
    }

    private void openAppDetailsSettings() {
        startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void checkOrInstallUpdate() {
        if (updateCheckInProgress) {
            return;
        }

        if (SelfUpdateManager.hasInstallableCandidate(this)) {
            updateCheckError = null;
            try {
                JSONObject state = SelfUpdateManager.installStagedFromForeground(this);
                Toast.makeText(this, "PickPico update: " + state.optString("status", "staging"), Toast.LENGTH_SHORT).show();
            } catch (RuntimeException error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
            handler.postDelayed(this::refreshStatus, 250L);
            return;
        }

        updateCheckInProgress = true;
        updateCheckError = null;
        refreshStatus();
        Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject latest = SelfUpdateManager.checkLatest(appContext, new JSONObject(), 0L);
                handler.post(() -> finishUpdateCheck(latest, null));
            } catch (RuntimeException error) {
                handler.post(() -> finishUpdateCheck(null, error));
            }
        }, "pickpico-manual-update-check").start();
    }

    private void finishUpdateCheck(JSONObject latest, RuntimeException error) {
        updateCheckInProgress = false;
        if (error != null) {
            updateCheckError = simplifyUpdateError(error.getMessage());
            Toast.makeText(this, "Update check failed: " + updateCheckError, Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        updateCheckError = null;

        try {
            boolean updateAvailable = latest.optBoolean("updateAvailable", false);
            if (!updateAvailable) {
                Toast.makeText(this, "PickPico is already up to date", Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }
            JSONObject state = SelfUpdateManager.startResolvedLatest(this, latest, 0L);
            Toast.makeText(this, "PickPico update: " + state.optString("status", "staging"), Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::refreshStatus, 250L);
        } catch (RuntimeException startError) {
            Toast.makeText(this, startError.getMessage(), Toast.LENGTH_LONG).show();
            refreshStatus();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
        }
    }

    private String maskSecret(String value) {
        if (TextUtils.isEmpty(value)) return "—";
        if (value.length() <= 8) return "••••••••";
        return value.substring(0, 4) + "••••••••••••" + value.substring(value.length() - 4);
    }

    private String orDash(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    private String generateToken() {
        byte[] data = new byte[24];
        new SecureRandom().nextBytes(data);
        return android.util.Base64.encodeToString(data,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface SwitchListener {
        void onChanged(boolean checked);
    }

    private static final class ThemedCardRef {
        final LinearLayout card;
        final boolean accented;

        ThemedCardRef(LinearLayout card, boolean accented) {
            this.card = card;
            this.accented = accented;
        }
    }

    /** Dark translucent panel with a subtle top highlight and green-tinted ambient layer. */
    private static final class GlassPanelDrawable extends Drawable {
        private final float radius;
        private final boolean accented;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassPanelDrawable(float radius, boolean accented) {
            this.radius = radius;
            this.accented = accented;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1f);
            stroke.setColor(Color.argb(48, 255, 255, 255));
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeWidth(1f);
            highlight.setColor(accented
                    ? Color.argb(105, 83, 230, 143)
                    : Color.argb(70, 255, 255, 255));
        }

        @Override
        public void draw(Canvas canvas) {
            RectF rect = new RectF(getBounds());
            fill.setShader(new LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    accented
                            ? new int[]{Color.argb(205, 11, 32, 24), Color.argb(174, 10, 15, 18)}
                            : new int[]{Color.argb(205, 24, 28, 32), Color.argb(170, 10, 13, 16)},
                    null,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, fill);

            RectF inset = new RectF(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f);
            canvas.drawRoundRect(inset, radius, radius, stroke);

            RectF topEdge = new RectF(rect.left + 1.5f, rect.top + 1.5f, rect.right - 1.5f, rect.bottom - 1.5f);
            canvas.save();
            canvas.clipRect(rect.left, rect.top, rect.right, rect.top + Math.max(12f, rect.height() * 0.34f));
            canvas.drawRoundRect(topEdge, radius, radius, highlight);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Soft ambient gradients behind translucent cards create the visible glass depth. */
    private static final class AmbientBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        AmbientBackgroundView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(BG);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            paint.setShader(new RadialGradient(
                    w * 0.17f,
                    h * 0.08f,
                    w * 0.88f,
                    new int[]{Color.argb(55, 34, 188, 105), Color.argb(12, 16, 90, 65), Color.TRANSPARENT},
                    new float[]{0f, 0.42f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            paint.setShader(new RadialGradient(
                    w * 0.92f,
                    h * 0.70f,
                    w * 0.72f,
                    new int[]{Color.argb(27, 46, 128, 174), Color.argb(8, 26, 70, 94), Color.TRANSPARENT},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    /** Bottom navigation gets a thinner, flatter glass treatment than content cards. */
    private static final class NavBarDrawable extends Drawable {
        private final float radius;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavBarDrawable(float radius) {
            this.radius = radius;
            line.setColor(Color.argb(44, 255, 255, 255));
            line.setStrokeWidth(1f);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF rect = new RectF(getBounds());
            fill.setShader(new LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    new int[]{Color.argb(228, 15, 19, 22), Color.argb(244, 6, 8, 10)},
                    null,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, fill);
            canvas.drawLine(rect.left, rect.top + 0.5f, rect.right, rect.top + 0.5f, line);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
