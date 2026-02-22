package com.ngmj.config;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CustomMessageConverterConfig implements WebMvcConfigurer {
    @Bean
    public XmlMapper xmlMapper() {
        return new XmlMapper();
    }
}