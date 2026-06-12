package com.linghu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Web MVC 配置：映射本地 uploads 目录为静态资源
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 将运行目录下的 uploads/ 文件夹映射到 /uploads/** 请求路径
     * 上传的商品图片可通过 http://host:8080/uploads/xxx.jpg 访问
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 固定使用 /workspace/uploads/ 目录，避免因启动目录不同导致路径偏移
        String uploadDir = "/workspace/uploads/";
        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir);
    }
}
