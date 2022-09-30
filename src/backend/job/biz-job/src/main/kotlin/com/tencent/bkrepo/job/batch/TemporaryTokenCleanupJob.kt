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

package com.tencent.bkrepo.job.batch

import com.tencent.bkrepo.job.batch.base.DefaultContextJob
import com.tencent.bkrepo.job.batch.base.JobContext
import com.tencent.bkrepo.job.config.properties.TemporaryTokenCleanupJobProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.where
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * Temporary token 清理任务
 */
@Component
@EnableConfigurationProperties(TemporaryTokenCleanupJobProperties::class)
class TemporaryTokenCleanupJob(
    private val properties: TemporaryTokenCleanupJobProperties,
    private val mongoTemplate: MongoTemplate
) : DefaultContextJob(properties) {

    data class TemporaryToken(
        val expireDate: LocalDateTime? = null,
        val permits: Int? = null
    )

    override fun getLockAtMostFor(): Duration = Duration.ofHours(6)

    override fun doStart0(jobContext: JobContext) {
        val expireDate = LocalDateTime.now().minusDays(properties.reserveDays)
        val criteria = Criteria().orOperator(
            where(TemporaryToken::expireDate).lt(expireDate),
            where(TemporaryToken::permits).lt(1)
        )
        val query = Query.query(criteria)
        val result = mongoTemplate.remove(query, COLLECTION_NAME)
        jobContext.success.getAndAdd(result.deletedCount)
        jobContext.total.getAndAdd(result.deletedCount)
    }

    companion object {
        private const val COLLECTION_NAME = "temporary_token"
    }
}
