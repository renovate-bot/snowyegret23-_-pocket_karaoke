package com.pocketkaraoke;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "pocket_karaoke";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_PITCH = "pitch";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_AD_BLOCK = "ad_block";
    private static final String DEFAULT_URL = "https://www.google.com";
    private static final int MIN_PITCH = -12;
    private static final int MAX_PITCH = 12;

    private final List<Bookmark> bookmarks = new ArrayList<>();

    private WebView webView;
    private EditText urlBar;
    private TextView pitchLabel;
    private Button adBlockButton;
    private SharedPreferences preferences;
    private AdBlocker adBlocker;
    private String pitchScript;
    private int pitchSemitones;
    private volatile boolean adBlockEnabled;
    private volatile String currentDocumentHost = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        adBlocker = new AdBlocker(getFilesDir());
        pitchSemitones = clampPitch(preferences.getInt(KEY_PITCH, 0));
        adBlockEnabled = preferences.getBoolean(KEY_AD_BLOCK, true);
        loadBookmarks();
        try {
            pitchScript = readAsset("pocket_karaoke.js");
        } catch (IOException e) {
            pitchScript = "";
        }

        View contentView = createLayout();
        setContentView(contentView);
        contentView.requestApplyInsets();
        configureWebView();
        adBlocker.start(updated -> {
            updateAdBlockButtonOnUiThread();
            if (updated && adBlockEnabled && webView != null) {
                runOnUiThread(() -> webView.reload());
            }
        });

        String initialUrl = preferences.getString(KEY_LAST_URL, DEFAULT_URL);
        webView.loadUrl(normalizeUrl(initialUrl == null ? DEFAULT_URL : initialUrl));
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private View createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        applySystemBarPadding(root);

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(dp(6), dp(6), dp(6), dp(3));

        Button backButton = makeButton(getString(R.string.button_back));
        backButton.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });
        navRow.addView(backButton);

        Button forwardButton = makeButton(getString(R.string.button_forward));
        forwardButton.setOnClickListener(v -> {
            if (webView.canGoForward()) {
                webView.goForward();
            }
        });
        navRow.addView(forwardButton);

        Button reloadButton = makeButton(getString(R.string.button_reload));
        reloadButton.setOnClickListener(v -> webView.reload());
        navRow.addView(reloadButton);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(14);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setInputType(EditorInfo.TYPE_TEXT_VARIATION_URI);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterPressed = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadUrlFromBar();
                return true;
            }
            return false;
        });
        navRow.addView(urlBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button goButton = makeButton(getString(R.string.button_go));
        goButton.setOnClickListener(v -> loadUrlFromBar());
        navRow.addView(goButton);
        root.addView(navRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout pitchRow = new LinearLayout(this);
        pitchRow.setOrientation(LinearLayout.HORIZONTAL);
        pitchRow.setGravity(Gravity.CENTER_VERTICAL);
        pitchRow.setPadding(dp(6), dp(3), dp(6), dp(6));

        TextView keyTitle = new TextView(this);
        keyTitle.setText(R.string.key_label);
        keyTitle.setTextSize(14);
        keyTitle.setPadding(dp(6), 0, dp(8), 0);
        pitchRow.addView(keyTitle);

        Button pitchDownButton = makeButton("-");
        pitchDownButton.setOnClickListener(v -> setPitch(pitchSemitones - 1));
        pitchRow.addView(pitchDownButton);

        pitchLabel = new TextView(this);
        pitchLabel.setGravity(Gravity.CENTER);
        pitchLabel.setTextSize(16);
        pitchLabel.setMinWidth(dp(58));
        pitchLabel.setPadding(dp(8), 0, dp(8), 0);
        updatePitchLabel();
        pitchRow.addView(pitchLabel);

        Button pitchUpButton = makeButton("+");
        pitchUpButton.setOnClickListener(v -> setPitch(pitchSemitones + 1));
        pitchRow.addView(pitchUpButton);

        Button resetButton = makeButton(getString(R.string.button_reset));
        resetButton.setOnClickListener(v -> setPitch(0));
        pitchRow.addView(resetButton);

        Button saveBookmarkButton = makeButton(getString(R.string.button_save_bookmark));
        saveBookmarkButton.setOnClickListener(v -> saveCurrentBookmark());
        pitchRow.addView(saveBookmarkButton);

        Button bookmarksButton = makeButton(getString(R.string.button_bookmarks));
        bookmarksButton.setOnClickListener(v -> showBookmarks());
        pitchRow.addView(bookmarksButton);

        adBlockButton = makeButton("");
        adBlockButton.setOnClickListener(v -> toggleAdBlock());
        adBlockButton.setOnLongClickListener(v -> {
            if (adBlocker != null) {
                adBlocker.refresh(updated -> {
                    updateAdBlockButtonOnUiThread();
                    if (adBlockEnabled && webView != null) {
                        runOnUiThread(() -> webView.reload());
                    }
                });
            }
            return true;
        });
        updateAdBlockButton();
        pitchRow.addView(adBlockButton);

        HorizontalScrollView pitchScroll = new HorizontalScrollView(this);
        pitchScroll.setFillViewport(true);
        pitchScroll.setHorizontalScrollBarEnabled(false);
        pitchScroll.addView(pitchRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(pitchScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        return root;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adBlockEnabled
                        && adBlocker != null
                        && adBlocker.shouldBlock(request.getUrl(), currentDocumentHost, request.isForMainFrame())) {
                    return emptyBlockedResponse();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentDocumentHost = hostFromUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                currentDocumentHost = hostFromUrl(url);
                urlBar.setText(url);
                saveState();
                applyPitchToPage();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
            }
        });
    }

    private void applySystemBarPadding(View root) {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(40));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private void loadUrlFromBar() {
        String input = urlBar.getText() == null ? "" : urlBar.getText().toString();
        String url = normalizeUrl(input);
        urlBar.setText(url);
        webView.loadUrl(url);
    }

    private String normalizeUrl(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_URL;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://" + trimmed;
        }
        return "https://www.google.com/search?q=" + trimmed.replace(" ", "+");
    }

    private void setPitch(int value) {
        pitchSemitones = clampPitch(value);
        updatePitchLabel();
        preferences.edit().putInt(KEY_PITCH, pitchSemitones).apply();
        applyPitchToPage();
    }

    private int clampPitch(int value) {
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, value));
    }

    private void updatePitchLabel() {
        if (pitchLabel == null) {
            return;
        }
        String prefix = pitchSemitones > 0 ? "+" : "";
        pitchLabel.setText(String.format(Locale.US, "%s%d", prefix, pitchSemitones));
    }

    private void applyPitchToPage() {
        if (webView == null || pitchScript == null || pitchScript.isEmpty()) {
            return;
        }
        String script = pitchScript.replace("__PITCH_SEMITONES__", Integer.toString(pitchSemitones));
        webView.evaluateJavascript(script, null);
    }

    private void toggleAdBlock() {
        adBlockEnabled = !adBlockEnabled;
        preferences.edit().putBoolean(KEY_AD_BLOCK, adBlockEnabled).apply();
        updateAdBlockButton();
        if (webView != null) {
            webView.reload();
        }
    }

    private void updateAdBlockButton() {
        if (adBlockButton == null) {
            return;
        }
        String label = adBlockEnabled
                ? getString(R.string.button_ad_block_on)
                : getString(R.string.button_ad_block_off);
        int ruleCount = adBlocker == null ? 0 : adBlocker.getRuleCount();
        if (adBlockEnabled && ruleCount > 0) {
            label = getString(R.string.button_ad_block_on_with_count, ruleCount);
        }
        adBlockButton.setText(label);
    }

    private void updateAdBlockButtonOnUiThread() {
        runOnUiThread(this::updateAdBlockButton);
    }

    private String hostFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.US);
    }

    private WebResourceResponse emptyBlockedResponse() {
        return new WebResourceResponse(
                "text/plain",
                "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    private void saveCurrentBookmark() {
        String url = webView.getUrl();
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        String title = webView.getTitle();
        if (title == null || title.trim().isEmpty()) {
            title = url;
        }
        Bookmark bookmark = new Bookmark(title, url, pitchSemitones);
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).url.equals(url)) {
                bookmarks.set(i, bookmark);
                saveBookmarks();
                return;
            }
        }
        bookmarks.add(bookmark);
        saveBookmarks();
    }

    private void showBookmarks() {
        if (bookmarks.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bookmarks_title)
                    .setMessage(R.string.bookmarks_empty)
                    .setPositiveButton(R.string.button_ok, null)
                    .show();
            return;
        }

        String[] labels = new String[bookmarks.size()];
        for (int i = 0; i < bookmarks.size(); i++) {
            Bookmark bookmark = bookmarks.get(i);
            String prefix = bookmark.pitch > 0 ? "+" : "";
            labels[i] = getString(R.string.bookmark_item, bookmark.title, bookmark.url, prefix, bookmark.pitch);
        }

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks_title)
                .setView(listView)
                .setNegativeButton(R.string.button_close, null)
                .create();
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Bookmark bookmark = bookmarks.get(position);
            setPitch(bookmark.pitch);
            webView.loadUrl(bookmark.url);
            dialog.dismiss();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDeleteBookmark(position, dialog);
            return true;
        });
        dialog.show();
    }

    private void confirmDeleteBookmark(int position, AlertDialog bookmarksDialog) {
        if (position < 0 || position >= bookmarks.size()) {
            return;
        }
        Bookmark bookmark = bookmarks.get(position);
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_bookmark_title)
                .setMessage(getString(R.string.delete_bookmark_message, bookmark.title))
                .setPositiveButton(R.string.button_delete, (dialog, which) -> {
                    bookmarks.remove(position);
                    saveBookmarks();
                    if (bookmarksDialog != null && bookmarksDialog.isShowing()) {
                        bookmarksDialog.dismiss();
                    }
                    showBookmarks();
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void loadBookmarks() {
        bookmarks.clear();
        String json = preferences.getString(KEY_BOOKMARKS, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String title = object.optString("title", "");
                String url = object.optString("url", "");
                int pitch = clampPitch(object.optInt("pitch", 0));
                if (!url.isEmpty()) {
                    bookmarks.add(new Bookmark(title.isEmpty() ? url : title, url, pitch));
                }
            }
        } catch (JSONException ignored) {
            bookmarks.clear();
        }
    }

    private void saveBookmarks() {
        JSONArray array = new JSONArray();
        for (Bookmark bookmark : bookmarks) {
            JSONObject object = new JSONObject();
            try {
                object.put("title", bookmark.title);
                object.put("url", bookmark.url);
                object.put("pitch", bookmark.pitch);
                array.put(object);
            } catch (JSONException ignored) {
            }
        }
        preferences.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
    }

    private void saveState() {
        if (webView == null) {
            return;
        }
        String url = webView.getUrl();
        if (url != null && !url.trim().isEmpty()) {
            preferences.edit()
                    .putString(KEY_LAST_URL, url)
                    .putInt(KEY_PITCH, pitchSemitones)
                    .apply();
        }
    }

    private String readAsset(String assetName) throws IOException {
        try (InputStream inputStream = getAssets().open(assetName);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static final class Bookmark {
        final String title;
        final String url;
        final int pitch;

        Bookmark(String title, String url, int pitch) {
            this.title = title;
            this.url = url;
            this.pitch = pitch;
        }
    }
}
