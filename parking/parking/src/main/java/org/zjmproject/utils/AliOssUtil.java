package org.zjmproject.utils;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import java.io.InputStream;
import java.net.URI;

public final class AliOssUtil {
    private AliOssUtil() {}
    public static String uploadFile(String name,InputStream input,String id,String secret,String bucket,String endpoint) throws Exception {
        if(id==null || id.isBlank() || secret==null || secret.isBlank() || bucket==null || bucket.isBlank() || endpoint==null || endpoint.isBlank()) throw new IllegalStateException("OSS配置不完整");
        URI address=URI.create(endpoint);
        if(!"https".equals(address.getScheme()) || address.getHost()==null) throw new IllegalArgumentException("OSS端点必须为HTTPS地址");
        com.aliyun.oss.ClientBuilderConfiguration configuration=new com.aliyun.oss.ClientBuilderConfiguration();
        configuration.setConnectionTimeout(3000);configuration.setSocketTimeout(15000);configuration.setMaxErrorRetry(1);
        OSS client=new OSSClientBuilder().build(endpoint,id,secret,configuration);
        try(InputStream stream=input) {
            client.putObject(bucket,name,stream);
            return "https://"+bucket+"."+address.getHost()+"/"+name;
        } catch(Exception e) {throw new IllegalStateException("头像上传失败，请检查OSS配置");}
        finally {client.shutdown();}
    }
}
