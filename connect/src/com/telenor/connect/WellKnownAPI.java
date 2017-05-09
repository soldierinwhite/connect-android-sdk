package com.telenor.connect;

import com.google.gson.annotations.SerializedName;

import retrofit.Callback;
import retrofit.http.GET;
import retrofit.http.Headers;

public interface WellKnownAPI {

    public static final String OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration";

    @Headers("Content-Type: application/json")
    @GET("/")
    void getWellKnownConfig(Callback<WellKnownConfig> callback);

    class WellKnownConfig {
        @SerializedName("issuer")
        private String issuer;

        public String getIssuer() {
            return issuer;
        }
    }
}