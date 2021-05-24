/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
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

package com.tencent.bkrepo.replication.job.replicator

import com.tencent.bkrepo.replication.config.DEFAULT_VERSION
import com.tencent.bkrepo.replication.job.ReplicaContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value

/**
 * 调度类任务同步器
 */
abstract class ScheduledReplicator : Replicator {

    @Value("\${spring.application.version}")
    private var version: String = DEFAULT_VERSION

    override fun replica(context: ReplicaContext) {
        checkVersion(context)
        doReplica(context)
    }

    /**
     * 同步具体逻辑，由子类实现
     */
    abstract fun doReplica(context: ReplicaContext)

    /**
     * 校验和远程集群版本是否一致
     */
    protected fun checkVersion(context: ReplicaContext) {
        with(context) {
            val remoteVersion = artifactReplicaClient.version().data.orEmpty()
            if (version != remoteVersion) {
                logger.warn("Local cluster's version[$version] is different from remote cluster[$remoteVersion].")
            }
        }
    }

    /**
     * 持久化任务
     */
    protected fun persistTaskLog(context: ReplicaContext) {
        context.taskRecord.status = context.status
        context.taskRecord.replicationProgress = context.progress
        taskLogRepository.save(context.taskRecord)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ScheduledReplicator::class.java)
    }
}
