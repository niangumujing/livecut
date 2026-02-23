package com.ngmj.service;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ngmj.util.MinioUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class WhisperService {

    private final MinioUtil minioUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${whisper.command:whisper}")
    private String whisperCommand;

    @Value("${whisper.model:base}")
    private String model;

    @Value("${whisper.output-dir:/tmp/livecut}")
    private String outputDir;

    public WhisperService(MinioUtil minioUtil) {
        this.minioUtil = minioUtil;
    }

    public TranscribeResult transcribe(String audioPath, String batchName) {
        log.info("开始转写: {}", audioPath);

        File audioFile = new File(audioPath);
        if (!audioFile.exists()) {
            log.error("音频文件不存在: {}", audioPath);
            return TranscribeResult.fail("音频文件不存在");
        }

        try {
            String batchDir = outputDir + "/" + batchName;
            File batchDirFile = new File(batchDir);
            if (!batchDirFile.exists()) {
                batchDirFile.mkdirs();
            }

            List<String> command = new ArrayList<>();
            command.add(whisperCommand);
            command.add(audioPath);
            command.add("--model");
            command.add(model);
            command.add("--language");
            command.add("zh");
            command.add("--output_format");
            command.add("json");
            command.add("--output_dir");
            command.add(batchDir);

            log.info("执行命令: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.info("[Whisper] {}", line);
                }
            }

            int exitCode = process.waitFor();
            log.info("Whisper退出码: {}", exitCode);

            String jsonPath = batchDir + "/audio.json";
            File jsonFile = new File(jsonPath);
            
            if (!jsonFile.exists()) {
                log.error("转写结果文件不存在: {}", jsonPath);
                return TranscribeResult.fail("转写结果文件不存在");
            }

            TranscribeResult result = parseWhisperJson(jsonFile, batchName);

            String srtContent = generateSrt(result.getSegments());
            String srtPath = batchDir + "/subtitle.srt";
            FileUtil.writeString(srtContent, srtPath, StandardCharsets.UTF_8);
            log.info("生成SRT字幕: {}", srtPath);

            String srtMinioUrl = minioUtil.upload(new File(srtPath), batchName + "/subtitle.srt");
            result.setSrtPath(srtPath);
            result.setSrtMinioUrl(srtMinioUrl);

            String jsonMinioUrl = minioUtil.upload(jsonFile, batchName + "/transcript.json");
            result.setJsonMinioUrl(jsonMinioUrl);

            return result;

        } catch (Exception e) {
            log.error("转写失败", e);
            return TranscribeResult.fail("转写失败: " + e.getMessage());
        }
    }

    private TranscribeResult parseWhisperJson(File jsonFile, String batchName) {
        try {
            String content = FileUtil.readString(jsonFile, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(content);

            TranscribeResult result = new TranscribeResult();
            result.setSuccess(true);
            result.setText(root.path("text").asText(""));
            result.setBaseName(batchName);

            List<TranscribeSegment> segments = new ArrayList<>();
            JsonNode segmentsNode = root.path("segments");
            
            if (segmentsNode.isArray()) {
                for (JsonNode seg : segmentsNode) {
                    TranscribeSegment segment = new TranscribeSegment();
                    segment.setStart(seg.path("start").asDouble());
                    segment.setEnd(seg.path("end").asDouble());
                    segment.setText(seg.path("text").asText("").trim());
                    segments.add(segment);
                }
            }

            result.setSegments(segments);
            log.info("转写完成, 共 {} 个片段, 总字数: {}", segments.size(), result.getText().length());

            return result;

        } catch (Exception e) {
            log.error("解析Whisper JSON失败", e);
            return TranscribeResult.fail("解析失败: " + e.getMessage());
        }
    }

    private String generateSrt(List<TranscribeSegment> segments) {
        StringBuilder srt = new StringBuilder();
        int index = 1;

        for (TranscribeSegment seg : segments) {
            srt.append(index++).append("\n");
            srt.append(formatSrtTime(seg.getStart())).append(" --> ").append(formatSrtTime(seg.getEnd())).append("\n");
            srt.append(seg.getText()).append("\n\n");
        }

        return srt.toString();
    }

    private String formatSrtTime(double seconds) {
        int hours = (int) (seconds / 3600);
        int minutes = (int) ((seconds % 3600) / 60);
        int secs = (int) (seconds % 60);
        int millis = (int) ((seconds * 1000) % 1000);

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, secs, millis);
    }

    @Getter
    public static class TranscribeResult {
        private boolean success;
        private String message;
        private String text;
        private String baseName;
        private List<TranscribeSegment> segments;
        private String srtPath;
        private String srtMinioUrl;
        private String jsonMinioUrl;

        public static TranscribeResult fail(String message) {
            TranscribeResult result = new TranscribeResult();
            result.success = false;
            result.message = message;
            return result;
        }

        public void setSuccess(boolean success) { this.success = success; }
        public void setMessage(String message) { this.message = message; }
        public void setText(String text) { this.text = text; }
        public void setBaseName(String baseName) { this.baseName = baseName; }
        public void setSegments(List<TranscribeSegment> segments) { this.segments = segments; }
        public void setSrtPath(String srtPath) { this.srtPath = srtPath; }
        public void setSrtMinioUrl(String srtMinioUrl) { this.srtMinioUrl = srtMinioUrl; }
        public void setJsonMinioUrl(String jsonMinioUrl) { this.jsonMinioUrl = jsonMinioUrl; }
    }

    @Getter
    public static class TranscribeSegment {
        private double start;
        private double end;
        private String text;

        public void setStart(double start) { this.start = start; }
        public void setEnd(double end) { this.end = end; }
        public void setText(String text) { this.text = text; }
    }
}
