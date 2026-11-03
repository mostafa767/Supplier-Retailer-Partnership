package com.equadis.portal.tenant;

import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers declare {@code Actor actor} as a parameter instead of reading headers.
 */
@Component
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Actor resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                 NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object actor = webRequest.getAttribute(ActorContextInterceptor.ACTOR_REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (actor instanceof Actor resolved) {
            return resolved;
        }
        throw new PortalException(ErrorCode.MISSING_IDENTITY, "No authenticated caller for this request");
    }
}
