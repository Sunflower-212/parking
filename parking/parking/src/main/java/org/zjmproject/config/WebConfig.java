package org.zjmproject.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.zjmproject.interceptors.AuthenticationInterceptor;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthenticationInterceptor authentication;
    public WebConfig(AuthenticationInterceptor authentication) {this.authentication=authentication;}
    @Override public void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(authentication).addPathPatterns("/**");}
}
