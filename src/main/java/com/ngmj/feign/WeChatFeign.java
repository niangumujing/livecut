package com.ngmj.feign;

import com.ngmj.entity.dto.AccessTokenDto;
import com.ngmj.entity.dto.UserAppInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import static com.ngmj.common.CommonUtils.WECHAT_URL;

@FeignClient(name = "wechat",url = WECHAT_URL)
public interface WeChatFeign {
    @PostMapping("/cgi-bin/stable_token")
    AccessTokenDto getStableAccessToken(@RequestBody UserAppInfo userAppInfo);


//    @PostMapping("/cgi-bin/message/custom/send")
//    WeChatResponse sendMessage(@RequestParam("access_token") String accessToken, @RequestBody ToUserMessage toUserMessage);
}
