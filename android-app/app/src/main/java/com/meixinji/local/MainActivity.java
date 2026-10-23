package com.meixinji.local;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.BatteryManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.location.Location;
import android.location.LocationManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.widget.Toast;
import android.webkit.GeolocationPermissions;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {
    private static final String HOME="https://appassets.androidplatform.net/assets/www/index.html";
    private static final int LOCATION_REQUEST = 93;
    private WebView view;
    private WebViewAssetLoader loader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xfff0f1f3);
        getWindow().setNavigationBarColor(0xfff0f1f3);
        loader=new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        view=new WebView(this);
        view.setBackgroundColor(0xfff0f1f3);
        setContentView(view);
        WebSettings settings=view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        view.addJavascriptInterface(new RealityBridge(),"MeixinjiNative");
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin,hasLocation(),false);
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public void onPageFinished(WebView v,String url) {
                if(url.startsWith("https://appassets.androidplatform.net/")) installReality();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request) {
                String url=request.getUrl().toString();
                if(url.startsWith("https://appassets.androidplatform.net/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW,request.getUrl())); }
                catch(Exception e) { Toast.makeText(MainActivity.this,"无法打开外部链接",Toast.LENGTH_SHORT).show(); }
                return true;
            }
        });
        view.loadUrl(HOME);
    }
    private void installReality() {
        try(InputStream in=getAssets().open("reality.js")) {
            byte[] bytes=new byte[in.available()];
            int n=0; while(n<bytes.length){int k=in.read(bytes,n,bytes.length-n);if(k<0)break;n+=k;}
            String script=new String(bytes,0,n,StandardCharsets.UTF_8);
            view.evaluateJavascript(script,null);
        }catch(Exception ignored){}
    }
    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
          || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private JSONObject locationData() {
        JSONObject o=new JSONObject();
        try {
            o.put("granted",hasLocation());
            if (!hasLocation()) return o;
            LocationManager mgr=(LocationManager)getSystemService(LOCATION_SERVICE);
            Location best=null;
            for(String provider:mgr.getProviders(true)) {
                Location loc=mgr.getLastKnownLocation(provider);
                if(loc!=null && (best==null||loc.getTime()>best.getTime()))best=loc;
            }
            if(best!=null) {
                o.put("latitude",best.getLatitude());
                o.put("longitude",best.getLongitude());
                o.put("accuracyMeters",best.getAccuracy());
                o.put("timestamp",best.getTime());
            }
        }catch(Exception ignored){}
        return o;
    }
    private void runJS(String code) { runOnUiThread(()->view.evaluateJavascript(code,null)); }
    public class RealityBridge {
        @JavascriptInterface public String snapshot() {
            JSONObject j=new JSONObject();
            try {
                BatteryManager bat=(BatteryManager)getSystemService(BATTERY_SERVICE);
                int level=bat.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                Intent state=registerReceiver(null,new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int status=state==null?-1:state.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                j.put("batteryPercent",level);
                j.put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL);
                ConnectivityManager conn=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
                NetworkCapabilities cap=conn.getNetworkCapabilities(conn.getActiveNetwork());
                j.put("online",cap!=null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                j.put("networkType",cap==null?"offline":cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"wifi":cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"cellular":"other");
                j.put("time",System.currentTimeMillis());
                j.put("timezone",java.util.TimeZone.getDefault().getID());
                j.put("location",locationData());
            }catch(Exception ignored){}
            return j.toString();
        }
        @JavascriptInterface public void askForLocation() {
            runOnUiThread(()->{
                if(!hasLocation())requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_REQUEST);
                else runJS("window.dispatchEvent(new Event('meixinji-location-updated'))");
            });
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==LOCATION_REQUEST)runJS("window.dispatchEvent(new Event('meixinji-location-updated'))");
    }
    @Override public void onBackPressed() {
        if(view!=null && view.canGoBack())view.goBack();
        else if(view!=null)view.evaluateJavascript("window.dispatchEvent(new PopStateEvent('popstate'));",null);
        else super.onBackPressed();
    }
    @Override public void onDestroy(){if(view!=null)view.destroy();super.onDestroy();}
}
