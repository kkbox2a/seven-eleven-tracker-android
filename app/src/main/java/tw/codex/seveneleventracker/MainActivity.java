package tw.codex.seveneleventracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String QUERY_URL = "https://eservice.7-11.com.tw/e-tracking/search.aspx";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/kkbox2a/seven-eleven-tracker-android/releases/latest";
    private static final String RELEASES_URL = "https://github.com/kkbox2a/seven-eleven-tracker-android/releases";
    private static final String REPOSITORY_URL = "https://github.com/kkbox2a/seven-eleven-tracker-android";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final int WRITE_STORAGE_REQUEST = 4201;
    private static final Pattern UPDATE_APK_PATTERN = Pattern.compile(
            "^SevenElevenTracker-v([0-9]+(?:\\.[0-9]+){1,2})\\.apk$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING_PATTERN = Pattern.compile("^[A-Za-z0-9]{8,11}$");
    private static final Pattern FOUR_DIGITS = Pattern.compile("^\\d{4}$");
    private static final int GREEN = Color.rgb(0, 143, 76);
    private static final int ORANGE = Color.rgb(255, 103, 18);

    private enum Stage { IDLE, LOADING_FORM, RECOGNIZING, SUBMITTING, EXTRACTING }

    private EditText trackingInput;
    private LinearLayout startButton;
    private LinearLayout shareButton;
    private TextView progressText;
    private LinearLayout resultsContainer;
    private WebView webView;
    private ScrollView rootScrollView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> queue = new ArrayList<>();
    private final List<TrackingResult> results = new ArrayList<>();
    private TextRecognizer recognizer;
    private volatile boolean downloadCancelled = false;
    private Uri pendingInstallUri;
    private UpdatePackage pendingLegacyDownload;
    private int currentIndex = 0;
    private int ocrAttempt = 0;
    private int submitAttempt = 0;
    private int generation = 0;
    private boolean running = false;
    private boolean manualAttempt = false;
    private Stage stage = Stage.IDLE;
    private Bitmap currentCaptcha;
    private Runnable timeoutTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        buildUi();
        configureSystemInsets();
        configureWebView();
        new Thread(this::cleanupOldUpdatePackages).start();
        handler.postDelayed(() -> checkForUpdates(false), 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallUri != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls())) {
            Uri apkUri = pendingInstallUri;
            launchPackageInstaller(apkUri);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private LinearLayout centeredIconButton(String text, int iconResource, int backgroundResource,
                                            int textColor, int textSizeSp, int iconSizeDp) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(text);
        button.setBackgroundResource(backgroundResource);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(icon, new LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)));

        TextView caption = label(text, textSizeSp, textColor);
        caption.setTypeface(null, android.graphics.Typeface.BOLD);
        caption.setGravity(Gravity.CENTER);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.setMargins(dp(7), 0, 0, 0);
        button.addView(caption, captionParams);
        return button;
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(24));
        page.setBackgroundColor(Color.rgb(247, 249, 248));

        TextView title = label("7-ELEVEN 貨態查詢", 25, GREEN);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = label("多筆貼入、逐筆查詢、OCR 驗證碼與貨態結果", 14, Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, 0, 0, dp(14));
        page.addView(subtitle);

        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setGravity(Gravity.END);
        Button aboutButton = new Button(this);
        aboutButton.setText("關於 App");
        aboutButton.setTextColor(GREEN);
        aboutButton.setTextSize(13);
        aboutButton.setAllCaps(false);
        aboutButton.setMinWidth(0);
        aboutButton.setMinHeight(0);
        aboutButton.setPadding(dp(14), 0, dp(14), 0);
        aboutButton.setBackgroundResource(R.drawable.update_button_background);
        aboutButton.setStateListAnimator(null);
        aboutButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_info, 0, 0, 0);
        aboutButton.setCompoundDrawablePadding(dp(6));
        aboutButton.setOnClickListener(v -> showAboutDialog());
        updateRow.addView(aboutButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

        Button updateButton = new Button(this);
        updateButton.setText("檢查更新  v" + BuildConfig.VERSION_NAME);
        updateButton.setTextColor(GREEN);
        updateButton.setTextSize(13);
        updateButton.setAllCaps(false);
        updateButton.setMinWidth(0);
        updateButton.setMinHeight(0);
        updateButton.setPadding(dp(14), 0, dp(14), 0);
        updateButton.setBackgroundResource(R.drawable.update_button_background);
        updateButton.setStateListAnimator(null);
        updateButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_update, 0, 0, 0);
        updateButton.setCompoundDrawablePadding(dp(6));
        updateButton.setOnClickListener(v -> checkForUpdates(true));
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        updateParams.setMargins(dp(8), 0, 0, 0);
        updateRow.addView(updateButton, updateParams);
        LinearLayout.LayoutParams updateRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updateRowParams.setMargins(0, 0, 0, dp(8));
        page.addView(updateRow, updateRowParams);

        LinearLayout inputHeader = new LinearLayout(this);
        inputHeader.setOrientation(LinearLayout.HORIZONTAL);
        inputHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView inputLabel = label("物流單號（每行一筆）", 16, Color.BLACK);
        inputHeader.addView(inputLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pasteButton = centeredIconButton(
                "貼上", R.drawable.ic_paste, R.drawable.update_button_background,
                GREEN, 13, 18);
        pasteButton.setPadding(dp(11), 0, dp(11), 0);
        pasteButton.setOnClickListener(v -> pasteTrackingNumbers());
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        pasteParams.setMargins(dp(6), dp(2), 0, dp(4));
        inputHeader.addView(pasteButton, pasteParams);

        LinearLayout exampleButton = centeredIconButton(
                "圖例展示", R.drawable.ic_example, R.drawable.example_button_background,
                Color.WHITE, 13, 18);
        exampleButton.setPadding(dp(11), 0, dp(11), 0);
        exampleButton.setOnClickListener(v -> showTrackingExample());
        LinearLayout.LayoutParams exampleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        exampleParams.setMargins(dp(8), dp(2), 0, dp(4));
        inputHeader.addView(exampleButton, exampleParams);
        page.addView(inputHeader, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        trackingInput = new EditText(this);
        trackingInput.setHint("請輸入寄件8碼或取件11碼，每行一筆");
        trackingInput.setHintTextColor(Color.GRAY);
        trackingInput.setTextSize(17);
        trackingInput.setGravity(Gravity.TOP | Gravity.START);
        trackingInput.setMinLines(4);
        trackingInput.setMaxLines(8);
        trackingInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        trackingInput.setBackgroundResource(R.drawable.tracking_input_background);
        trackingInput.setElevation(dp(3));
        trackingInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        page.addView(trackingInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        startButton = centeredIconButton(
                "開始逐筆查詢", R.drawable.ic_search, R.drawable.primary_button_background,
                Color.WHITE, 15, 20);
        startButton.setOnClickListener(v -> startQuery());
        buttonRow.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        shareButton = centeredIconButton(
                "分享結果", R.drawable.ic_share, R.drawable.share_button_background,
                Color.WHITE, 15, 20);
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(v -> shareResults());
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        shareParams.setMargins(dp(8), 0, 0, 0);
        buttonRow.addView(shareButton, shareParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(6), 0, dp(6));
        page.addView(buttonRow, rowParams);

        LinearLayout clearButton = centeredIconButton(
                "清除並重新查詢", R.drawable.ic_clear, R.drawable.update_button_background,
                GREEN, 14, 19);
        clearButton.setOnClickListener(v -> clearQuerySession(true));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        clearParams.setMargins(0, 0, 0, dp(8));
        page.addView(clearButton, clearParams);

        progressText = label("準備就緒", 15, Color.DKGRAY);
        progressText.setPadding(dp(8), dp(8), dp(8), dp(8));
        progressText.setBackgroundColor(Color.rgb(235, 239, 237));
        page.addView(progressText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setVisibility(View.GONE);
        page.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));

        TextView resultTitle = label("查詢結果", 18, Color.BLACK);
        resultTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        resultTitle.setPadding(0, dp(12), 0, dp(6));
        page.addView(resultTitle);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(resultsContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rootScrollView = new ScrollView(this);
        rootScrollView.setFillViewport(true);
        rootScrollView.setClipToPadding(true);
        rootScrollView.setClipChildren(true);
        rootScrollView.setBackgroundColor(Color.rgb(247, 249, 248));
        rootScrollView.addView(page);
        setContentView(rootScrollView);
    }

    private void pasteTrackingNumbers() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "剪貼簿沒有可貼上的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "剪貼簿沒有可貼上的文字", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence clipboardText = clip.getItemAt(0).coerceToText(this);
        if (clipboardText == null) {
            Toast.makeText(this, "剪貼簿內容不是文字", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> pastedLines = new ArrayList<>();
        for (String line : clipboardText.toString().replace('\r', '\n').split("\\n+")) {
            String value = line.trim();
            if (!value.isEmpty()) pastedLines.add(value);
        }
        if (pastedLines.isEmpty()) {
            Toast.makeText(this, "剪貼簿沒有可貼上的單號", Toast.LENGTH_SHORT).show();
            return;
        }

        String existing = trackingInput.getText().toString().trim();
        String pasted = android.text.TextUtils.join("\n", pastedLines);
        String combined = existing.isEmpty() ? pasted : existing + "\n" + pasted;
        trackingInput.setText(combined);
        trackingInput.setSelection(combined.length());
        rootScrollView.post(() -> rootScrollView.smoothScrollTo(0, trackingInput.getTop()));
        Toast.makeText(this, "已貼上 " + pastedLines.size() + " 筆內容", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    private void configureSystemInsets() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);

        rootScrollView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = windowInsets.getSystemWindowInsetTop();
                bottom = windowInsets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && windowInsets.getDisplayCutout() != null) {
                    top = Math.max(top, windowInsets.getDisplayCutout().getSafeInsetTop());
                }
            }
            view.setPadding(0, top, 0, bottom);
            return windowInsets;
        });
        rootScrollView.requestApplyInsets();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new WebBridge(), "AndroidTracker");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                return host == null || !host.equalsIgnoreCase("eservice.7-11.com.tw");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!running) return;
                cancelTimeout();
                inspectLoadedPage();
            }
        });
    }

    private void showTrackingExample() {
        ImageView exampleImage = new ImageView(this);
        exampleImage.setImageResource(R.drawable.tracking_number_example);
        exampleImage.setAdjustViewBounds(true);
        exampleImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        exampleImage.setContentDescription("寄件與取件物流單號圖例");

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(exampleImage, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("貨態查詢圖例")
                .setView(scrollView)
                .setPositiveButton("關閉", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.75f);
            scrollView.getLayoutParams().height = maxHeight;
            scrollView.requestLayout();
        });
        dialog.show();
    }

    private void showAboutDialog() {
        LinearLayout aboutContent = new LinearLayout(this);
        aboutContent.setOrientation(LinearLayout.VERTICAL);
        aboutContent.setPadding(dp(22), dp(8), dp(22), dp(4));

        TextView information = new TextView(this);
        information.setTextSize(15);
        information.setTextColor(Color.DKGRAY);
        information.setText("版本：v" + BuildConfig.VERSION_NAME
                + "\n\n提供多筆物流單號查詢、OCR 驗證碼、查詢結果分享與 App 內更新下載。"
                + "\n\n資料與隱私：查詢結果不會儲存為 App 紀錄檔。更新 APK 只會暫存於 App 專屬下載目錄。"
                + "\n\n資料來源：7-ELEVEN 貨態查詢網站。本 App 為非官方工具，與統一超商無隸屬或合作關係。"
                + "\n\n開發與原始碼：GitHub / kkbox2a"
                + "\n\nCopyright © 2026 kkbox2a. All rights reserved.");
        aboutContent.addView(information, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout githubRow = new LinearLayout(this);
        githubRow.setGravity(Gravity.CENTER_VERTICAL);
        githubRow.setPadding(0, dp(14), 0, dp(6));
        TextView githubLabel = label("查看 GitHub 專案", 15, Color.DKGRAY);
        githubRow.addView(githubLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton githubButton = new ImageButton(this);
        githubButton.setImageResource(R.drawable.ic_github);
        githubButton.setContentDescription("開啟 GitHub 專案");
        githubButton.setPadding(dp(9), dp(9), dp(9), dp(9));
        githubButton.setBackgroundResource(R.drawable.update_button_background);
        githubButton.setOnClickListener(v -> openReleasePage(REPOSITORY_URL));
        githubRow.addView(githubButton, new LinearLayout.LayoutParams(dp(46), dp(46)));
        aboutContent.addView(githubRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("關於 7-ELEVEN 貨態查詢")
                .setView(aboutContent)
                .setPositiveButton("關閉", null)
                .show();
    }

    private void checkForUpdates(boolean userInitiated) {
        if (userInitiated) Toast.makeText(this, "正在檢查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "SevenElevenTracker-Android");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("GitHub 回應代碼 " + connection.getResponseCode());
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }

                JSONObject release = new JSONObject(response.toString());
                String latestVersion = normalizeVersion(release.optString("tag_name"));
                String releaseUrl = release.optString("html_url", RELEASES_URL);
                String releaseNotes = release.optString("body", "").trim();
                JSONObject apkAsset = findApkAsset(release.optJSONArray("assets"));
                String apkName = apkAsset.optString("name", "SevenElevenTracker-v" + latestVersion + ".apk");
                String apkUrl = apkAsset.optString("browser_download_url");
                long apkSize = apkAsset.optLong("size", 0L);
                String apkDigest = apkAsset.optString("digest", "");
                if (apkUrl.isEmpty()) throw new IllegalStateException("Release 中找不到 APK 更新包");
                boolean hasUpdate = compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0;
                runOnUiThread(() -> {
                    if (hasUpdate) showUpdateDialog(latestVersion, releaseUrl, releaseNotes,
                            apkName, apkUrl, apkSize, apkDigest);
                    else if (userInitiated) Toast.makeText(this,
                            "目前已是最新版 v" + BuildConfig.VERSION_NAME, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                if (userInitiated) runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("無法檢查更新")
                        .setMessage("請確認網路連線後再試一次。\n\n" + error.getMessage())
                        .setPositiveButton("前往 Release 頁面", (dialog, which) -> openReleasePage(RELEASES_URL))
                        .setNegativeButton("關閉", null)
                        .show());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WRITE_STORAGE_REQUEST) return;
        UpdatePackage pending = pendingLegacyDownload;
        pendingLegacyDownload = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pending != null) {
            downloadAndInstallApk(pending.name, pending.url, pending.size, pending.digest);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("需要儲存權限")
                    .setMessage("Android 9 以下需要儲存權限，才能將更新 APK 放入內部儲存空間的 Download 資料夾。")
                    .setPositiveButton("關閉", null)
                    .show();
        }
    }

    private Uri publishToPublicDownloads(File source, String displayName, String sourceUrl) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            deletePublicDownloadByName(displayName);
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            values.put(MediaStore.Downloads.DOWNLOAD_URI, sourceUrl);
            Uri itemUri = resolver.insert(collection, values);
            if (itemUri == null) throw new IllegalStateException("無法在 Download 資料夾建立更新包");
            try (OutputStream output = resolver.openOutputStream(itemUri, "w")) {
                if (output == null) throw new IllegalStateException("無法寫入 Download 更新包");
                copyFile(source, output);
            } catch (Exception error) {
                resolver.delete(itemUri, null, null);
                throw error;
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(itemUri, ready, null, null);
            return itemUri;
        }

        @SuppressWarnings("deprecation")
        File downloadDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDirectory.exists() && !downloadDirectory.mkdirs()) {
            throw new IllegalStateException("無法建立 Download 資料夾");
        }
        File destination = new File(downloadDirectory, displayName);
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            copyFile(source, output);
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", destination);
    }

    private void copyFile(File source, OutputStream output) throws Exception {
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
        }
    }

    private void deletePublicDownloadByName(String displayName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            getContentResolver().delete(collection,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{displayName, Environment.DIRECTORY_DOWNLOADS + "/"});
        } catch (Exception ignored) {
        }
    }

    private void cleanupOldUpdatePackages() {
        cleanupTemporaryUpdates();
        cleanupLegacyAppSpecificUpdates();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cleanupMediaStoreUpdates();
        else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            cleanupLegacyDownloads();
        }
    }

    private void cleanupLegacyAppSpecificUpdates() {
        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) return;
        File directory = new File(base, "updates");
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && shouldDeleteUpdate(file.getName())) file.delete();
        }
    }

    private void cleanupTemporaryUpdates() {
        File directory = new File(getCacheDir(), "updates");
        File[] files = directory.listFiles();
        if (files == null) return;
        long staleBefore = System.currentTimeMillis() - 60L * 60L * 1000L;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < staleBefore) file.delete();
        }
    }

    private void cleanupMediaStoreUpdates() {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        try (Cursor cursor = getContentResolver().query(collection, projection, selection,
                new String[]{"SevenElevenTracker-v%.apk"}, null)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                if (!shouldDeleteUpdate(name)) continue;
                Uri itemUri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn));
                try {
                    getContentResolver().delete(itemUri, null, null);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void cleanupLegacyDownloads() {
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && shouldDeleteUpdate(file.getName())) file.delete();
        }
    }

    private boolean shouldDeleteUpdate(String fileName) {
        if (fileName == null) return false;
        Matcher matcher = UPDATE_APK_PATTERN.matcher(fileName);
        return matcher.matches() && compareVersions(matcher.group(1), BuildConfig.VERSION_NAME) <= 0;
    }

    private JSONObject findApkAsset(JSONArray assets) throws JSONException {
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) return asset;
            }
        }
        throw new JSONException("Release 中沒有 APK 更新包");
    }

    private void showUpdateDialog(String latestVersion, String releaseUrl, String releaseNotes,
                                  String apkName, String apkUrl, long apkSize, String apkDigest) {
        String message = "目前版本：v" + BuildConfig.VERSION_NAME + "\n最新版本：v" + latestVersion;
        message += "\n更新包：" + apkName + "\n大小：" + formatBytes(apkSize);
        String formattedNotes = formatReleaseNotesForDialog(releaseNotes);
        if (!formattedNotes.isEmpty()) message += "\n\n更新內容\n" + formattedNotes;
        new AlertDialog.Builder(this)
                .setTitle("發現新版")
                .setMessage(message)
                .setPositiveButton("下載並安裝", (dialog, which) ->
                        downloadAndInstallApk(apkName, apkUrl, apkSize, apkDigest))
                .setNeutralButton("Release 頁面", (dialog, which) -> openReleasePage(releaseUrl))
                .setNegativeButton("稍後", null)
                .show();
    }

    private String formatReleaseNotesForDialog(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean previousBlank = true;
        for (String sourceLine : lines) {
            String line = sourceLine.trim();
            if (line.isEmpty()) {
                if (!previousBlank && output.length() > 0) output.append('\n');
                previousBlank = true;
                continue;
            }

            boolean heading = line.matches("^#{1,6}\\s+.*");
            if (heading) line = line.replaceFirst("^#{1,6}\\s+", "");
            if (line.equals("更新內容") || line.equalsIgnoreCase("what's changed")) continue;
            line = line.replaceFirst("^[-*+]\\s+", "• ");
            line = line.replaceFirst("^>\\s*", "");
            line = line.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");
            line = line.replace("**", "").replace("__", "").replace("`", "");
            line = line.replaceAll("<[^>]+>", "").trim();
            if (line.isEmpty()) continue;

            if (heading && output.length() > 0 && !previousBlank) output.append('\n');
            output.append(line).append('\n');
            previousBlank = false;
        }
        return output.toString().trim();
    }

    private void downloadAndInstallApk(String apkName, String apkUrl, long expectedSize, String expectedDigest) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingLegacyDownload = new UpdatePackage(apkName, apkUrl, expectedSize, expectedDigest);
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_STORAGE_REQUEST);
            return;
        }
        String safeName = apkName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safeName.toLowerCase(Locale.ROOT).endsWith(".apk")) safeName += ".apk";

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), dp(4));
        TextView nameView = label("更新包：" + apkName, 15, Color.DKGRAY);
        TextView sizeView = label("大小：" + formatBytes(expectedSize), 14, Color.DKGRAY);
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        TextView progressView = label("準備下載…", 14, Color.DKGRAY);
        TextView speedView = label("網路速度：--", 14, GREEN);
        content.addView(nameView);
        content.addView(sizeView);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        barParams.setMargins(0, dp(12), 0, dp(8));
        content.addView(progressBar, barParams);
        content.addView(progressView);
        content.addView(speedView);

        downloadCancelled = false;
        AlertDialog downloadDialog = new AlertDialog.Builder(this)
                .setTitle("下載最新版 APK")
                .setView(content)
                .setNegativeButton("取消", (dialog, which) -> downloadCancelled = true)
                .setCancelable(false)
                .create();
        downloadDialog.show();

        final String downloadName = safeName;
        new Thread(() -> {
            HttpURLConnection connection = null;
            File apkFile = null;
            try {
                File updateDirectory = new File(getCacheDir(), "updates");
                if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                    throw new IllegalStateException("無法建立更新下載目錄");
                }
                apkFile = new File(updateDirectory, downloadName + ".part.apk");

                connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("Accept", APK_MIME_TYPE);
                connection.setRequestProperty("User-Agent", "SevenElevenTracker-Android");
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("下載伺服器回應代碼 " + responseCode);
                }

                long totalSize = expectedSize > 0 ? expectedSize : connection.getContentLengthLong();
                long downloaded = 0L;
                long lastBytes = 0L;
                long lastTime = android.os.SystemClock.elapsedRealtime();
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(apkFile, false)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (downloadCancelled) throw new DownloadCancelledException();
                        output.write(buffer, 0, count);
                        downloaded += count;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastTime >= 500L) {
                            double seconds = (now - lastTime) / 1000.0;
                            double bytesPerSecond = (downloaded - lastBytes) / seconds;
                            long currentBytes = downloaded;
                            int percent = totalSize > 0 ? (int) Math.min(100, currentBytes * 100 / totalSize) : 0;
                            String progressText = totalSize > 0
                                    ? percent + "%　" + formatBytes(currentBytes) + " / " + formatBytes(totalSize)
                                    : formatBytes(currentBytes);
                            runOnUiThread(() -> {
                                if (!downloadCancelled) {
                                    progressBar.setProgress(percent);
                                    progressView.setText(progressText);
                                    speedView.setText("網路速度：" + formatBytes((long) bytesPerSecond) + "/s");
                                }
                            });
                            lastTime = now;
                            lastBytes = downloaded;
                        }
                    }
                    output.flush();
                }

                if (expectedSize > 0 && apkFile.length() != expectedSize) {
                    throw new IllegalStateException("更新包大小不符，請重新下載");
                }
                verifyDownloadedApk(apkFile, expectedDigest);
                Uri completedUri = publishToPublicDownloads(apkFile, downloadName, apkUrl);
                apkFile.delete();
                runOnUiThread(() -> {
                    downloadDialog.dismiss();
                    Toast.makeText(this, "已儲存至 Download，準備安裝", Toast.LENGTH_SHORT).show();
                    requestApkInstallation(completedUri);
                });
            } catch (DownloadCancelledException ignored) {
                if (apkFile != null && apkFile.exists()) apkFile.delete();
                runOnUiThread(() -> Toast.makeText(this, "已取消更新下載", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                if (apkFile != null && apkFile.exists()) apkFile.delete();
                runOnUiThread(() -> {
                    downloadDialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("更新下載失敗")
                            .setMessage(error.getMessage())
                            .setPositiveButton("前往 Release 頁面", (dialog, which) -> openReleasePage(RELEASES_URL))
                            .setNegativeButton("關閉", null)
                            .show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void verifyDownloadedApk(File apkFile, String expectedDigest) throws Exception {
        if (expectedDigest != null && expectedDigest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            String expected = expectedDigest.substring("sha256:".length()).trim();
            String actual = sha256(apkFile);
            if (!expected.equalsIgnoreCase(actual)) throw new IllegalStateException("更新包 SHA-256 驗證失敗");
        }
        PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        if (packageInfo == null || !getPackageName().equals(packageInfo.packageName)) {
            throw new IllegalStateException("更新包的 App 套件名稱不符");
        }
        PackageInfo installedInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
        long installedVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? installedInfo.getLongVersionCode() : installedInfo.versionCode;
        if (archiveVersion <= installedVersion) {
            throw new IllegalStateException("更新包版本不高於目前安裝版本");
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private void requestApkInstallation(Uri apkUri) {
        pendingInstallUri = apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("允許安裝更新")
                    .setMessage("Android 需要你允許此 App 安裝下載的更新。開啟設定後，請啟用「允許來自此來源」。")
                    .setPositiveButton("前往設定", (dialog, which) -> {
                        try {
                            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(settingsIntent);
                        } catch (Exception error) {
                            openReleasePage(RELEASES_URL);
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> pendingInstallUri = null)
                    .show();
            return;
        }
        launchPackageInstaller(apkUri);
    }

    private void launchPackageInstaller(Uri contentUri) {
        pendingInstallUri = null;
        try {
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(contentUri, APK_MIME_TYPE);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(installIntent);
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle("無法啟動安裝")
                    .setMessage(error.getMessage())
                    .setPositiveButton("前往 Release 頁面", (dialog, which) -> openReleasePage(RELEASES_URL))
                    .setNegativeButton("關閉", null)
                    .show();
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static class DownloadCancelledException extends Exception {
    }

    private static class UpdatePackage {
        final String name;
        final String url;
        final long size;
        final String digest;

        UpdatePackage(String name, String url, long size, String digest) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.digest = digest;
        }
    }

    private void openReleasePage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Toast.makeText(this, "無法開啟下載頁面", Toast.LENGTH_LONG).show();
        }
    }

    private String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        return normalized;
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? versionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? versionPart(rightParts[i]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private int versionPart(String value) {
        Matcher matcher = Pattern.compile("^(\\d+)").matcher(value);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void startQuery() {
        if (running) return;
        hideKeyboard();
        String[] lines = trackingInput.getText().toString().split("\\r?\\n");
        Set<String> seen = new HashSet<>();
        queue.clear();
        for (String line : lines) {
            String value = line.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty() || seen.contains(value)) continue;
            if (!TRACKING_PATTERN.matcher(value).matches()) {
                Toast.makeText(this, "單號格式不符：" + value, Toast.LENGTH_LONG).show();
                return;
            }
            seen.add(value);
            queue.add(value);
        }
        if (queue.isEmpty()) {
            Toast.makeText(this, "請至少輸入一筆物流單號", Toast.LENGTH_SHORT).show();
            return;
        }

        results.clear();
        resultsContainer.removeAllViews();
        shareButton.setEnabled(false);
        currentIndex = 0;
        running = true;
        startButton.setEnabled(false);
        beginCurrentTracking();
    }

    private void hideKeyboard() {
        View focusedView = getCurrentFocus();
        if (focusedView == null) return;
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        }
        focusedView.clearFocus();
    }

    private String currentTracking() {
        return queue.get(currentIndex);
    }

    private void beginCurrentTracking() {
        ocrAttempt = 0;
        submitAttempt = 0;
        manualAttempt = false;
        progress("[" + (currentIndex + 1) + "/" + queue.size() + "] 準備查詢 " + currentTracking());
        loadFreshForm(false);
    }

    private void loadFreshForm(boolean manual) {
        manualAttempt = manual;
        if (!manual) ocrAttempt++;
        generation++;
        stage = Stage.LOADING_FORM;
        progress("[" + (currentIndex + 1) + "/" + queue.size() + "] " + currentTracking()
                + (manual ? "：等待手動驗證碼" : "：OCR 第 " + ocrAttempt + "/5 次"));
        webView.loadUrl(QUERY_URL);
        scheduleTimeout("查詢頁載入逾時");
    }

    private void inspectLoadedPage() {
        final int token = generation;
        String script = "(function(){"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "if(/查無該取貨\\s*[\\/／]\\s*繳費編號資料/.test(body)){AndroidTracker.onPageKind('no_data'," + token + ");return;}"
                + "var q=document.getElementById('query_no');"
                + "if(q){AndroidTracker.onPageKind('result'," + token + ");return;}"
                + "var input=document.getElementById('txtProductNum');"
                + "if(input){AndroidTracker.onPageKind('form'," + token + ");return;}"
                + "AndroidTracker.onPageKind('unknown'," + token + ");"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private void handlePageKind(String kind, int token) {
        if (!running || token != generation) return;
        if ("no_data".equals(kind)) {
            failCurrent("查無該取貨／繳費編號資料");
            return;
        }
        if ("result".equals(kind)) {
            stage = Stage.EXTRACTING;
            extractResult(token);
            return;
        }
        if ("form".equals(kind)) {
            if (stage == Stage.SUBMITTING) {
                if (manualAttempt) {
                    failCurrent("手動驗證碼未通過或網站查無資料");
                } else if (ocrAttempt < 5) {
                    loadFreshForm(false);
                } else {
                    loadFreshForm(true);
                }
            } else {
                captureCaptcha(token);
            }
            return;
        }
        failCurrent("網站版面無法辨識，可能已改版");
    }

    private void captureCaptcha(int token) {
        stage = Stage.RECOGNIZING;
        String order = JSONObject.quote(currentTracking());
        String script = "(function(){"
                + "var order=document.getElementById('txtProductNum');if(order)order.value=" + order + ";"
                + "var img=document.getElementById('ImgVCode');"
                + "function grab(){try{"
                + "if(!img||!img.complete||!img.naturalWidth){setTimeout(grab,200);return;}"
                + "var c=document.createElement('canvas');c.width=img.naturalWidth;c.height=img.naturalHeight;"
                + "c.getContext('2d').drawImage(img,0,0);"
                + "AndroidTracker.onCaptcha(c.toDataURL('image/png').split(',')[1]," + token + ");"
                + "}catch(e){AndroidTracker.onJsError(String(e)," + token + ");}}grab();"
                + "})();";
        webView.evaluateJavascript(script, null);
        scheduleTimeout("驗證碼圖片讀取逾時");
    }

    private void recognizeCaptcha(Bitmap bitmap, int token) {
        currentCaptcha = bitmap;
        if (manualAttempt) {
            showManualCaptcha(bitmap, token);
            return;
        }
        List<Bitmap> variants = createOcrVariants(bitmap);
        recognizeVariant(variants, 0, token);
    }

    private List<Bitmap> createOcrVariants(Bitmap source) {
        List<Bitmap> variants = new ArrayList<>();
        variants.add(source);
        Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(320, source.getWidth() * 4), Math.max(120, source.getHeight() * 4), true);
        variants.add(scaled);
        int[] thresholds = {110, 145, 180, 215};
        for (int threshold : thresholds) variants.add(thresholdBitmap(scaled, threshold));
        return variants;
    }

    private Bitmap thresholdBitmap(Bitmap source, int threshold) {
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        int[] row = new int[source.getWidth()];
        for (int y = 0; y < source.getHeight(); y++) {
            source.getPixels(row, 0, source.getWidth(), 0, y, source.getWidth(), 1);
            for (int x = 0; x < row.length; x++) {
                int pixel = row[x];
                int gray = (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100;
                row[x] = gray > threshold ? Color.WHITE : Color.BLACK;
            }
            output.setPixels(row, 0, source.getWidth(), 0, y, source.getWidth(), 1);
        }
        return output;
    }

    private void recognizeVariant(List<Bitmap> variants, int index, int token) {
        if (!running || token != generation) return;
        if (index >= variants.size()) {
            if (ocrAttempt < 5) loadFreshForm(false);
            else loadFreshForm(true);
            return;
        }
        recognizer.process(InputImage.fromBitmap(variants.get(index), 0))
                .addOnSuccessListener(text -> {
                    if (!running || token != generation) return;
                    String code = normalizeCaptcha(text.getText());
                    if (FOUR_DIGITS.matcher(code).matches()) submitCaptcha(code, token);
                    else recognizeVariant(variants, index + 1, token);
                })
                .addOnFailureListener(error -> recognizeVariant(variants, index + 1, token));
    }

    private String normalizeCaptcha(String input) {
        String upper = input == null ? "" : input.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        String digits = upper.replaceAll("\\D", "");
        if (digits.length() == 4) return digits;
        String normalized = upper
                .replace('O', '0').replace('Q', '0').replace('D', '0')
                .replace('I', '1').replace('L', '1')
                .replace('Z', '2').replace('S', '5')
                .replace('G', '6').replace('B', '8')
                .replaceAll("\\D", "");
        return normalized.length() == 4 ? normalized : "";
    }

    private void showManualCaptcha(Bitmap bitmap, int token) {
        cancelTimeout();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(12), dp(22), 0);
        ImageView image = new ImageView(this);
        image.setImageBitmap(Bitmap.createScaledBitmap(bitmap, dp(240), dp(100), true));
        image.setAdjustViewBounds(true);
        content.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
        EditText input = new EditText(this);
        input.setHint("輸入 4 位數驗證碼");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(20);
        content.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("驗證碼手動備援")
                .setMessage("OCR 連續辨識失敗，請輸入圖片中的 4 位數。")
                .setView(content)
                .setPositiveButton("送出", null)
                .setNegativeButton("略過此單號", (d, which) -> failCurrent("使用者略過驗證碼"))
                .setCancelable(false)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = input.getText().toString().trim();
            if (!FOUR_DIGITS.matcher(code).matches()) {
                input.setError("請輸入 4 位數");
                return;
            }
            dialog.dismiss();
            submitCaptcha(code, token);
        }));
        dialog.show();
    }

    private void submitCaptcha(String code, int token) {
        if (!running || token != generation) return;
        submitAttempt++;
        if (submitAttempt > 6) {
            failCurrent("網站重複返回查詢頁面，已達安全停止上限");
            return;
        }
        stage = Stage.SUBMITTING;
        progress(currentTracking() + "：已輸入驗證碼，等待網站結果…");
        String script = "(function(){"
                + "document.getElementById('txtProductNum').value=" + JSONObject.quote(currentTracking()) + ";"
                + "document.getElementById('tbChkCode').value=" + JSONObject.quote(code) + ";"
                + "document.getElementById('submit').click();"
                + "})();";
        webView.evaluateJavascript(script, null);
        scheduleTimeout("網站查詢逾時");
    }

    private void extractResult(int token) {
        String script = "(function(){"
                + "function t(id){var e=document.getElementById(id);return e?(e.innerText||e.textContent||'').trim():'';}"
                + "var history=Array.from(document.querySelectorAll('#timeline_status li')).map(function(li){"
                + "var lines=(li.innerText||'').split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean);"
                + "var time=lines.find(function(x){return /^\\d{4}\\/\\d{2}\\/\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?$/.test(x);})||'';"
                + "return {time:time,status:lines.filter(function(x){return x!==time;}).join(' ')};"
                + "}).filter(function(x){return x.time&&x.status;});"
                + "var data={tracking:t('query_no'),store:t('store_name'),address:t('store_address'),"
                + "shipDate:t('store_outdate'),arrival:t('arrivalstore_date'),deadline:t('deadline'),"
                + "payment:t('servicetype'),history:history,raw:(document.body.innerText||'')};"
                + "AndroidTracker.onResult(JSON.stringify(data)," + token + ");"
                + "})();";
        webView.evaluateJavascript(script, null);
        scheduleTimeout("結果解析逾時");
    }

    private void handleResult(String json, int token) {
        if (!running || token != generation) return;
        cancelTimeout();
        try {
            JSONObject data = new JSONObject(json);
            String shown = data.optString("tracking").trim().toUpperCase(Locale.ROOT);
            if (!shown.equals(currentTracking())) {
                failCurrent("網站回傳的物流單號不符");
                return;
            }
            TrackingResult result = new TrackingResult(currentTracking());
            result.store = data.optString("store");
            result.address = data.optString("address");
            result.shipDate = data.optString("shipDate");
            result.arrival = data.optString("arrival");
            result.deadline = data.optString("deadline");
            result.payment = data.optString("payment");
            result.raw = data.optString("raw");
            JSONArray history = data.optJSONArray("history");
            if (history != null) {
                for (int i = 0; i < history.length(); i++) {
                    JSONObject item = history.optJSONObject(i);
                    if (item != null) result.history.add(new HistoryItem(item.optString("time"), item.optString("status")));
                }
            }
            Collections.sort(result.history, (a, b) -> a.time.compareTo(b.time));
            if (!result.history.isEmpty()) {
                HistoryItem latest = result.history.get(result.history.size() - 1);
                result.latestTime = latest.time;
                result.latestStatus = latest.status;
            } else {
                result.latestStatus = "查無貨態歷程";
            }
            results.add(result);
            addResultCard(result);
            advanceQueue();
        } catch (JSONException error) {
            failCurrent("結果資料格式無法解析");
        }
    }

    private void failCurrent(String message) {
        cancelTimeout();
        TrackingResult result = new TrackingResult(currentTracking());
        result.error = message;
        result.raw = message;
        results.add(result);
        addResultCard(result);
        advanceQueue();
    }

    private void advanceQueue() {
        currentIndex++;
        if (currentIndex < queue.size()) {
            final int token = generation;
            handler.postDelayed(() -> {
                if (running && token == generation) beginCurrentTracking();
            }, 600);
        } else {
            finishRun();
        }
    }

    private void finishRun() {
        running = false;
        stage = Stage.IDLE;
        startButton.setEnabled(true);
        shareButton.setEnabled(!results.isEmpty());
        int success = 0;
        for (TrackingResult item : results) if (item.error.isEmpty()) success++;
        progress("完成：成功 " + success + " 筆，失敗 " + (results.size() - success) + " 筆");
        Toast.makeText(this, "查詢完成", Toast.LENGTH_LONG).show();
    }

    private void addResultCard(TrackingResult result) {
        TextView card = new TextView(this);
        card.setText(formatResult(result, true));
        card.setTextSize(15);
        card.setTextColor(Color.DKGRAY);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(9));
        resultsContainer.addView(card, params);
    }

    private String formatResult(TrackingResult result, boolean includeHistory) {
        StringBuilder text = new StringBuilder();
        text.append(result.tracking).append('\n');
        if (!result.error.isEmpty()) return text.append("查詢失敗：").append(result.error).toString();
        text.append("最新貨態：").append(result.latestStatus).append('\n');
        text.append("最新時間：").append(result.latestTime).append('\n');
        text.append("取貨門市：").append(result.store).append('\n');
        text.append("門市地址：").append(result.address).append('\n');
        text.append("出貨／預計到店／截止：").append(result.shipDate).append("／")
                .append(result.arrival).append("／").append(result.deadline).append('\n');
        text.append("付款資訊：").append(result.payment);
        if (includeHistory) {
            text.append("\n貨態歷程：");
            for (HistoryItem item : result.history) text.append("\n  ").append(item.time).append("　").append(item.status);
        }
        return text.toString();
    }

    private void shareResults() {
        StringBuilder body = new StringBuilder("7-ELEVEN 貨態查詢結果\n\n");
        for (TrackingResult result : results) body.append(formatResult(result, true)).append("\n\n");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "7-ELEVEN 貨態查詢結果");
        intent.putExtra(Intent.EXTRA_TEXT, body.toString());
        startActivity(Intent.createChooser(intent, "分享查詢結果"));
    }

    private void progress(String message) {
        progressText.setText(message);
    }

    private void scheduleTimeout(String message) {
        cancelTimeout();
        final int token = generation;
        timeoutTask = () -> {
            if (!running || token != generation) return;
            if (!manualAttempt && ocrAttempt < 5) loadFreshForm(false);
            else failCurrent(message);
        };
        handler.postDelayed(timeoutTask, 35_000);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) handler.removeCallbacks(timeoutTask);
        timeoutTask = null;
    }

    private class WebBridge {
        @JavascriptInterface
        public void onPageKind(String kind, int token) {
            runOnUiThread(() -> handlePageKind(kind, token));
        }

        @JavascriptInterface
        public void onCaptcha(String base64, int token) {
            runOnUiThread(() -> {
                if (!running || token != generation) return;
                cancelTimeout();
                try {
                    byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) throw new IllegalArgumentException("空白圖片");
                    recognizeCaptcha(bitmap, token);
                } catch (Exception error) {
                    if (!manualAttempt && ocrAttempt < 5) loadFreshForm(false);
                    else failCurrent("驗證碼圖片無法解析");
                }
            });
        }

        @JavascriptInterface
        public void onResult(String json, int token) {
            runOnUiThread(() -> handleResult(json, token));
        }

        @JavascriptInterface
        public void onJsError(String message, int token) {
            runOnUiThread(() -> {
                if (!running || token != generation) return;
                if (!manualAttempt && ocrAttempt < 5) loadFreshForm(false);
                else failCurrent("網頁操作失敗：" + message);
            });
        }
    }

    private static class HistoryItem {
        final String time;
        final String status;

        HistoryItem(String time, String status) {
            this.time = time;
            this.status = status;
        }
    }

    private static class TrackingResult {
        final String tracking;
        String store = "";
        String address = "";
        String shipDate = "";
        String arrival = "";
        String deadline = "";
        String payment = "";
        String latestTime = "";
        String latestStatus = "";
        String error = "";
        String raw = "";
        final List<HistoryItem> history = new ArrayList<>();

        TrackingResult(String tracking) {
            this.tracking = tracking;
        }
    }

    private void clearSessionData() {
        clearQuerySession(false);
        downloadCancelled = true;
        pendingInstallUri = null;
        pendingLegacyDownload = null;
        handler.removeCallbacksAndMessages(null);
    }

    private void clearQuerySession(boolean announce) {
        cancelTimeout();
        generation++;
        running = false;
        stage = Stage.IDLE;
        queue.clear();
        results.clear();
        currentIndex = 0;
        ocrAttempt = 0;
        submitAttempt = 0;
        manualAttempt = false;
        currentCaptcha = null;
        if (trackingInput != null) trackingInput.setText("");
        if (resultsContainer != null) resultsContainer.removeAllViews();
        if (startButton != null) startButton.setEnabled(true);
        if (shareButton != null) shareButton.setEnabled(false);
        if (progressText != null) progressText.setText("準備就緒");
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.loadUrl("about:blank");
        }
        hideKeyboard();
        if (rootScrollView != null) rootScrollView.post(() -> rootScrollView.smoothScrollTo(0, 0));
        if (announce) Toast.makeText(this, "已清除，可以輸入新的物流單號", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        clearSessionData();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
        else finish();
    }

    @Override
    protected void onDestroy() {
        cancelTimeout();
        downloadCancelled = true;
        if (recognizer != null) recognizer.close();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidTracker");
            webView.destroy();
        }
        super.onDestroy();
    }
}
