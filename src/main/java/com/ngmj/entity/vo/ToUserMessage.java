package com.ngmj.entity.vo;

import lombok.Data;

@Data
public class ToUserMessage {
    private String touser;
    private String msgtype;

    private Text text;
}
