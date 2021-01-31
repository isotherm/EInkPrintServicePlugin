package com.alpaxo.einkprintserviceplugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.android.volley.AuthFailureError;
import com.android.volley.Cache;
import com.android.volley.Network;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.BasicNetwork;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.NoCache;
import com.sketchproject.infogue.modules.VolleyMultipartRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class VssApi {
    private static final String LOG_TAG = "VssApi";

    private final String server;
    private final String key;
    private final String secret;
    private final RequestQueue requestQueue;

    public VssApi(Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String scheme = prefs.getBoolean("secure", true) ? "https" : "http";
        String host = prefs.getString("host", "master.joan.vnct.xyz");
        String port = prefs.getString("port", "443");
        this.server = scheme + "://" + host + ":" + port;
        this.key = prefs.getString("key", "");
        this.secret = prefs.getString("secret", "");

        RequestQueue requestQueue;
        Cache cache = new NoCache();
        Network network = new BasicNetwork(new HurlStack());
        this.requestQueue = new RequestQueue(cache, network);
        this.requestQueue.start();
    }

    public void getDevicesCollection(Response.Listener<JSONArray> listener, Response.ErrorListener errorListener) {
        String path = "/api/device/";
        Log.d(LOG_TAG, path);
        JsonArrayRequest request = new JsonArrayRequest(Request.Method.GET, this.server + path,
                null, listener, errorListener) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                return VssApi.this.getHeaders(this, path);
            }
        };
        this.requestQueue.add(request);
    }

    public void putImage(String uuid, Bitmap bitmap, Response.Listener<NetworkResponse> listener,
                         Response.ErrorListener errorListener) {
        String path = "/backend/" + uuid;
        Log.d(LOG_TAG, path);
        VolleyMultipartRequest request = new VolleyMultipartRequest(Request.Method.PUT,
                this.server + path, listener, errorListener) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                return VssApi.this.getHeaders(this, path);
            }

            @Override
            protected Map<String, DataPart> getByteData() {
                Map<String, DataPart> params = new HashMap<>();
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                bitmap.recycle();
                try {
                    byte[] bytes = stream.toByteArray();
                    params.put("image", new DataPart("image.png", bytes, "image/png"));
                    stream.close();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "", e);
                }
                return params;
            }
        };
        this.requestQueue.add(request);
    }

    private Map<String, String> getHeaders(Request request, String path) {
        String method = methodToString(request.getMethod());
        String contentType = request.getBodyContentType();
        String date = VssApi.rfc1123Date(System.currentTimeMillis());
        String dataToHash = method + "\n\n" + contentType + "\n" + date + "\n" + path;

        Map<String, String> headers = new HashMap<String, String>();
        byte[] hash;
        try {
            hash = VssApi.HmacSHA256(dataToHash, this.secret);
        } catch(Exception e) {
            Log.e(LOG_TAG, "", e);
            return headers;
        }
        headers.put("Content-type", contentType);
        headers.put("Date", date);
        headers.put("Authorization", this.key + ":" + Base64.encodeToString(hash, Base64.NO_WRAP));
        return headers;
    }

    private static String methodToString(int method) {
        switch(method) {
            case Request.Method.GET:
                return "GET";
            case Request.Method.POST:
                return "POST";
            case Request.Method.PUT:
                return "PUT";
            default:
                return "";
        }
    }

    private static String rfc1123Date(long millis) {
        String pattern = "EEE, dd MMM yyyy HH:mm:ss zzz";
        DateFormat df = new SimpleDateFormat(pattern, Locale.ENGLISH);
        return df.format(new Date(millis));
    }

    private static byte[] HmacSHA256(String data, String secret) throws Exception {
        String algorithm = "HmacSHA256";
        String encoding = "UTF-8";
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(encoding), algorithm));
        return mac.doFinal(data.getBytes(encoding));
    }
}
