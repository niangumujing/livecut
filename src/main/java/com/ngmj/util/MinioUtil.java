package com.ngmj.util;

import com.ngmj.config.MinioConfig;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioUtil {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public String upload(File file, String objectName) {
        try (InputStream is = new FileInputStream(file)) {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .object(objectName)
                    .stream(is, file.length(), -1)
                    .contentType(getContentType(file.getName()))
                    .build()
            );
            
            String url = minioConfig.getEndpoint() + "/" + 
                         minioConfig.getBucketName() + "/" + objectName;
            log.info("上传成功: {}", url);
            return url;
            
        } catch (Exception e) {
            log.error("上传失败: {}", e.getMessage());
            throw new RuntimeException("上传失败: " + e.getMessage(), e);
        }
    }

    public String upload(File file) {
        String objectName = System.currentTimeMillis() + "_" + file.getName();
        return upload(file, objectName);
    }

    private String getContentType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "mp4" -> "video/mp4";
            case "json" -> "application/json";
            default -> "application/octet-stream";
        };
    }
}
