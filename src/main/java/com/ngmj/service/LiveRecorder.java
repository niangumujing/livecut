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

@Slf4j
@Service
public class LiveRecorder {

    private Process ffmpegProcess;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MinioUtil minioUtil;

    @Getter
    private String currentOutputPath;

    @Getter
    private String currentMinioUrl;

    @Value("${recorder.output-dir:/tmp/livecut}")
    private String outputDir;

    @Value("${recorder.live-url:}")
    private String liveUrl;

    @Value("${recorder.check-interval:30}")
    private int checkInterval;

    public LiveRecorder(MinioUtil minioUtil) {
        this.minioUtil = minioUtil;
    }

    public synchronized String startMonitoring() {
        if (monitoring.get()) {
            return "已在监听中";
        }
        if (recording.get()) {
            return "已在录制中";
        }

        monitoring.set(true);
        executor.submit(() -> {
            log.info("开始监听直播间: {}", liveUrl);
            while (monitoring.get()) {
                try {
                    boolean isLiving = checkLiveStatus();
                    log.info("直播间状态: {}", isLiving ? "直播中" : "未开播");

                    if (isLiving && !recording.get()) {
                        String streamUrl = getStreamUrl();
                        if (streamUrl != null) {
                            doStartRecording(streamUrl);
                        }
                    } else if (!isLiving && recording.get()) {
                        log.info("直播间已关闭，停止录制");
                        doStopRecording();
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
            log.info("监听已停止");
        });

        return "已开始监听直播间，等待开播...";
    }

    public synchronized String stopMonitoring() {
        monitoring.set(false);
        if (recording.get()) {
            doStopRecording();
            return "已停止监听和录制";
        }
        return "已停止监听";
    }

    public synchronized String forceStop() {
        monitoring.set(false);
        if (recording.get()) {
            doStopRecording();
            return "已强制停止录制";
        }
        return "当前没有录制任务";
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
                    .timeout(10000)
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
            File dir = new File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            currentOutputPath = outputDir + "/live_" + timestamp + ".mp3";

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

            if (currentOutputPath != null) {
                File outputFile = new File(currentOutputPath);
                if (outputFile.exists() && outputFile.length() > 0) {
                    log.info("录制完成: {} ({})", currentOutputPath, formatSize(outputFile.length()));
                    
                    currentMinioUrl = minioUtil.upload(outputFile, "audio/" + outputFile.getName());
                    log.info("已上传到MinIO: {}", currentMinioUrl);
                }
            }

        } catch (Exception e) {
            log.error("停止录制失败", e);
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
