package org.zjmproject.service.impl;

import okhttp3.*;
import org.json.JSONObject;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.zjmproject.config.BaiduCloudConfig;
import org.zjmproject.service.LicensePlateRecognitionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

@Service
public class LicensePlateRecognitionServiceImpl implements LicensePlateRecognitionService {
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build();
    private final BaiduCloudConfig config;
    private final StringRedisTemplate redis;
    public LicensePlateRecognitionServiceImpl(BaiduCloudConfig config,StringRedisTemplate redis) {this.config=config;this.redis=redis;}
    @Override public String getPlate(String path) throws IOException {
        String token=redis.opsForValue().get("parking:baidu:access-token");
        if(token==null) {
            if(config.getApi_key()==null || config.getApi_key().isBlank() || config.getSecret_key()==null || config.getSecret_key().isBlank()) throw new IllegalStateException("百度识别密钥未配置");
            RequestBody form=new FormBody.Builder().add("grant_type","client_credentials").add("client_id",config.getApi_key()).add("client_secret",config.getSecret_key()).build();
            JSONObject response=call(new Request.Builder().url("https://aip.baidubce.com/oauth/2.0/token").post(form).build());
            token=response.optString("access_token","");
            if(token.isBlank()) throw new IllegalStateException("百度识别鉴权失败");
            redis.opsForValue().set("parking:baidu:access-token",token,Duration.ofSeconds(Math.max(1,response.optLong("expires_in",2592000)-300)));
        }
        RequestBody image=new FormBody.Builder().add("image",Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(path)))).add("multi_detect","false").build();
        HttpUrl url=HttpUrl.get("https://aip.baidubce.com/rest/2.0/ocr/v1/license_plate").newBuilder().addQueryParameter("access_token",token).build();
        JSONObject response=call(new Request.Builder().url(url).post(image).build());
        if(response.has("error_code")) {
            if(response.optInt("error_code")==110 || response.optInt("error_code")==111) redis.delete("parking:baidu:access-token");
            throw new IllegalStateException("车牌识别服务未返回有效结果，请稍后重试");
        }
        JSONObject words=response.optJSONObject("words_result");
        String plate=words==null?"":words.optString("number","").toUpperCase();
        if(plate.length()==7 || plate.length()==8) return plate.substring(0,2)+"-"+plate.substring(2);
        return null;
    }
    private JSONObject call(Request request) throws IOException {
        try(Response response=HTTP.newCall(request).execute()) {
            if(!response.isSuccessful() || response.body()==null) throw new IOException("车牌识别请求失败");
            return new JSONObject(response.body().string());
        }
    }
}
