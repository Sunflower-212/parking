package org.zjmproject.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class FileUtils {

    public static String readFileOfTxt(String path){

        StringBuilder sb = new StringBuilder();

        try(BufferedReader bufferedReader = new BufferedReader(new FileReader(path))) {
            char[] buf = new char[1024];
            int len = -1;
            while ((len = bufferedReader.read(buf)) != -1) {
                sb.append(new String(buf, 0, len));
            }
        } catch (IOException e) {
            // 不把可能包含本地私密路径的异常原文写入控制台。
            org.slf4j.LoggerFactory.getLogger(FileUtils.class).atWarn().addKeyValue("event","file.read")
                .addKeyValue("result","FAILURE").addKeyValue("reasonCode",e.getClass().getSimpleName()).log("file_unavailable");
        }
        return sb.toString();
    }
}
