package mx.gob.sanpedro.visorurbano;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.customtabs.CustomTabsServiceConnection;
import androidx.browser.customtabs.CustomTabsSession;

/**
 * Visor Urbano SPGG.
 * La pantalla de inicio es local (assets/inicio.html). Al tocar el botón, el Visor Urbano
 * oficial se abre con el motor de Chrome dentro de la app (Custom Tab): misma velocidad,
 * vista móvil, selección de predios y ubicación que en Chrome.
 * Si el teléfono no tiene un navegador compatible, se usa un WebView como respaldo.
 */
public class MainActivity extends Activity {

    static final String START = "file:///android_asset/inicio.html";
    static final String VISOR_URL = "https://visorurbano.sanpedro.gob.mx/";
    static final String VISOR_HOST = "visorurbano.sanpedro.gob.mx";
    static final int NAVY = Color.parseColor("#193275");
    static final int REQ_LOCATION = 10;
    static final int REQ_FILE = 11;

    private WebView web;
    private String tabsPackage;
    private CustomTabsSession tabsSession;
    private CustomTabsServiceConnection tabsConnection;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(NAVY);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setTextZoom(100);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // Identificarse como Chrome móvil para que el visor entregue su versión para celular
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")").replaceAll("Version/\\d+\\.\\d+ ", ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                boolean fromStart = START.equals(view.getUrl());
                if (fromStart && VISOR_HOST.equals(uri.getHost())) {
                    return openVisor(uri);
                }
                if (scheme.equals("http") || scheme.equals("https") || scheme.equals("file")) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, true);
                } else {
                    geoOrigin = origin;
                    geoCallback = callback;
                    requestLocation();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Archivos descargables del visor (PDF, etc.) se abren con la app del teléfono
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException ignored) { }
        });

        prepareTabs();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START);
        }
    }

    /** Conecta con Chrome por adelantado para que el visor abra más rápido. */
    private void prepareTabs() {
        tabsPackage = CustomTabsClient.getPackageName(this, null);
        if (tabsPackage == null) return;
        tabsConnection = new CustomTabsServiceConnection() {
            @Override
            public void onCustomTabsServiceConnected(ComponentName name, CustomTabsClient client) {
                client.warmup(0);
                tabsSession = client.newSession(null);
                if (tabsSession != null) tabsSession.mayLaunchUrl(Uri.parse(VISOR_URL), null, null);
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                tabsSession = null;
            }
        };
        try {
            CustomTabsClient.bindCustomTabsService(this, tabsPackage, tabsConnection);
        } catch (Exception ignored) {
            tabsConnection = null;
        }
    }

    /** Abre el visor con el motor de Chrome; si no hay, lo carga en el WebView. */
    private boolean openVisor(Uri uri) {
        if (tabsPackage != null) {
            CustomTabColorSchemeParams colors = new CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(NAVY)
                    .setNavigationBarColor(NAVY)
                    .build();
            CustomTabsIntent.Builder b = tabsSession != null
                    ? new CustomTabsIntent.Builder(tabsSession)
                    : new CustomTabsIntent.Builder();
            CustomTabsIntent tab = b
                    .setDefaultColorSchemeParams(colors)
                    .setShowTitle(true)
                    .setUrlBarHidingEnabled(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .setBookmarksButtonEnabled(false)
                    .setDownloadButtonEnabled(false)
                    .setInstantAppsEnabled(false)
                    .build();
            tab.intent.setPackage(tabsPackage);
            try {
                tab.launchUrl(this, uri);
                return true;
            } catch (ActivityNotFoundException ignored) { }
        }
        // Respaldo: cargar en el WebView y pedir ubicación de una vez
        if (!hasLocationPermission()) requestLocation();
        return false;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocationPermission(), true);
            geoCallback = null;
            geoOrigin = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (tabsConnection != null) {
            try { unbindService(tabsConnection); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
