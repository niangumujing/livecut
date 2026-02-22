package com.ngmj.controller;

import com.ngmj.common.enums.MessageType;
import com.ngmj.entity.po.TextMessage;
import com.ngmj.service.LiveRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("wx")
public class WechatController {

    private final LiveRecorder liveRecorder;

    @GetMapping()
    public String validate(
            @RequestParam("signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {

        String[] arr = {"niangumujing", timestamp, nonce};
        Arrays.sort(arr);
        String sha1 = DigestUtils.sha1Hex(String.join("", arr));
        return sha1.equals(signature) ? echostr : "";
    }

    @PostMapping()
    public TextMessage receptMeg(@RequestBody TextMessage textMessage) {
        String content = textMessage.getContent();
        String reply;

        if (content == null) {
            reply = "请输入有效指令";
        } else if (content.trim().equals("1")) {
            reply = liveRecorder.startMonitoring();
        } else if (content.trim().equals("2")) {
            reply = liveRecorder.forceStop();
        } else {
            reply = "指令说明:\n1 - 开始监听直播间\n2 - 强制停止录制";
        }

        return getReturnMessage(textMessage, reply);
    }

    private TextMessage getReturnMessage(TextMessage textMessage, String finalContent) {
        TextMessage returnMessage = new TextMessage();
        returnMessage.setContent(finalContent);
        returnMessage.setToUserName(textMessage.getFromUserName());
        returnMessage.setFromUserName(textMessage.getToUserName());
        returnMessage.setCreateTime(System.currentTimeMillis());
        returnMessage.setMsgType(MessageType.TEXT.getType());
        return returnMessage;
    }
}
