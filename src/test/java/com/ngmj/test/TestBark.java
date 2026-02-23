package com.ngmj.test;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest
public class TestBark {

    @Test
    public void sendNotification() {
        String title = "录制完成";
        String content = "文件已保存: https://www.ggsuper.com.cn/upload/2023/01/05/20230105164207.mp4";
        String url = "https://www.ggsuper.com.cn/upload/2023/01/05/20230105164207.mp4";
        
        try {
            String jsonBody = String.format(
                    "{\"title\":\"%s\",\"msg\":\"%s\",\"url\":\"%s\",\"token\":\"niangumujin\",\"issecure\":0,\"sender\":\"livecut\"}",
                    title, content, url
            );

            log.info("发送JSON: {}", jsonBody);

            HttpResponse response = HttpRequest.post("http://www.ggsuper.com.cn/push/api/v1/sendMsg3_New.php")
                    .header("Content-Type", "application/json")
                    .body(jsonBody)
                    .timeout(10000)
                    .execute();
            log.info("推送通知: {} - {}, 响应: {}", title, content, response.body());
        } catch (Exception e) {
            log.error("推送通知失败: {}", e.getMessage());
        }
    }

}
