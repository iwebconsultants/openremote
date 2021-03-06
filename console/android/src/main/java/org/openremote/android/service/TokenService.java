package org.openremote.android.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.openremote.android.R;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


public class TokenService {

    private static final Logger LOG = Logger.getLogger(TokenService.class.getName());

    private final SharedPreferences sharedPref;
    private final String refreshTokenKey;
    private final String fcmTokenKey;
    private final String deviceIdKey;
    private final String realm;
    private final String baseUrl;
    private final OkHttpClient okHttpClient;
    private final OAuth2Service oauth2Service;
    private final RestApiResource restApiResource;

    public TokenService(Context context) {
        baseUrl = context.getString(R.string.OR_BASE_SERVER);

        Retrofit.Builder builder = new Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(JacksonConverterFactory.create());

        if (Boolean.parseBoolean(context.getString(R.string.SSL_IGNORE))) {
            okHttpClient = getUnsafeOkHttpClient();
        } else {
            okHttpClient = new OkHttpClient();
        }

        builder.client(okHttpClient);
        Retrofit retrofit = builder.build();

        oauth2Service = retrofit.create(OAuth2Service.class);
        restApiResource = retrofit.create(RestApiResource.class);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        refreshTokenKey = context.getString(R.string.SHARED_PREF_REFRESH_TOKEN);
        fcmTokenKey = context.getString(R.string.SHARED_PREF_FCM_TOKEN);
        deviceIdKey = context.getString(R.string.SHARED_PREF_DEVICE_ID);
        realm = context.getString(R.string.OR_REALM);
    }

    public void sendOrStoreFCMToken(String fcmToken, String deviceId) {
        LOG.info("Storing FCM token: " + fcmToken);
        LOG.info("Storing Device ID: " + deviceId);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(fcmTokenKey, fcmToken);
        editor.putString(deviceIdKey, deviceId);
        editor.commit();
    }

    // TODO: Support other content types
    public void executeRequest(final String httpMethod, String url, String body) {

        // If relative URL assume it is relative to base URL
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
            if (url.startsWith("/")) {
                url = baseUrl + url;
            } else {
                url = baseUrl + "/" + url;
            }
        }

        // If URL is for our server and refresh token present then use authentication
        String refreshToken = sharedPref.getString(refreshTokenKey, null);
        boolean useAuth = url.startsWith(baseUrl) && !TextUtils.isEmpty(refreshToken);
        final Request.Builder requestBuilder = new Request.Builder().url(url);
        RequestBody requestBody = !TextUtils.isEmpty(body)
            ? RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body)
            : null;

        try {
            switch (httpMethod.toUpperCase(Locale.ROOT)) {
                case "GET":
                    requestBuilder.get();
                    break;
                case "POST":
                    requestBuilder.post(requestBody);
                    break;
                case "PUT":
                    requestBuilder.put(requestBody);
                    break;
                case "PATCH":
                    requestBuilder.patch(requestBody);
                    break;
                case "DELETE":
                    if (requestBody != null) {
                        requestBuilder.delete(requestBody);
                    } else {
                        requestBuilder.delete();
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP Method: " + httpMethod);
            }

            LOG.info("Executing silent request: HTTP " + httpMethod + " " + url);

            if (useAuth) {
                withAccessToken(new TokenCallback() {
                    @Override
                    public void onToken(String accessToken) throws IOException {
                        requestBuilder.addHeader("Authorization", accessToken);
                        okHttpClient.newCall(requestBuilder.build()).execute();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        LOG.log(Level.SEVERE, "HTTP request failed", t);
                    }
                });
            } else {
                okHttpClient.newCall(requestBuilder.build()).execute();
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "HTTP request failed", e);
        }
    }

    public void notificationDelivered(final Long notificationId, final String fcmToken) {
        LOG.info("Notification status update 'delivered': " + notificationId);

        restApiResource.notificationDelivered(realm, notificationId, fcmToken).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                LOG.info("Notification status update success");
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                LOG.log(Level.SEVERE, "Notification status update failed", t);
            }
        });
    }

    public void notificationAcknowledged(final Long notificationId, final String fcmToken, String acknowledgement) {

        LOG.info("Notification status update 'acknowledged' with value '" + acknowledgement + "': " + notificationId);

        if (TextUtils.isEmpty(acknowledgement)) {
            acknowledgement = "";
        }

        restApiResource.notificationAcknowledged(realm, notificationId, fcmToken, acknowledgement).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                LOG.info("Notification status update success");
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                LOG.log(Level.SEVERE, "Notification status update failed", t);
            }
        });
    }

    static OkHttpClient getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[]{};
                    }
                }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            OkHttpClient okHttpClient = builder.build();
            return okHttpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void withAccessToken(final TokenCallback callback) {
        Call<Map<String, String>> call = oauth2Service.refreshToken(realm, "refresh_token", "openremote", sharedPref.getString(refreshTokenKey, null));
        call.enqueue(new Callback<Map<String, String>>() {
            @Override
            public void onResponse(Call<Map<String, String>> call, Response<Map<String, String>> response) {
                if (response.isSuccessful()) {
                    if (response.body() != null) {
                        try {
                            LOG.fine("Access token successfully updated");
                            callback.onToken("Bearer " + response.body().get("access_token"));
                        } catch (IOException e) {
                            callback.onFailure(e);
                        }
                    } else {
                        callback.onFailure(new NullPointerException("No response body on update access token request"));
                    }
                } else {
                    callback.onFailure(new IllegalStateException("Unsuccessful response in update access token request: " + response.code()));
                }
            }

            @Override
            public void onFailure(Call call, Throwable t) {
                callback.onFailure(t);
            }
        });
    }
}
