package com.yupi.yupao.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        //设置允许跨域的路径。
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:3000","http://127.0.0.1:5173","http://127.0.0.1:8082","http://127.0.0.1:8083")
                //是否允许证书 不在沉默
                .allowCredentials(true)
                //设置允许的方法
                .allowedMethods("*")
                //跨域允许时间
                .maxAge(3600);
    }
}
