package com.ngmj.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.ngmj.util.MinioUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class LiveRecorder {

    private Process ffmpegProcess;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MinioUtil minioUtil;
    private final WhisperService whisperService;
    private final AtomicInteger failCount = new AtomicInteger(0);

    @Getter
    private String currentOutputPath;

    @Getter
    private String currentBatchName;

    @Getter
    private String currentMinioUrl;

    @Getter
    private String currentSrtUrl;

    @Getter
    private String currentTranscriptText;

    @Value("${recorder.output-dir:/tmp/livecut}")
    private String outputDir;

    @Value("${recorder.live-url:}")
    private String liveUrl;

    @Value("${recorder.check-interval:30}")
    private int checkInterval;

    @Value("${recorder.max-fail-count:3}")
    private int maxFailCount;

    @Value("${bark.url:http://www.ggsuper.com.cn/push/api/v1/sendMsg_New.php?token=niangumujin}")
    private String barkUrl;

    public LiveRecorder(MinioUtil minioUtil, WhisperService whisperService) {
        this.minioUtil = minioUtil;
        this.whisperService = whisperService;
    }

    public synchronized String startMonitoring() {
        if (monitoring.get()) {
            return "已在监听中";
        }
        if (recording.get()) {
            return "已在录制中";
        }

        monitoring.set(true);
        failCount.set(0);
        
        executor.submit(() -> {
            log.info("开始监听直播间: {}", liveUrl);
            log.info("最大失败次数: {}", maxFailCount);
            
            while (monitoring.get()) {
                try {
                    boolean isLiving = checkLiveStatusWithRetry();
                    log.info("直播间状态: {} {}", isLiving ? "直播中" : "未开播", 
                            failCount.get() > 0 ? "(失败次数: " + failCount.get() + ")" : "");

                    if (isLiving && !recording.get()) {
                        String streamUrl = getStreamUrl();
                        if (streamUrl != null) {
                            doStartRecording(streamUrl);
                        }
                        failCount.set(0);
                    } else if (!isLiving && recording.get()) {
                        if (failCount.get() >= maxFailCount) {
                            log.info("连续{}次检测失败，停止录制", failCount.get());
                            doStopRecording();
                            failCount.set(0);
                        } else {
                            log.warn("检测失败，继续录制等待恢复... ({}/{})", failCount.get(), maxFailCount);
                        }
                    }

                    Thread.sleep(checkInterval * 1000L);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log.error("监听异常: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            
            if (recording.get()) {
                log.info("监听停止，保存当前录制");
                doStopRecording();
            }
            log.info("监听已停止");
        });

        return "已开始监听直播间，等待开播...";
    }

    public synchronized String stopMonitoring() {
        if (!monitoring.get()) {
            return "当前没有监听任务";
        }
        
        monitoring.set(false);
        if (recording.get()) {
            doStopRecording();
            return "已停止监听和录制";
        }
        return "未开播，停止等待";
    }

    public synchronized String forceStop() {
        if (!monitoring.get()) {
            return "当前没有任务";
        }
        
        monitoring.set(false);
        if (recording.get()) {
            doStopRecording();
            return "已强制停止录制";
        }
        return "未开播，停止等待";
    }

    private boolean checkLiveStatusWithRetry() {
        for (int i = 0; i < 3; i++) {
            try {
                HttpResponse response = HttpRequest.get(liveUrl)
                        .timeout(15000)
                        .execute();
                String body = response.body();
                boolean isLiving = body.contains("live/stream") || 
                        body.contains("flv") || 
                        body.contains("m3u8") ||
                        body.contains("\"live_status\":1");
                
                if (isLiving) {
                    failCount.set(0);
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.warn("检查直播状态失败(尝试{}/3): {}", i + 1, e.getMessage());
                if (i < 2) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
        
        failCount.incrementAndGet();
        return false;
    }

    private boolean checkLiveStatus() {
        try {
            HttpResponse response = HttpRequest.get(liveUrl)
                    .timeout(10000)
                    .execute();
            String body = response.body();
            return body.contains("live/stream") || 
                   body.contains("flv") || 
                   body.contains("m3u8") ||
                   body.contains("\"live_status\":1");
        } catch (Exception e) {
            log.error("检查直播状态失败: {}", e.getMessage());
            return false;
        }
    }

    private String getStreamUrl() {
        try {
            HttpResponse response = HttpRequest.get(liveUrl)
                    .timeout(15000)
                    .execute();
            String body = response.body();
            log.info("页面内容长度: {}", body.length());
            
            int flvIdx = body.indexOf(".flv");
            if (flvIdx > 0) {
                int start = body.lastIndexOf("http", flvIdx);
                int end = body.indexOf("\"", flvIdx);
                if (start > 0 && end > start) {
                    String streamUrl = body.substring(start, end)
                            .replace("\\u0026", "&")
                            .replace("\\", "")
                            .trim();
                    log.info("提取到流地址: {}", streamUrl);
                    return streamUrl;
                }
            }
            log.info("未提取到流地址，使用原始URL: {}", liveUrl);
            return liveUrl;
        } catch (Exception e) {
            log.error("获取流地址失败: {}", e.getMessage());
            return liveUrl;
        }
    }

    private void doStartRecording(String streamUrl) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String dateStr = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String timeStr = now.format(DateTimeFormatter.ofPattern("HHmmss"));
            currentBatchName = dateStr + "/live_" + timeStr;
            
            String batchDir = outputDir + "/" + currentBatchName;
            currentOutputPath = batchDir + "/audio.mp3";
            
            File dir = new File(batchDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String[] command = {
                "ffmpeg",
                "-i", streamUrl,
                "-vn",
                "-acodec", "libmp3lame",
                "-ab", "128k",
                "-ar", "44100",
                "-ac", "2",
                currentOutputPath,
                "-y"
            };

            log.info("开始录制: {}", streamUrl);
            log.info("输出文件: {}", currentOutputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            ffmpegProcess = pb.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(ffmpegProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[FFmpeg] {}", line);
                    }
                } catch (Exception e) {
                    log.error("读取FFmpeg输出失败", e);
                }
            }, "ffmpeg-reader").start();

            Thread.sleep(3000);

            if (ffmpegProcess.isAlive()) {
                recording.set(true);
                log.info("录制已启动");
            } else {
                int exitCode = ffmpegProcess.exitValue();
                log.error("FFmpeg启动失败，退出码: {}，流地址: {}", exitCode, streamUrl);
            }

        } catch (Exception e) {
            log.error("启动录制失败", e);
        }
    }

    private void doStopRecording() {
        try {
            if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
                ffmpegProcess.destroy();
                Thread.sleep(1000);
                if (ffmpegProcess.isAlive()) {
                    ffmpegProcess.destroyForcibly();
                }
            }

            recording.set(false);

            final String audioPath = currentOutputPath;
            
            if (audioPath != null) {
                File outputFile = new File(audioPath);
                if (outputFile.exists() && outputFile.length() > 0) {
                    log.info("录制完成: {} ({})", audioPath, formatSize(outputFile.length()));
                    
                    executor.submit(() -> processAudioAsync(audioPath));
                } else {
                    log.warn("录制文件不存在或为空: {}", audioPath);
                    sendNotification("录制异常", "文件不存在或为空");
                }
            }

        } catch (Exception e) {
            log.error("停止录制失败", e);
            sendNotification("录制失败", e.getMessage());
        }
    }

    private void processAudioAsync(String audioPath) {
        try {
            File outputFile = new File(audioPath);
            if (!outputFile.exists()) {
                log.error("音频文件不存在: {}", audioPath);
                return;
            }

            log.info("开始语音转写...");
            sendNotification("转写中", "正在将音频转为文字...");
            
            String batchName = currentBatchName;
            WhisperService.TranscribeResult transcribeResult = whisperService.transcribe(audioPath, batchName);

            currentMinioUrl = minioUtil.upload(outputFile, batchName + "/audio.mp3");
            log.info("已上传音频到MinIO: {}", currentMinioUrl);
            
            if (transcribeResult.isSuccess()) {
                currentSrtUrl = transcribeResult.getSrtMinioUrl();
                currentTranscriptText = transcribeResult.getText();
                
                String msg = String.format("转写完成，共%d字", currentTranscriptText.length());
                log.info(msg);
                sendNotification("转写完成", msg + " " + currentSrtUrl);
            } else {
                log.error("转写失败: {}", transcribeResult.getMessage());
                sendNotification("转写失败", transcribeResult.getMessage());
            }
        } catch (Exception e) {
            log.error("处理音频失败", e);
            sendNotification("处理失败", e.getMessage());
        }
    }

    private void sendNotification(String title, String content) {
        try {
            String jsonBody = String.format(
                "{\"title\":\"%s\",\"msg\":\"%s\",\"url\":\"%s\",\"token\":\"niangumujin\",\"issecure\":0,\"sender\":\"livecut\"}",
                title, content, currentMinioUrl != null ? currentMinioUrl : ""
            );
            
            HttpResponse response = HttpRequest.post(barkUrl)
                    .header("Content-Type", "application/json")
                    .body(jsonBody)
                    .timeout(10000)
                    .execute();
            log.info("推送通知: {} - {}, 响应: {}", title, content, response.body());
        } catch (Exception e) {
            log.error("推送通知失败: {}", e.getMessage());
        }
    }

    public boolean isMonitoring() {
        return monitoring.get();
    }

    public boolean isRecording() {
        return recording.get();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
