package com.xspaceagi.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.Assert;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.xspaceagi.interceptor.ApiKeyInterceptor;
import com.xspaceagi.interceptor.AuthInterceptor;
import com.xspaceagi.interceptor.HttpInterceptor;
import com.xspaceagi.interceptor.InternalApiInterceptor;
import com.xspaceagi.interceptor.TIdInterceptor;

import jakarta.annotation.Resource;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private HttpInterceptor httpInterceptor;

    @Autowired
    private AuthInterceptor jwtInterceptor;

    /**
     * tid统一标识处理
     */
    @Resource
    private TIdInterceptor tidInterceptor;

    @Resource
    private ApiKeyInterceptor apiKeyInterceptor;

    @Resource
    private InternalApiInterceptor internalApiInterceptor;

    @Value("${access.control.allow-origin}")
    private String accessControlAllowOrigin;

    @Override
    public void addInterceptors(InterceptorRegistry interceptorRegistry) {
        // 请求参数记录日志拦截器
        interceptorRegistry.addInterceptor(tidInterceptor).order(1).addPathPatterns("/api/**");
        interceptorRegistry.addInterceptor(httpInterceptor).order(21).addPathPatterns("/api/**");
        interceptorRegistry.addInterceptor(jwtInterceptor).order(22).addPathPatterns("/api/**");
        interceptorRegistry.addInterceptor(apiKeyInterceptor).order(23).addPathPatterns("/api/**");
        // 内部服务间 API：静态 X-Api-Key 认证（internal.api.key，未配置则一律拒绝）。
        // collab 命名空间由 Sidecar 共享密钥 X-Collab-Secret 在控制器内自行认证，须排除，
        // 否则两套密钥互相打架（RepoInternalAuthController 不发 X-Api-Key）。
        interceptorRegistry.addInterceptor(internalApiInterceptor).order(24)
                .addPathPatterns("/api/internal/**")
                .excludePathPatterns("/api/internal/collab/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 允许所有请求路径跨域访问
                .allowCredentials(true) // 是否携带Cookie，默认false
                .allowedHeaders("Origin", "X-Requested-With", "Content-Type", "Accept", "Authorization",
                        "Cache-Control", "Fragment", "X-Client-Type", InternalApiInterceptor.API_KEY_HEADER) // 允许的请求头类型
                .maxAge(3600) // 预检请求的缓存时间（单位：秒）
                .allowedMethods("HEAD", "GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的请求方法类型
                .allowedOriginPatterns(accessControlAllowOrigin); // 允许哪些域名进行跨域访问
    }

    @Bean
    public HttpMessageConverter<String> responseBodyConverter() {
        Charset defaultCharset = StandardCharsets.UTF_8;
        return new StringHttpMessageConverter(defaultCharset);
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(responseBodyConverter());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new ConverterFactory<String, IEnum>() {
            @Override
            public <T extends IEnum> Converter<String, T> getConverter(Class<T> targetType) {
                return source -> {
                    Assert.isTrue(targetType.isEnum(), () -> "the class what implements IEnum must be the enum type");
                    return Arrays.stream(targetType.getEnumConstants())
                            .filter(t -> String.valueOf(t.getValue()).equals(source))
                            .findFirst().orElse(null);
                };
            }
        });
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    }
}