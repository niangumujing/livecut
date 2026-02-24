package com.ngmj.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.common.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class AliyunService {

    @Value("${aliyun.api-key}")
    String apiKey;
    @Value("${aliyun.model}")
    String model;


    Generation gen = new Generation();

    private GenerationParam buildGenerationParam(List<Message> messages) {
        return GenerationParam.builder()
                // 若没有配置环境变量，请用百炼API Key将下行替换为：.apiKey("sk-xxx")
                .apiKey(apiKey)
                .model(model)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
    }
    public String callWithMessage(List<Message> messages) {
        GenerationParam param = buildGenerationParam(messages);
        try {
            var result = gen.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("call aliyun error", e);
        }
        return null;
    }
}