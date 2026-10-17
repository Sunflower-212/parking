package org.zjmproject.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.zjmproject.pojo.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Service
public class PlateUploadService {
    private final LicensePlateRecognitionService ocr;
    private final Path dir;
    private final boolean enabled;
    public PlateUploadService(LicensePlateRecognitionService ocr,@Value("${parking.upload-dir:./uploads}") String dir,@Value("${parking.integrations.ocr:false}") boolean enabled) {this.ocr=ocr;this.dir=Path.of(dir).toAbsolutePath().normalize();this.enabled=enabled;}
    public String recognize(MultipartFile file) throws Exception {
        if(!enabled) throw new IllegalStateException("车牌识别尚未启用，请配置百度识别服务");
        validate(file);
        Files.createDirectories(dir);
        Path temp=Files.createTempFile(dir,"plate-",".image");
        try {
            file.transferTo(temp);
            if(javax.imageio.ImageIO.read(temp.toFile())==null) throw new IllegalArgumentException("文件不是有效图片");
            String plate=ocr.getPlate(temp.toString());
            if(plate==null || !plate.matches(User.LICENSE_PLATE_REGEX)) throw new IllegalArgumentException("未识别到有效车牌");
            return plate;
        } finally {Files.deleteIfExists(temp);}
    }
    public static void validate(MultipartFile file) {
        if(file==null || file.isEmpty() || file.getSize()>5*1024*1024 || !Set.of("image/jpeg","image/png").contains(file.getContentType()==null?"":file.getContentType()))
            throw new IllegalArgumentException("请上传不超过5MB的JPEG或PNG图片");
    }
}
