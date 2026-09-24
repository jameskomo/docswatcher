package dev.docswatcher.app.config;

import dev.docswatcher.app.auth.AccessInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AppProperties properties;
  private final AccessInterceptor access;

  public WebConfig(AppProperties properties, AccessInterceptor access) {
    this.properties = properties;
    this.access = access;
  }

  /** Per-organisation and per-repository authorisation for signed-in members. docs/adr/0008. */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(access).addPathPatterns("/api/**");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(access);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(properties.web().origin())
        .allowedMethods("GET", "POST", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type");
  }
}
