package com.telenor.connect;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.customtabs.CustomTabsIntent;
import android.support.v4.app.Fragment;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebResourceResponse;
import android.widget.Toast;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.nimbusds.jwt.SignedJWT;
import com.telenor.connect.id.AccessTokenCallback;
import com.telenor.connect.id.ConnectIdService;
import com.telenor.connect.id.IdToken;
import com.telenor.connect.id.ConnectStore;
import com.telenor.connect.id.UserInfo;
import com.telenor.connect.network.MobileDataFetcher;
import com.telenor.connect.ui.ConnectActivity;
import com.telenor.connect.ui.ConnectWebFragment;
import com.telenor.connect.utils.ConnectUrlHelper;
import com.telenor.connect.utils.ConnectUtils;
import com.telenor.connect.utils.RestHelper;
import com.telenor.connect.utils.Validator;
import com.telenor.mobileconnect.MobileConnectSdkProfile;
import com.telenor.mobileconnect.SimCardStateChangedBroadcastReceiver;
import com.telenor.mobileconnect.operatordiscovery.OperatorDiscoveryConfig;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import okhttp3.HttpUrl;
import retrofit.Callback;
import retrofit.ResponseCallback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public final class ConnectSdk {

    private static ArrayList<Locale> sLocales;
    private static String heToken;
    private static SdkProfile sdkProfile;
    private static ConnectivityManager connectivityManager;
    private static volatile Network cellularNetwork;
    private static volatile Network defaultNetwork;
    private static ConnectStore connectStore;
    private static volatile String advertisingId;
    private static volatile long tsSdkInitialization;
    private static volatile long tsLoginButtonClicked;
    private static volatile long tsRedirectUrlInvoked;
    private static volatile long tsTokenResponseReceived;
    private static volatile String logSessionId;

    /**
     * The key for the client ID in the Android manifest.
     */
    public static final String CLIENT_ID_PROPERTY = "com.telenor.connect.CLIENT_ID";

    /**
     * The key for the client ID in the Android manifest.
     */
    public static final String CONFIDENTIAL_CLIENT_PROPERTY = "com.telenor.connect.CONFIDENTIAL_CLIENT";

    /**
     * The key for the redirect URI in the Android manifest.
     */
    public static final String REDIRECT_URI_PROPERTY = "com.telenor.connect.REDIRECT_URI";

    /**
     * The key to enable the staging environment in the Android manifest.
     */
    public static final String USE_STAGING_PROPERTY = "com.telenor.connect.USE_STAGING";

    public static final String ACTION_LOGIN_STATE_CHANGED =
            "com.telenor.connect.ACTION_LOGIN_STATE_CHANGED";

    public static final String EXTRA_CONNECT_TOKENS =
            "com.telenor.connect.EXTRA_CONNECT_TOKENS";

    public static final int MAX_REDIRECTS_TO_FOLLOW_FOR_HE = 5;

    public static SdkProfile getSdkProfile() {
        Validator.sdkInitialized();
        return sdkProfile;
    }

    public static void beforeAuthentication() {
        logSessionId = UUID.randomUUID().toString();
        tsLoginButtonClicked = System.currentTimeMillis();
    }

    public static synchronized void authenticate(
            Activity activity,
            int requestCode,
            String... scopeTokens) {
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put("scope", TextUtils.join(" ", scopeTokens));
        authenticate(activity, parameters, requestCode);
    }

    public static synchronized void authenticate(
            final Activity activity,
            final Map<String, String> parameters,
            final int requestCode) {
        Validator.sdkInitialized();
        parameters.put("log_session_id", getLogSessionId());
        sdkProfile.onStartAuthorization(parameters, new SdkProfile.OnStartAuthorizationCallback() {
            @Override
            public void onSuccess() {
                Intent intent = getAuthIntent(parameters);
                activity.startActivityForResult(intent, requestCode);
            }

            @Override
            public void onError() {
                showAuthCancelMessage(activity);
            }
        });
    }

    private static Intent getAuthIntent(Map<String, String> parameters) {
        final Intent intent = new Intent();
        intent.setClass(getContext(), ConnectActivity.class);
        intent.setAction(ConnectUtils.LOGIN_ACTION);
        String mccMnc = getMccMnc();
        WellKnownAPI.WellKnownConfig wellKnownConfig = sdkProfile.getWellKnownConfig();
        if (!TextUtils.isEmpty(mccMnc) && wellKnownConfig != null &&
                !(wellKnownConfig.getNetworkAuthenticationTargetIps().isEmpty()
                        && wellKnownConfig.getNetworkAuthenticationTargetUrls().isEmpty()))
        {
            parameters.put("login_hint", String.format("MCCMNC:%s", mccMnc));
        }
        final String url = getAuthorizeUri(parameters, BrowserType.WEB_VIEW).toString();
        intent.putExtra(ConnectUtils.LOGIN_AUTH_URI, url);
        intent.putExtra(ConnectUtils.WELL_KNOWN_CONFIG_EXTRA, wellKnownConfig);
        return intent;
    }

    public static synchronized void authenticate(final Activity activity,
                                                 final Map<String, String> parameters,
                                                 final int customLoadingLayout,
                                                 final int requestCode) {
        Validator.sdkInitialized();
        sdkProfile.onStartAuthorization(parameters, new SdkProfile.OnStartAuthorizationCallback() {
            @Override
            public void onSuccess() {
                Intent intent = getAuthIntent(parameters);
                intent.putExtra(ConnectUtils.CUSTOM_LOADING_SCREEN_EXTRA, customLoadingLayout);
                activity.startActivityForResult(intent, requestCode);
            }

            @Override
            public void onError() {
                showAuthCancelMessage(activity);
            }
        });
    }

    private static void showAuthCancelMessage(Activity activity) {
        Toast.makeText(
                activity,
                R.string.com_telenor_authorization_cancelled,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Get a {@code Fragment} that can be used for authorizing and getting a tokens.
     * {@code Activity} that uses the {@code Fragment} must implement {@code ConnectCallback}.
     *
     * @param parameters authorization parameters
     * @return authorization fragment
     */
    public static Fragment getAuthFragment(Map<String, String> parameters) {
        Validator.sdkInitialized();

        final Fragment fragment = new ConnectWebFragment();
        Intent authIntent = getAuthIntent(parameters);
        String action = authIntent.getAction();
        Bundle bundle = new Bundle(authIntent.getExtras());
        bundle.putString(ConnectUrlHelper.ACTION_ARGUMENT, action);
        fragment.setArguments(bundle);
        fragment.setRetainInstance(true);
        return fragment;
    }

    /**
     * Get a valid Access Token. If a non-expired one is available, that will be given to the
     * callback {@code onSuccess(String accessToken)} method.
     * <p>
     * If it is expired, it will be refreshed and then returned. This requires a network call.
     *
     * @param callback callback that will be called on success or failure to update.
     * @throws ConnectRefreshTokenMissingException if no Request Token is available
     */
    public static synchronized void getValidAccessToken(AccessTokenCallback callback) {
        Validator.sdkInitialized();
        sdkProfile.getConnectIdService().getValidAccessToken(callback);
    }

    public static synchronized String getAccessToken() {
        Validator.sdkInitialized();
        if (sdkProfile.getConnectIdService() != null) {
            return sdkProfile.getConnectIdService().getAccessToken();
        }
        return null;
    }

    /**
     * Get the expiration time of the signed in user. If no user is signed in returns {@code null}.
     *
     * @return the expiration time of the Access Token in form of a {@code Date}.
     */
    public static synchronized Date getAccessTokenExpirationTime() {
        Validator.sdkInitialized();
        return sdkProfile.getConnectIdService().getAccessTokenExpirationTime();
    }

    public static synchronized void getAccessTokenFromCode(String code, final ConnectCallback callback) {
        Validator.sdkInitialized();
        tsRedirectUrlInvoked = System.currentTimeMillis();
        sdkProfile.getConnectIdService().getAccessTokenFromCode(code, new ConnectCallback() {
            @Override
            public void onSuccess(Object successData) {
                tsTokenResponseReceived = System.currentTimeMillis();
                if (callback != null) {
                    callback.onSuccess(successData);
                }
                sendAnalyticsData();
            }

            @Override
            public void onError(Object errorData) {
                tsTokenResponseReceived = System.currentTimeMillis();
                if (callback != null) {
                    callback.onError(errorData);
                }
                sendAnalyticsData();
            }
        });
    }

    private static void sendAnalyticsData() {
        if (getWellKnownConfig() == null || getWellKnownConfig().getAnalyticsEndpoint() == null) {
            return;
        }

        String accessToken = getAccessToken();
        final String auth = accessToken != null ? "Bearer " + accessToken : null;
        final String subject = getIdToken() != null ? getIdToken().getSubject() : null;
        RestHelper.getAnalyticsApi(getWellKnownConfig().getAnalyticsEndpoint()).sendAnalyticsData(
                auth,
                new AnalyticsAPI.SDKAnalyticsData(
                        getApplicationName(),
                        getApplicationVersion(),
                        subject,
                        getLogSessionId(),
                        getAdvertisingId(),
                        tsSdkInitialization,
                        tsLoginButtonClicked,
                        tsRedirectUrlInvoked,
                        tsTokenResponseReceived
                ),
                new ResponseCallback() {
                    @Override
                    public void success(Response response) {
                    }

                    @Override
                    public void failure(RetrofitError error) {
                        Log.e(ConnectUtils.LOG_TAG, "Failed to send analytics data", error);
                    }
                });
    }

    public static synchronized Uri getAuthorizeUri(
            Map<String, String> parameters, BrowserType browserType) {
        if (ConnectSdk.getClientId() == null) {
            throw new ConnectException("Client ID not specified in application manifest.");
        }
        if (ConnectSdk.getRedirectUri() == null) {
            throw new ConnectException("Redirect URI not specified in application manifest.");
        }

        if (parameters.get("scope") == null || parameters.get("scope").isEmpty()) {
            throw new IllegalStateException("Cannot log in without scope tokens.");
        }
        return sdkProfile.getAuthorizeUri(parameters, getUiLocales(), browserType);
    }

    public static HttpUrl getConnectApiUrl() {
        Validator.sdkInitialized();
        return sdkProfile.getApiUrl();
    }

    public static Context getContext() {
        Validator.sdkInitialized();
        return sdkProfile.getContext();
    }

    public static String getClientId() {
        Validator.sdkInitialized();
        return sdkProfile.getClientId();
    }

    public static ArrayList<Locale> getLocales() {
        Validator.sdkInitialized();
        return sLocales;
    }

    public static String getRedirectUri() {
        Validator.sdkInitialized();
        return sdkProfile.getRedirectUri();
    }

    private static ArrayList<String> getUiLocales() {
        ArrayList<String> locales = new ArrayList<>();
        if (ConnectSdk.getLocales() != null && !ConnectSdk.getLocales().isEmpty()) {
            for (Locale locale : ConnectSdk.getLocales()) {
                locales.add(locale.toString());
                locales.add(locale.getLanguage());
            }
        }
        locales.add(Locale.getDefault().toString());
        locales.add(Locale.getDefault().getLanguage());
        return locales;
    }

    public static String getExpectedIssuer() {
        Validator.sdkInitialized();
        return sdkProfile.getExpectedIssuer();
    }

    public static List<String> getExpectedAudiences() {
        Validator.sdkInitialized();
        return sdkProfile.getExpectedAudiences();
    }

    public static synchronized boolean isConfidentialClient() {
        Validator.sdkInitialized();
        return sdkProfile.isConfidentialClient();
    }

    public static synchronized boolean isInitialized() {
        return sdkProfile != null;
    }

    public static void logout() {
        Validator.sdkInitialized();
        sdkProfile.logout();
    }

    public static synchronized void sdkInitialize(Context context) {
        if (isInitialized()) {
            return;
        }

        tsSdkInitialization = System.currentTimeMillis();

        Validator.notNull(context, "context");
        ConnectSdkProfile profile = loadConnectConfig(context);
        sdkProfile = profile;
        connectStore = new ConnectStore(getContext());
        profile.setConnectIdService(
                new ConnectIdService(
                        connectStore,
                        RestHelper.getConnectApi(getConnectApiUrl().toString()),
                        profile.getClientId(),
                        profile.getRedirectUri()));

        initializeCommonComponents();
    }

    public static String getMccMnc() {
        TelephonyManager tel
                = (TelephonyManager)getContext().getSystemService(Context.TELEPHONY_SERVICE);
        return tel.getNetworkOperator();
    }

    public static void setLocales(Locale... locales) {
        sLocales = new ArrayList<Locale>(Arrays.asList(locales));
    }

    public static void setLocales(ArrayList<Locale> locales) {
        sLocales = locales;
    }

    /**
     * Manually update the access token.
     *
     * @param callback callback that will be called on success or failure to update.
     */
    public static void updateTokens(AccessTokenCallback callback) {
        Validator.sdkInitialized();
        sdkProfile.getConnectIdService().updateTokens(callback);
    }

    private static ConnectSdkProfile loadConnectConfig(Context context) {

        ApplicationInfo ai = getApplicationInfo(context);
        if (ai == null || ai.metaData == null) {
            throw new ConnectException("No application metadata was found.");
        }

        ConnectSdkProfile profile = new ConnectSdkProfile(
                context,
                fetchBooleanProperty(ai, USE_STAGING_PROPERTY),
                fetchBooleanProperty(ai, CONFIDENTIAL_CLIENT_PROPERTY));

        Object clientIdObject = ai.metaData.get(CLIENT_ID_PROPERTY);
        if (clientIdObject instanceof String) {
            String clientIdString = (String) clientIdObject;
            profile.setClientId(clientIdString);
        }

        Object redirectUriObject = ai.metaData.get(REDIRECT_URI_PROPERTY);
        if (redirectUriObject instanceof String) {
            profile.setRedirectUri((String) redirectUriObject);
        }

        return profile;
    }

    private static ApplicationInfo getApplicationInfo(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static boolean fetchBooleanProperty(ApplicationInfo appInfo, String propertyName) {
        if (appInfo == null) {
            return false;
        }
        if (appInfo.metaData == null) {
            return false;
        }
        Object booleanPropertyObject = appInfo.metaData.get(propertyName);
        if (!(booleanPropertyObject instanceof Boolean)) {
            return false;
        }
        return (Boolean) booleanPropertyObject;
    }

    public static WellKnownAPI.WellKnownConfig getWellKnownConfig() {
        Validator.sdkInitialized();
        return sdkProfile.getWellKnownConfig();
    }

    /**
     * @return the subject's ID (sub), if one is signed in. Otherwise {@code null}.
     * @deprecated use {@code getIdToken()} instead to access user information.
     */
    @Deprecated
    public static String getSubjectId() {
        Validator.sdkInitialized();
        IdToken idToken = sdkProfile.getConnectIdService().getIdToken();
        return idToken != null ? idToken.getSubject() : null;
    }

    /**
     * Returns the {@code IdToken} of the signed in user, otherwise {@code null}. The presence of
     * the fields depend on the scope and claim variables that were given at sign in time.
     * See http://docs.telenordigital.com/apis/connect/id/authentication.html for more details.
     *
     * @return the ID token for the user
     */
    public static IdToken getIdToken() {
        Validator.sdkInitialized();
        return sdkProfile.getConnectIdService().getIdToken();
    }

    /**
     * Fetches the logged in user's info from the /oauth/userinfo endpoint.
     * See http://docs.telenordigital.com/apis/connect/id/authentication.html#authorization-server-user-information
     * for more details on the scope and claims preconditions needed in order to get the info
     * needed.
     *
     * @param userInfoCallback the callback that will be called on after the response is received
     * @throws ConnectNotInitializedException if no user is signed in.
     */
    public static void getUserInfo(Callback<UserInfo> userInfoCallback) {
        Validator.sdkInitialized();
        sdkProfile.getConnectIdService().getUserInfo(userInfoCallback);
    }

    /**
     * Checks if the Uri data of an {@code Intent} is the same as the apps registered Redirect Uri,
     * with state matching the saved state and a code which can be used to get a valid Access Token.
     * Useful for checking if an {@code Activity} was started by the system calling
     * {@code "example-clientid://oauth2callback?state=xyz&code=abc"}
     *
     * @param intent intent to check data element of.
     * @return true if getData() on the intent matches Redirect Uri, has valid state and code
     * query parameters.
     */
    public static boolean hasValidRedirectUrlCall(Intent intent) {
        final Uri data = intent.getData();
        if (data == null) {
            return false;
        }

        final boolean startsWithCorrect = data.toString().startsWith(getRedirectUri());
        if (!startsWithCorrect) {
            return false;
        }

        final String state = data.getQueryParameter("state");
        String originalState = connectStore.getSessionStateParam();
        if (originalState != null && !originalState.equals(state)) {
            return false;
        }

        final String code = data.getQueryParameter("code");
        if (code == null || code.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * If the intent has a valid Redirect Uri data element the query parameter authorization code
     * will be passed to the {@code getAccessTokenFromCode} method, with the given
     * ConnectCallback callback.
     *
     * @param intent intent to check data element of
     * @param callback callback that will be called upon by the getAccessTokenFromCode method
     *
     * @see #hasValidRedirectUrlCall
     * @see #getAccessTokenFromCode
     */
    public static void handleRedirectUriCallIfPresent(
            Intent intent, ConnectCallback callback) {
        if (!hasValidRedirectUrlCall(intent)) {
            return;
        }
        final String code = getCodeFromIntent(intent);
        getAccessTokenFromCode(code, callback);
    }

    /**
     * Helper method to get the <b>code</b> parameter from an Intent's data Uri.
     * Example: An Intent with a data Uri of
     * {@code "example-clientid://oauth2callback?code=123&state=xyz"} will return "123".
     *
     * @param intent the Intent to get the code from.
     * @return code parameter of an Intent
     */
    public static String getCodeFromIntent(@NonNull Intent intent) {
        final Uri data = intent.getData();
        return data.getQueryParameter("code");
    }

    public static synchronized void sdkInitializeMobileConnect(
            Context context,
            OperatorDiscoveryConfig operatorDiscoveryConfig) {
        if (isInitialized()) {
            return;
        }
        sdkProfile = new MobileConnectSdkProfile(
                context,
                operatorDiscoveryConfig,
                fetchBooleanProperty(getApplicationInfo(context), CONFIDENTIAL_CLIENT_PROPERTY));
        context.registerReceiver(
                SimCardStateChangedBroadcastReceiver.getReceiver(),
                SimCardStateChangedBroadcastReceiver.getIntentFilter());

        initializeCommonComponents();
    }

    public static synchronized void sdkReinitializeMobileConnect() {
        if (!isInitialized()) {
            return;
        }
        MobileConnectSdkProfile profile = (MobileConnectSdkProfile) sdkProfile;
        profile.deInitialize();
    }

    /**
     * Initialize components common to both Mobile Connect and ConnectID SDK profiles
     */
    private static synchronized void initializeCommonComponents() {
        connectivityManager
                = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        initializeCellularNetwork();
        initalizeDefaultNetwork();
        initializeAdvertisingId();
    }

    public static boolean isCellularDataNetworkConnected() {
        if (connectivityManager == null) {
            return false;
        }
        NetworkInfo networkInfo;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        } else {
            if (cellularNetwork == null) {
                return false;
            }
            networkInfo = connectivityManager.getNetworkInfo(cellularNetwork);
        }
        return (networkInfo != null) && networkInfo.isConnected();
    }

    public static boolean isCellularDataNetworkDefault() {
        if (connectivityManager == null) {
            return false;
        }
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_MOBILE;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void initializeCellularNetwork() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
        try {
            connectivityManager.requestNetwork(
                    networkRequest,
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            cellularNetwork = network;
                            initializeHeaderEnrichment();
                        }
                    }
            );
        } catch (SecurityException e) {
            cellularNetwork = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static void initalizeDefaultNetwork() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            connectivityManager.requestNetwork(
                    networkRequest,
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            defaultNetwork = network;
                        }
                    }
            );
        } catch (SecurityException e) {
            defaultNetwork = null;
        }
    }

    private static void initializeAdvertisingId() {
        GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
        if (googleAPI.isGooglePlayServicesAvailable(getContext()) == ConnectionResult.SUCCESS) {
            new Thread(new Runnable() {
                public void run() {
                    AdvertisingIdClient.Info adInfo = null;
                    try {
                        adInfo = AdvertisingIdClient.getAdvertisingIdInfo(getContext());
                        advertisingId = adInfo.getId();
                    } catch (Exception e) {
                        Log.w(ConnectUtils.LOG_TAG, "Failed to read advertising id", e);
                    }
                }
            }).start();
        }
    }

    private static void initializeHeaderEnrichment() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        GetTokenTask getTokenTask = new GetTokenTask();
        getTokenTask.execute("https://grumpy-turkey-95.localtunnel.me/id/api/get-header-enrichment-token");


//        sdkProfile.getConnectIdService().getHeaderEnrichmentToken(new Callback<JsonObject>() {
//            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//            @Override
//            public void success(JsonObject jsonObject, Response response) {
////                Toast.makeText(getContext(), jsonObject.toString(), Toast.LENGTH_LONG).show();
////                JsonObject heToken = jsonObject.get("he-token").getAsJsonObject();
//                String gifUrl;
//                try {
//                    String heTokenString = jsonObject.get("he-token").getAsString();
//                    gifUrl = new JSONObject(heTokenString).getString("gifInject.gif");
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                    return;
//                }
////                Map<String, Object> tokenClaimsSet;
////                try {
////                    tokenClaimsSet = SignedJWT.parse(heToken).getJWTClaimsSet().getCustomClaims();
////                } catch (ParseException e) {
////                    e.printStackTrace();
////                    return;
////                }
//                if (gifUrl == null || gifUrl.isEmpty()) {
//                    return;
//                }
//                MobileDataFetcher.fetchUrlTroughCellular(gifUrl);
//            }
//
//            @Override
//            public void failure(RetrofitError error) {
//                Toast.makeText(getContext(), error.toString(), Toast.LENGTH_LONG).show();
//            }
//        });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static Network getCellularNetwork() {
        return cellularNetwork;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static Network getDefaultNetwork() {
        return defaultNetwork;
    }

    private static String getAdvertisingId() {
        return advertisingId;
    }

    public static String getLogSessionId() {
        return logSessionId;
    }

    private static String getApplicationName() {
        try {
            return getContext().getApplicationInfo().loadLabel(getContext().getPackageManager()).toString();
        } catch (Exception e) {
            Log.e(ConnectUtils.LOG_TAG, "Failed to read application name", e);
            return "";
        }
    }

    private static String getApplicationVersion() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            Log.e(ConnectUtils.LOG_TAG, "Failed to read application version", e);
            return "";
        }
    }

    static class GetTokenTask extends AsyncTask<String, Void, WebResourceResponse> {

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        protected WebResourceResponse doInBackground(String... strings) {
            return MobileDataFetcher.fetchUrlTroughCellular(strings[0]);
        }

        @Override
        protected void onPostExecute(WebResourceResponse webResourceResponse) {
            if (webResourceResponse == null) {
                return;
            }
            String response;
            try {
                InputStream inputStream = webResourceResponse.getData();
                response = IOUtils.toString(inputStream, "UTF-8");
                inputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            String gifUrl;
            JSONObject jsonResponse;
            boolean mustConsent;
            try {
                jsonResponse = new JSONObject(response);
                gifUrl = jsonResponse.getString("gifUrl");
                mustConsent = jsonResponse.getBoolean("mustConsent");
                heToken = jsonResponse.getString("heToken");
            } catch (JSONException e) {
                e.printStackTrace();
                return;
            }

            if (mustConsent) {
                return;
            }

            if (TextUtils.isEmpty(gifUrl)) {
                return;
            }

            new GetHeaderEnrichmentGifTask().execute(gifUrl);
//            Toast.makeText(getContext(), response, Toast.LENGTH_LONG).show();
        }
    }

    static class GetHeaderEnrichmentGifTask extends AsyncTask<String, Void, WebResourceResponse> {

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        protected WebResourceResponse doInBackground(String... strings) {
            return MobileDataFetcher.fetchUrlTroughCellular(strings[0]);
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        protected void onPostExecute(WebResourceResponse webResourceResponse) {
            Toast.makeText(getContext(), "Success!", Toast.LENGTH_LONG).show();
//            String url = "https://grumpy-turkey-95.localtunnel.me/id/signin?gui_config=eyJraWQiOiJ1aV9jYWxsYmFjayIsImFsZyI6IlJTMjU2In0.eyJsb2MiOiJlbiIsImxzaSI6IjA2MmJmMGJhLTU3OGYtNDA2ZC05OTIxLWUyNDY4YjY3N2FiMSIsImxvaCI6W10sInNkdiI6IiIsInVzYyI6ImNvbm5lY3RfaWQiLCJwdWwiOmZhbHNlLCJzc2kiOm51bGwsImhlZSI6dHJ1ZSwicHV0IjoibXNpc2RuIiwibWNkIjpmYWxzZSwiYWNyIjpbIjEiXSwiYnJkIjoiZ29sZGVucnllIiwic2VuIjoic2ltdWxhdG9yIiwicmVzIjoiQklHUkFORE9NU1RSSU5HT0ZHQVJCQUdFVEhBVFdJTExNRUFOTk9USElOR1RPWU9VIiwiYXB0IjoibmF0aXZlIiwidHBhIjpmYWxzZSwib2xkIjpmYWxzZSwidGF1IjpmYWxzZSwidWF0IjpudWxsLCJhdWMiOiJ0ZWxlbm9yZGlnaXRhbC1jb25uZWN0ZXhhbXBsZS1hbmRyb2lkOlwvXC9vYXV0aDJjYWxsYmFjayIsImxwciI6ZmFsc2UsImxobCI6ZmFsc2UsImVzYyI6W10sInZwciI6ZmFsc2UsImJ1biI6IlNpbXVsYXRlZEJ1TmFtZSIsInNscyI6ZmFsc2UsInNsdSI6ZmFsc2UsImNpZCI6ImU5OTgzZWZmLWRlMTktNDQyZi05M2FjLWRlYWJkNzViYjAxZSJ9.f186-zkl35eC-Yw3-Aj9jZaekRRmHI4nZts4jWTvHgP6qkHV5nTdwel8Z_KbhDrLbqNt18vSLdinEQLEUVve5-Ghldm3_r1zZQVUh5xHbncEYYkli8Bi_0vTHQgDK7sOnpjP75r9vkTzLQFhNhiEMTloSFkczt1mxooHiNC0ZoHmydUYV9EKoa8E68fFqEMfEmzPDGEDcKod9f3GWAu1IliEmfPOqN1ZST25DsdZgK3xu60WduSkz1eU3paLo2jaDPwkvFKw0Pz_J5hEf1yovmed4hzy7oXNYpaDv5u5WDlihu_wbEgLWFF3lTDqCtGNLh3xRlqru98gZELuDWh24w";
            String url = "https://grumpy-turkey-95.localtunnel.me/id/signin?gui_config=eyJraWQiOiJ1aV9jYWxsYmFjayIsImFsZyI6IlJTMjU2In0.eyJsb2MiOiJlbiIsImxzaSI6ImQ4MjMyNjgyLWYxMDAtNDAyYy05ZDc2LTMxMTcwYzhmNzZiYSIsImxvaCI6W10sInNkdiI6IiIsInVzYyI6ImNvbm5lY3RfaWQiLCJwdWwiOmZhbHNlLCJzc2kiOm51bGwsImhlZSI6dHJ1ZSwicHV0IjoibXNpc2RuIiwibWNkIjpmYWxzZSwiYWNyIjpbIjIiXSwiYnJkIjoiZ29sZGVucnllIiwic2VuIjoic2ltdWxhdG9yIiwicmVzIjoiQklHUkFORE9NU1RSSU5HT0ZHQVJCQUdFVEhBVFdJTExNRUFOTk9USElOR1RPWU9VIiwiYXB0Ijoid2ViIiwidHBhIjpmYWxzZSwib2xkIjpmYWxzZSwidGF1IjpmYWxzZSwidWF0IjpudWxsLCJhdWMiOiJcL2lkXC9kZWJ1Z1wvbGFuZGluZyIsImxwciI6ZmFsc2UsImxobCI6ZmFsc2UsImVzYyI6W10sInZwciI6ZmFsc2UsImJ1biI6IlNpbXVsYXRlZEJ1TmFtZSIsInNscyI6ZmFsc2UsInNsdSI6ZmFsc2UsImNpZCI6IjM0NjczMjA3LTZlZjEtNDc2NS04ZGViLTNmZDRjY2RmNGM1MiJ9.y_eCDW0yKK-UUcjKi9SNAwtVbREUrgwRaE25zFcilarSp0RBAQM_2hkrjy5hk-sNp01TZn87sKEbH_hgyRlAQheV8oK_hZEP5M_9ytcjoJC7YruwF2vn0gKIg5yqNLFSwuRspqRPQxxDTTrBI3HBGUbP4eB6kAIG5MpvXqETib9gmpsKBrsR9JR5KvEe9T5VxlLVUWXR0VgCX3rs6-VeLRhFbUa_UDk5XKhdQvlJkg37UGtDmOExcoaQHuTorwvUnTR7hz5EivyFEP07aIJxJUhA2_XmVJFnY1gY0PZ2ccOac2ZFdxLBTfO9k2YF8nSq3HicZJxEuLhoLgqvamHFpg";
            CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
            CustomTabsIntent customTabsIntent = builder.build();
            customTabsIntent.launchUrl(getContext(), Uri.parse(url));
        }
    }
}
