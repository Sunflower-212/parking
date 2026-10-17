package org.zjmproject.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.zjmproject.interceptors.AuthenticationInterceptor;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @org.springframework.beans.factory.annotation.Autowired private org.zjmproject.interceptors.QueryValidationInterceptor validation;
    private final AuthenticationInterceptor authentication;
    public WebConfig(AuthenticationInterceptor authentication) {this.authentication=authentication;}
    @Override public void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(authentication).addPathPatterns("/**");registry.addInterceptor(validation).addPathPatterns("/**");}
}
