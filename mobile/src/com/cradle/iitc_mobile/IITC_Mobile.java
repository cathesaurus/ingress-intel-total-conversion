package com.cradle.iitc_mobile;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.SearchView;
import android.widget.Toast;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;

public class IITC_Mobile extends Activity {

    private static final int REQUEST_LOGIN = 1;

    private IITC_WebView mIitcWebView;
    private OnSharedPreferenceChangeListener mSharedPrefChangeListener;
    private final String mIntelUrl = "https://www.ingress.com/intel";
    private boolean mIsLocEnabled = false;
    private Location mLastLocation = null;
    private LocationManager mLocMngr = null;
    private LocationListener mLocListener = null;
    private boolean mFullscreenMode = false;
    private IITC_DeviceAccountLogin mLogin;
    private MenuItem mSearchMenuItem;
    private boolean mDesktopMode = false;
    private boolean mAdvancedMenu = false;
    private boolean mReloadNeeded = false;
    private final ArrayList<String> mDialogStack = new ArrayList<String>();
    private SharedPreferences mSharedPrefs;
    private IITC_ActionBarHelper mActionBarHelper;

    // Used for custom back stack handling
    private final ArrayList<Integer> mBackStack = new ArrayList<Integer>();
    private boolean mBackStackPush = true;
    private int mCurrentPane = android.R.id.home;
    private boolean mBackButtonPressed = false;

    public static final SparseArray<String> PANE_TITLES = new SparseArray<String>();
    public static final HashMap<String, Integer> PANES = new HashMap<String, Integer>();

    static {
        PANES.put("map", android.R.id.home);
        PANES.put("info", R.id.menu_info);
        PANES.put("full", R.id.menu_full);
        PANES.put("compact", R.id.menu_compact);
        PANES.put("public", R.id.menu_public);
        PANES.put("faction", R.id.menu_faction);
        PANES.put("debug", R.id.menu_debug);

        // No need to declare android.R.id.home - that title is default
        PANE_TITLES.append(R.id.menu_info, "Info");
        PANE_TITLES.append(R.id.menu_full, "Full");
        PANE_TITLES.append(R.id.menu_compact, "Compact");
        PANE_TITLES.append(R.id.menu_public, "Public");
        PANE_TITLES.append(R.id.menu_faction, "Faction");
        PANE_TITLES.append(R.id.menu_debug, "Debug");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // enable progress bar above action bar
        requestWindowFeature(Window.FEATURE_PROGRESS);

        setContentView(R.layout.activity_main);
        mIitcWebView = (IITC_WebView) findViewById(R.id.iitc_webview);

        // pass ActionBar to helper because we deprecated getActionBar
        mActionBarHelper = new IITC_ActionBarHelper(this, super.getActionBar());

        // do something if user changed something in the settings
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSharedPrefChangeListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    SharedPreferences sharedPreferences, String key) {
                if (key.equals("pref_force_desktop")) {
                    mDesktopMode = sharedPreferences.getBoolean("pref_force_desktop", false);
                    mActionBarHelper.onPrefChanged();
                    invalidateOptionsMenu();
                }
                if (key.equals("pref_user_loc"))
                    mIsLocEnabled = sharedPreferences.getBoolean("pref_user_loc",
                            false);
                if (key.equals("pref_fullscreen_actionbar")) {
                    mActionBarHelper.onPrefChanged();
                    return;
                }
                if (key.equals("pref_advanced_menu")) {
                    mAdvancedMenu = sharedPreferences.getBoolean("pref_advanced_menu", false);
                    invalidateOptionsMenu();
                    // no reload needed
                    return;
                }
                // no reload needed
                if (key.equals("pref_press_twice_to_exit") || key.equals("pref_share_selected_tab"))
                    return;

                mReloadNeeded = true;
            }
        };
        mSharedPrefs.registerOnSharedPreferenceChangeListener(mSharedPrefChangeListener);

        // enable/disable mDesktopMode mode on menu create and url load
        mDesktopMode = mSharedPrefs.getBoolean("pref_force_desktop", false);

        // enable/disable advance menu
        mAdvancedMenu = mSharedPrefs.getBoolean("pref_advanced_menu", false);

        // Acquire a reference to the system Location Manager
        mLocMngr = (LocationManager) this
                .getSystemService(Context.LOCATION_SERVICE);

        // Define a mSharedPrefChangeListener that responds to location updates
        mLocListener = new LocationListener() {
            public void onLocationChanged(Location location) {
                // Called when a new location is found by the network location
                // provider.
                drawMarker(location);
                mLastLocation = location;
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            public void onProviderEnabled(String provider) {
            }

            public void onProviderDisabled(String provider) {
            }
        };

        mIsLocEnabled = mSharedPrefs.getBoolean("pref_user_loc", false);
        if (mIsLocEnabled) {
            // Register the mSharedPrefChangeListener with the Location Manager to receive
            // location updates
            mLocMngr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    0, 0, mLocListener);
            mLocMngr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0,
                    mLocListener);
        }

        // Clear the back stack
        mBackStack.clear();

        handleIntent(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void handleIntent(Intent intent, boolean onCreate) {
        // load new iitc web view with ingress intel page
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();
            Log.d("iitcm", "intent received url: " + uri.toString());

            if (uri.getScheme().equals("http") || uri.getScheme().equals("https")) {
                if (uri.getHost() != null
                        && (uri.getHost().equals("ingress.com") || uri.getHost().endsWith(".ingress.com"))) {
                    Log.d("iitcm", "loading url...");
                    this.loadUrl(uri.toString());
                    return;
                }
            }

            if (uri.getScheme().equals("geo")) {
                try {
                    handleGeoUri(uri);
                    return;
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.intent_error)
                            .setMessage(e.getReason())
                            .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            }
        }

        if (Intent.ACTION_SEARCH.equals(action)) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            query = query.replace("'", "''");
            final SearchView searchView =
                    (SearchView) mSearchMenuItem.getActionView();
            searchView.setQuery(query, false);
            searchView.clearFocus();
            mActionBarHelper.switchTo(android.R.id.home);
            backStackUpdate(android.R.id.home);
            mIitcWebView.loadUrl("javascript:search('" + query + "');");
            return;
        }

        if (onCreate) {
            this.loadUrl(mIntelUrl);
        }
    }

    private void handleGeoUri(Uri uri) throws URISyntaxException {
        String[] parts = uri.getSchemeSpecificPart().split("\\?", 2);
        Double lat, lon;
        Integer z = null;

        // parts[0] may contain an 'uncertainty' parameter, delimited by a semicolon
        String[] pos = parts[0].split(";", 2)[0].split(",", 2);
        if (pos.length != 2)
            throw new URISyntaxException(uri.toString(), "URI does not contain a valid position");

        try {
            lat = Double.valueOf(pos[0]);
            lon = Double.valueOf(pos[1]);
        } catch (NumberFormatException e) {
            URISyntaxException use = new URISyntaxException(uri.toString(), "position could not be parsed");
            use.initCause(e);
            throw use;
        }

        if (parts.length > 1) { // query string present
            // search for z=
            for (String param : parts[1].split("&")) {
                if (param.startsWith("z=")) {
                    try {
                        z = Integer.valueOf(param.substring(2));
                    } catch (NumberFormatException e) {
                        URISyntaxException use = new URISyntaxException(uri.toString(), "could not parse zoom level");
                        use.initCause(e);
                        throw use;
                    }
                    break;
                }
            }
        }

        String url = "http://www.ingress.com/intel?ll=" + lat + "," + lon;
        if (z != null)
            url += "&z=" + z;
        this.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // enough idle...let's do some work
        Log.d("iitcm", "resuming...reset idleTimer");
        mIitcWebView.updateCaching();

        if (mIsLocEnabled) {
            // Register the mSharedPrefChangeListener with the Location Manager to receive
            // location updates
            mLocMngr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, mLocListener);
            mLocMngr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocListener);
        }

        if (mReloadNeeded) {
            Log.d("iitcm", "preference had changed...reload needed");
            reloadIITC();
        }
        else {
            // iitc is not fully booted...timer will be reset by the script itself
            if (findViewById(R.id.imageLoading).getVisibility() == View.GONE) {
                mIitcWebView.loadUrl("javascript: window.idleReset();");
            }
        }
    }

    @Override
    protected void onStop() {
        Log.d("iitcm", "stopping iitcm");
        mIitcWebView.loadUrl("javascript: window.idleSet();");

        if (mIsLocEnabled)
            mLocMngr.removeUpdates(mLocListener);

        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Log.d("iitcm", "configuration changed...restoring...reset idleTimer");
        mIitcWebView.loadUrl("javascript: window.idleTime = 0");
        mIitcWebView.loadUrl("javascript: window.renderUpdateStatus()");
    }

    // we want a self defined behavior for the back button
    @Override
    public void onBackPressed() {
        // first kill all open iitc dialogs
        if (!mDialogStack.isEmpty()) {
            int last = mDialogStack.size() - 1;
            String id = mDialogStack.get(last);
            mIitcWebView.loadUrl("javascript: " +
                    "var selector = $(window.DIALOGS['" + id + "']); " +
                    "selector.dialog('close'); " +
                    "selector.remove();");
            return;
        }
        // exit fullscreen mode if it is enabled and action bar is disabled
        // or the back stack is empty
        if (mFullscreenMode && (mBackStack.isEmpty() || mActionBarHelper.hideInFullscreen())) {
            this.toggleFullscreen();
        } else if (!mBackStack.isEmpty()) {
            // Pop last item from backstack and pretend the relevant menu item was clicked
            backStackPop();
        } else {
            if (mBackButtonPressed || !mSharedPrefs.getBoolean("pref_press_twice_to_exit", false))
                super.onBackPressed();
            else {
                mBackButtonPressed = true;
                Toast.makeText(this, "Press twice to exit", Toast.LENGTH_SHORT).show();
                // reset back button after 2 seconds
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mBackButtonPressed = false;
                    }
                }, 2000);
            }
        }
    }

    public void backStackPop() {
        // shouldn't be called when back stack is empty
        // catch wrong usage
        if (mBackStack.isEmpty()) {
            // Empty back stack means we should be at home (ie map) screen
            mActionBarHelper.switchTo(android.R.id.home);
            mIitcWebView.loadUrl("javascript: window.show('map');");
            return;
        }
        int index = mBackStack.size() - 1;
        int itemId = mBackStack.remove(index);
        mBackStackPush = false;
        handleMenuItemSelected(itemId);
    }

    public void backStackUpdate(int itemId) {
        // ensure no double adds
        if (itemId == mCurrentPane) return;
        if (itemId == android.R.id.home) {
            mBackStack.clear();
            mBackStackPush = true;
        } else {
            if (mBackStackPush)
                mBackStack.add(mCurrentPane);
            else
                mBackStackPush = true;
        }

        mCurrentPane = itemId;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        // Get the SearchView and set the searchable configuration
        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        this.mSearchMenuItem = menu.findItem(R.id.menu_search);
        final SearchView searchView =
                (SearchView) mSearchMenuItem.getActionView();
        // Assumes current activity is the searchable activity
        searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
        searchView.setIconifiedByDefault(false); // Do not iconify the widget; expand it by default
        // enable/disable mDesktopMode menu
        enableDesktopUI(menu);
        enableAdvancedMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        final int itemId = item.getItemId();
        boolean result = handleMenuItemSelected(itemId);
        return result || super.onOptionsItemSelected(item);
    }

    public boolean handleMenuItemSelected(int itemId) {
        switch (itemId) {
            case android.R.id.home:
                mIitcWebView.loadUrl("javascript: window.show('map');");
                return true;
            case R.id.reload_button:
                reloadIITC();
                return true;
            case R.id.toggle_fullscreen:
                toggleFullscreen();
                return true;
            case R.id.layer_chooser:
                // Force map view to handle potential issue with back stack
                if (!mBackStack.isEmpty() && mCurrentPane != android.R.id.home)
                    mIitcWebView.loadUrl("javascript: window.show('map');");
                // the getLayers function calls the setLayers method of IITC_JSInterface
                mIitcWebView.loadUrl("javascript: window.layerChooser.getLayers()");
                return true;
            case R.id.locate: // get the users current location and focus it on map
                mIitcWebView.loadUrl("javascript: window.show('map');");
                // get location from network by default
                if (!mIsLocEnabled) {
                    mIitcWebView.loadUrl("javascript: " +
                            "window.map.locate({setView : true, maxZoom: 15});");
                    // if gps location is displayed we can use a better location without any costs
                } else {
                    if (mLastLocation != null)
                        mIitcWebView.loadUrl("javascript: window.map.setView(new L.LatLng(" +
                                mLastLocation.getLatitude() + "," +
                                mLastLocation.getLongitude() + "), 15);");
                }
                return true;
            case R.id.action_settings: // start settings activity
                Intent intent = new Intent(this, IITC_PreferenceActivity.class);
                intent.putExtra("iitc_version", mIitcWebView.getWebViewClient()
                        .getIITCVersion());
                startActivity(intent);
                return true;
            case R.id.menu_info:
                mIitcWebView.loadUrl("javascript: window.show('info');");
                return true;
            case R.id.menu_full:
                mIitcWebView.loadUrl("javascript: window.show('full');");
                return true;
            case R.id.menu_compact:
                mIitcWebView.loadUrl("javascript: window.show('compact');");
                return true;
            case R.id.menu_public:
                mIitcWebView.loadUrl("javascript: window.show('public');");
                return true;
            case R.id.menu_faction:
                mIitcWebView.loadUrl("javascript: window.show('faction');");
                return true;
            case R.id.menu_debug:
                mIitcWebView.loadUrl("javascript: window.show('debug')");
                return true;
            case R.id.menu_clear_cookies:
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookie();
                return true;
            default:
                return false;
        }
    }

    public void reloadIITC() {
        mActionBarHelper.reset();
        mBackStack.clear();
        // iitc starts on map after reload
        mCurrentPane = android.R.id.home;
        loadUrl(mIntelUrl);
        mReloadNeeded = false;
    }

    private void loadIITC() {
        try {
            mIitcWebView.getWebViewClient().loadIITC_JS(this);
        } catch (IOException e1) {
            e1.printStackTrace();
        } catch (NullPointerException e2) {
            e2.printStackTrace();
        }
    }

    // vp=f enables mDesktopMode mode...vp=m is the defaul mobile view
    private String addUrlParam(String url) {
        if (mDesktopMode)
            return (url + "?vp=f");
        else
            return (url + "?vp=m");
    }

    // inject the iitc-script and load the intel url
    // plugins are injected onPageFinished
    public void loadUrl(String url) {
        showSplashScreen();
        url = addUrlParam(url);
        loadIITC();
        mIitcWebView.loadUrl(url);
    }

    // update the user location marker on the map
    public void drawMarker(Location loc) {
        // throw away all positions with accuracy > 100 meters
        // should avoid gps glitches
        if (loc.getAccuracy() < 100) {
            // do not touch the javascript while iitc boots
            if (findViewById(R.id.imageLoading).getVisibility() == View.GONE) {
                mIitcWebView.loadUrl("javascript: "
                        + "window.plugin.userLocation.updateLocation( "
                        + loc.getLatitude() + ", " + loc.getLongitude() + ");");
            }
        }
    }

    public void toggleFullscreen() {
        mFullscreenMode = !mFullscreenMode;
        mActionBarHelper.setFullscreen(mFullscreenMode);

        // toggle notification bar
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.flags ^= WindowManager.LayoutParams.FLAG_FULLSCREEN;
        this.getWindow().setAttributes(attrs);
    }

    public IITC_WebView getWebView() {
        return this.mIitcWebView;
    }

    /**
     * It can occur that in order to authenticate, an external activity has to be launched.
     * (This could for example be a confirmation dialog.)
     */
    public void startLoginActivity(Intent launch) {
        startActivityForResult(launch, REQUEST_LOGIN); // REQUEST_LOGIN is to recognize the result
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_LOGIN:
                // authentication activity has returned. mLogin will continue authentication
                mLogin.onActivityResult(resultCode, data);
                break;

            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * called by IITC_WebViewClient when the Google login form is opened.
     */
    public void onReceivedLoginRequest(IITC_WebViewClient client, WebView view,
            String realm, String account, String args) {
        Log.d("iitcm", "logging in...set caching mode to default");
        mIitcWebView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        mLogin = new IITC_DeviceAccountLogin(this, view, client);
        mLogin.startLogin(realm, account, args);
    }

    /**
     * called after successful login
     */
    public void loginSucceeded() {
        // garbage collection
        mLogin = null;
        showSplashScreen();
    }

    // disable/enable some menu buttons...
    public void enableDesktopUI(Menu menu) {
        MenuItem item;
        item = menu.findItem(R.id.menu_chat);
        item.setVisible(!mDesktopMode);
        item = menu.findItem(R.id.menu_info);
        item.setVisible(!mDesktopMode);
        item = menu.findItem(R.id.menu_debug);
        item.setVisible(!mDesktopMode);
    }

    // remove dialog and add it back again
    // to ensure it is the last element of the list
    // focused dialogs should be closed first
    public void setFocusedDialog(String id) {
        Log.d("iitcm", "Dialog " + id + " focused");
        mDialogStack.remove(id);
        mDialogStack.add(id);
    }

    // called by the javascript interface
    public void dialogOpened(String id, boolean open) {
        if (open) {
            Log.d("iitcm", "Dialog " + id + " added");
            mDialogStack.add(id);
        } else {
            Log.d("iitcm", "Dialog " + id + " closed");
            mDialogStack.remove(id);
        }
    }

    public void showSplashScreen() {
        if (!mSharedPrefs.getBoolean("pref_disable_splash", false)) {
            findViewById(R.id.iitc_webview).setVisibility(View.GONE);
            findViewById(R.id.imageLoading).setVisibility(View.VISIBLE);
        }
    }

    public void enableAdvancedMenu(Menu menu) {
        MenuItem item;
        item = menu.findItem(R.id.menu_debug);
        item.setVisible(mAdvancedMenu);
        item = menu.findItem(R.id.menu_clear_cookies);
        item.setVisible(mAdvancedMenu);
    }

    /**
     * @deprecated ActionBar related stuff should be handled by ActionBarHelper
     */
    @Deprecated
    @Override
    public ActionBar getActionBar() {
        return super.getActionBar();
    }

    public IITC_ActionBarHelper getActionBarHelper() {
        return mActionBarHelper;
    }
}
