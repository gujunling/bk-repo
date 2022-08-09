/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.common.artifact.interceptor

import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.artifact.constant.DownloadInterceptorType
import com.tencent.bkrepo.common.artifact.interceptor.config.DownloadInterceptorProperties
import com.tencent.bkrepo.common.artifact.interceptor.impl.FilenameInterceptor
import com.tencent.bkrepo.common.artifact.interceptor.impl.MetadataInterceptor
import com.tencent.bkrepo.common.artifact.interceptor.impl.MobileInterceptor
import com.tencent.bkrepo.common.artifact.interceptor.impl.OfficeNetworkInterceptor
import com.tencent.bkrepo.common.artifact.interceptor.impl.WebInterceptor
import com.tencent.bkrepo.common.service.util.HeaderUtils
import org.slf4j.LoggerFactory

class DownloadInterceptorFactory(
    properties: DownloadInterceptorProperties
) {

    init {
        Companion.properties = properties
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DownloadInterceptorFactory::class.java)
        private lateinit var properties: DownloadInterceptorProperties
        private const val ANDROID_APP_USER_AGENT = "BKCI_APP"
        private const val IOS_APP_USER_AGENT = "com.apple.appstored"

        fun buildInterceptor(type: DownloadInterceptorType, rules: Map<String, Any>): DownloadInterceptor<*>? {
            val downloadSource = getDownloadSource()
            return when {
                type == DownloadInterceptorType.FILENAME -> FilenameInterceptor(rules)
                type == DownloadInterceptorType.METADATA -> MetadataInterceptor(rules)
                type == DownloadInterceptorType.WEB && type == downloadSource -> WebInterceptor(rules)
                type == DownloadInterceptorType.MOBILE && type == downloadSource -> MobileInterceptor(rules)
                type == DownloadInterceptorType.OFFICE_NETWORK -> OfficeNetworkInterceptor(rules, properties)
                else -> null
            }
        }

        private fun getDownloadSource(): DownloadInterceptorType {
            val userAgent = HeaderUtils.getHeader(HttpHeaders.USER_AGENT) ?: return DownloadInterceptorType.WEB
            logger.debug("download user agent: $userAgent")
            return when {
                userAgent.contains(ANDROID_APP_USER_AGENT) -> DownloadInterceptorType.MOBILE
                userAgent.contains(IOS_APP_USER_AGENT) -> DownloadInterceptorType.MOBILE
                else -> DownloadInterceptorType.WEB
            }
        }
    }
}