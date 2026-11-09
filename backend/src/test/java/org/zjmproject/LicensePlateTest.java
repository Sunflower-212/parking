package org.zjmproject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.zjmproject.service.*;
import org.zjmproject.utils.LicensePlate;
import org.zjmproject.pojo.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LicensePlateTest {
 @TempDir Path temp;
 @Test void sameStructuralCasesAsFrontend() throws Exception {
  try(var source=getClass().getResourceAsStream("/license-plates.json")){
   for(var item:new ObjectMapper().readTree(source))assertEquals(!item.get("type").isNull(),item.get("plate").asText().matches(User.LICENSE_PLATE_REGEX),item.toString());
  }
 }
 @Test void ocrNormalizesAllTypesAndRejectsUnsupportedValuesWithoutLeavingFiles() throws Exception {
  var ocr=mock(LicensePlateRecognitionService.class);
  var service=new PlateUploadService(ocr,temp.toString(),true);
  var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
  for(String raw:new String[]{"皖h88888","京agA1234","沪B12345H"}){
   when(ocr.getPlate(anyString())).thenReturn(raw);
   assertEquals(LicensePlate.normalize(raw),service.recognize(new MockMultipartFile("file","plate.png","image/png",bytes.toByteArray())));
  }
  when(ocr.getPlate(anyString())).thenReturn("京AABC12");
  assertThrows(IllegalArgumentException.class,()->service.recognize(new MockMultipartFile("file","plate.png","image/png",bytes.toByteArray())));
  try(var files=Files.list(temp)){assertEquals(0,files.count());}
 }
}
