package com.tencent.bkrepo.common.security.http

import com.tencent.bkrepo.common.api.constant.ANONYMOUS_USER
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import com.tencent.bkrepo.common.security.http.credentials.AnonymousCredentials
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter
import javax.annotation.PostConstruct
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Http请求认证拦截器
 * 拦截器中使用了FeignClient，不能使用构造器注入，否则会有循环依赖错误
 */
class HttpAuthInterceptor : HandlerInterceptorAdapter() {

    @Autowired
    private lateinit var httpAuthSecurity: HttpAuthSecurity

    @PostConstruct
    private fun init() {
        if (httpAuthSecurity.getAuthHandlerList().isEmpty()) {
            logger.warn("No http auth handler was configured.")
        }
        httpAuthSecurity.getAuthHandlerList().forEach {
            logger.info("Initializing http auth handler[${it::class.simpleName}].")
        }
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val requestUri = request.requestURI
        httpAuthSecurity.getAuthHandlerList().forEach { authHandler ->
            val isLoginRequest = authHandler.getLoginEndpoint() == requestUri
            if (authHandler.getLoginEndpoint() == null || isLoginRequest) {
                try {
                    val authCredentials = authHandler.extractAuthCredentials(request)
                    if (authCredentials !is AnonymousCredentials) {
                        val userId = authHandler.onAuthenticate(request, authCredentials)
                        logger.debug("User[$userId] authenticate success by ${authHandler.javaClass.simpleName}.")
                        authHandler.onAuthenticateSuccess(request, response, userId)
                        request.setAttribute(USER_KEY, userId)
                        return true
                    } else if (isLoginRequest) {
                        throw AuthenticationException()
                    }
                } catch (authenticationException: AuthenticationException) {
                    authHandler.onAuthenticateFailed(request, response, authenticationException)
                    return false
                }
            }
        }
        // 没有合适的认证handler或为匿名用户
        if (httpAuthSecurity.isAnonymousEnabled()) {
            logger.debug("None of the auth handler authenticate success, set anonymous user.")
            request.setAttribute(USER_KEY, ANONYMOUS_USER)
            return true
        } else {
            throw AuthenticationException()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HttpAuthInterceptor::class.java)
    }
}