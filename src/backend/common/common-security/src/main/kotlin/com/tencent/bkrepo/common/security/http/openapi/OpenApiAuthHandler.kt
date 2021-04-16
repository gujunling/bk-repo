/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.common.security.http.openapi

import com.tencent.bkrepo.common.api.constant.CharPool.COLON
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.api.constant.PLATFORM_KEY
import com.tencent.bkrepo.common.api.constant.USER_KEY
import com.tencent.bkrepo.common.security.constant.OPENAPI_AUTH_PREFIX
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import com.tencent.bkrepo.common.security.http.core.HttpAuthHandler
import com.tencent.bkrepo.common.security.http.credentials.AnonymousCredentials
import com.tencent.bkrepo.common.security.http.credentials.HttpAuthCredentials
import com.tencent.bkrepo.common.security.manager.AuthenticationManager
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.servlet.http.HttpServletRequest

/**
 * 平台账号认证
 */
open class OpenApiAuthHandler(private val authenticationManager: AuthenticationManager) : HttpAuthHandler {

    override fun extractAuthCredentials(request: HttpServletRequest): HttpAuthCredentials {
        val authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        return if (authorizationHeader.startsWith(OPENAPI_AUTH_PREFIX)) {
            try {
                val encodedCredentials = authorizationHeader.removePrefix(OPENAPI_AUTH_PREFIX)
                val decodedHeader = String(Base64.getDecoder().decode(encodedCredentials))
                val parts = decodedHeader.split(COLON)
                require(parts.size >= 2)
                OpenApiAuthCredentials(parts[0], parts[1])
            } catch (exception: IllegalArgumentException) {
                throw AuthenticationException("Authorization value [$authorizationHeader] is not a valid scheme.")
            }
        } else AnonymousCredentials()
    }

    override fun onAuthenticate(request: HttpServletRequest, authCredentials: HttpAuthCredentials): String {
        require(authCredentials is OpenApiAuthCredentials)
        val accessKey = authCredentials.accessKey
        val secretKey = authCredentials.secretKey
        val appId = authenticationManager.checkPlatformAccount(accessKey, secretKey)
        val appIdRequestHeader = request.getHeader(HttpHeaders.APP_ID).orEmpty()
        if (appId != appIdRequestHeader) throw AuthenticationException("appId $appIdRequestHeader check failed.")
        // openApi认证通过后直接赋予admin权限
        val userId = ADMIN
        request.setAttribute(PLATFORM_KEY, appId)
        request.setAttribute(USER_KEY, userId)
        return userId
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OpenApiAuthHandler::class.java)
        private const val ADMIN = "admin"
    }
}
