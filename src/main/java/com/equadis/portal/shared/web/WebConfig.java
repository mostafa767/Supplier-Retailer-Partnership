package com.equadis.portal.shared.web;

import com.equadis.portal.tenant.ActorArgumentResolver;
import com.equadis.portal.tenant.ActorContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers the request pipeline. In order, every API call goes through:
 * <ol>
 *     <li>{@link RequestTracingFilter} - correlation id, request log line;</li>
 *     <li>{@link RequestSizeLimitFilter} - request bodies limited to 1 MB (imports: 50 MB);</li>
 *     <li>{@link ActorContextInterceptor} - identity and marketplace (tenant);</li>
 *     <li>the controller, which receives the {@code Actor} via {@link ActorArgumentResolver};</li>
 *     <li>on any exception: {@code GlobalExceptionHandler} - one error format.</li>
 * </ol>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ActorContextInterceptor actorContextInterceptor;
    private final ActorArgumentResolver actorArgumentResolver;

    public WebConfig(ActorContextInterceptor actorContextInterceptor, ActorArgumentResolver actorArgumentResolver) {
        this.actorContextInterceptor = actorContextInterceptor;
        this.actorArgumentResolver = actorArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(actorContextInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(actorArgumentResolver);
    }
}
