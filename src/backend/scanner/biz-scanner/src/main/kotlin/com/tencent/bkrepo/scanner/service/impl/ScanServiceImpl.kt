/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2022 THL A29 Limited, a Tencent company.  All rights reserved.
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

package com.tencent.bkrepo.scanner.service.impl

import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.mongo.dao.util.Pages
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.scanner.pojo.scanner.SubScanTaskStatus
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.repository.api.NodeClient
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.scanner.component.manager.ScanExecutorResultManager
import com.tencent.bkrepo.scanner.dao.FileScanResultDao
import com.tencent.bkrepo.scanner.dao.ScanTaskDao
import com.tencent.bkrepo.scanner.dao.SubScanTaskDao
import com.tencent.bkrepo.scanner.exception.ScanTaskNotFoundException
import com.tencent.bkrepo.scanner.metrics.ScannerMetrics
import com.tencent.bkrepo.scanner.model.TScanTask
import com.tencent.bkrepo.scanner.model.TSubScanTask
import com.tencent.bkrepo.scanner.pojo.ScanTask
import com.tencent.bkrepo.scanner.pojo.ScanTaskStatus
import com.tencent.bkrepo.scanner.pojo.ScanTriggerType
import com.tencent.bkrepo.scanner.pojo.SubScanTask
import com.tencent.bkrepo.scanner.pojo.request.FileScanResultDetailRequest
import com.tencent.bkrepo.scanner.pojo.request.FileScanResultOverviewRequest
import com.tencent.bkrepo.scanner.pojo.request.ReportResultRequest
import com.tencent.bkrepo.scanner.pojo.request.ScanRequest
import com.tencent.bkrepo.scanner.pojo.request.ScanTaskQuery
import com.tencent.bkrepo.scanner.pojo.response.FileScanResultDetail
import com.tencent.bkrepo.scanner.pojo.response.FileScanResultOverview
import com.tencent.bkrepo.scanner.service.ScanService
import com.tencent.bkrepo.scanner.service.ScannerService
import com.tencent.bkrepo.scanner.task.ScanTaskScheduler
import com.tencent.bkrepo.scanner.utils.Converter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter.ISO_DATE_TIME

@Service
class ScanServiceImpl @Autowired constructor(
    private val nodeClient: NodeClient,
    private val repositoryClient: RepositoryClient,
    private val scanTaskDao: ScanTaskDao,
    private val subScanTaskDao: SubScanTaskDao,
    private val fileScanResultDao: FileScanResultDao,
    private val scannerService: ScannerService,
    private val scanTaskScheduler: ScanTaskScheduler,
    private val scanExecutorResultManagers: Map<String, ScanExecutorResultManager>,
    private val scannerMetrics: ScannerMetrics
) : ScanService {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var self: ScanServiceImpl

    @Transactional(rollbackFor = [Throwable::class])
    override fun scan(scanRequest: ScanRequest, triggerType: ScanTriggerType): ScanTask {
        val userId = SecurityUtils.getUserId()
        with(scanRequest) {
            val scanner = scannerService.get(scanner)
            val now = LocalDateTime.now()
            val scanTask = scanTaskDao.save(
                TScanTask(
                    createdBy = userId,
                    createdDate = now,
                    lastModifiedBy = userId,
                    lastModifiedDate = now,
                    rule = rule?.toJsonString(),
                    triggerType = triggerType.name,
                    status = ScanTaskStatus.PENDING.name,
                    total = 0L,
                    scanning = 0L,
                    failed = 0L,
                    scanned = 0L,
                    scanner = scanner.name,
                    scannerType = scanner.type,
                    scannerVersion = scanner.version,
                    scanResultOverview = null
                )
            ).run { Converter.convert(this) }
            scannerMetrics.incTaskCountAndGet(ScanTaskStatus.PENDING)
            scanTaskScheduler.schedule(scanTask)
            logger.info("create scan task[${scanTask.taskId}] success")
            return scanTask
        }
    }

    override fun task(taskId: String): ScanTask {
        return scanTaskDao.findById(taskId)?.let {
            Converter.convert(it)
        } ?: throw ScanTaskNotFoundException(taskId)
    }

    override fun tasks(scanTaskQuery: ScanTaskQuery, pageLimit: PageLimit): Page<ScanTask> {
        val criteria = Criteria()
        with(scanTaskQuery) {
            scanner?.let { criteria.and(TScanTask::scanner.name).isEqualTo(it) }
            scannerType?.let { criteria.and(TScanTask::scannerType.name).isEqualTo(it) }
            status?.let { criteria.and(TScanTask::status.name).isEqualTo(it) }
        }
        val query = Query(criteria)
        val count = scanTaskDao.count(query)
        query.with(Pages.ofRequest(pageLimit.pageNumber, pageLimit.pageSize))
        val scanTasks = scanTaskDao.find(query).map { Converter.convert(it) }
        return Page(pageLimit.pageNumber, pageLimit.pageSize, count, scanTasks)
    }

    @Transactional(rollbackFor = [Throwable::class])
    override fun reportResult(reportResultRequest: ReportResultRequest) {
        with(reportResultRequest) {
            logger.info("report result, parentTask[$parentTaskId], subTask[$subTaskId]")
            val subScanTask = subScanTaskDao.findById(subTaskId) ?: return
            // 更新扫描任务结果
            val updateScanTaskResultSuccess = updateScanTaskResult(
                subScanTask, scanExecutorResult.scanStatus, parentTaskId, scanExecutorResult.overview
            )

            // 没有扫描任务被更新或子扫描任务失败时直接返回
            if (!updateScanTaskResultSuccess || scanExecutorResult.scanStatus != SubScanTaskStatus.SUCCESS.name) {
                return
            }

            // 统计任务耗时
            scannerMetrics.record(
                scanExecutorResult.fileType, subScanTask.size, subScanTask.scanner,
                scanExecutorResult.startTimestamp, scanExecutorResult.finishedTimestamp
            )

            // 更新文件扫描结果
            val scanner = scannerService.get(subScanTask.scanner)
            fileScanResultDao.upsertResult(
                subScanTask.credentialsKey,
                subScanTask.sha256,
                parentTaskId,
                scanner,
                scanExecutorResult.overview,
                toLocalDateTime(scanExecutorResult.startTimestamp),
                toLocalDateTime(scanExecutorResult.finishedTimestamp)
            )

            // 保存详细扫描结果
            val resultManager = scanExecutorResultManagers[scanner.type]
            resultManager?.save(subScanTask.credentialsKey, subScanTask.sha256, scanner.name, scanExecutorResult)
        }
    }

    /**
     * 更新任务状态
     *
     * @return 是否更新成功
     */
    @Transactional(rollbackFor = [Throwable::class])
    fun updateScanTaskResult(
        subTask: TSubScanTask,
        resultSubTaskStatus: String,
        parentTaskId: String,
        overview: Map<String, Any?>
    ): Boolean {
        val subTaskId = subTask.id!!
        // 任务已扫描过，重复上报直接返回
        if (subScanTaskDao.deleteById(subTaskId).deletedCount != 1L) {
            return false
        }
        scannerMetrics.subtaskStatusChange(
            SubScanTaskStatus.valueOf(subTask.status), SubScanTaskStatus.valueOf(resultSubTaskStatus)
        )
        logger.info("updating scan result, parentTask[$parentTaskId], subTask[$subTaskId][$resultSubTaskStatus]")

        // 更新父任务扫描结果
        val scanSuccess = resultSubTaskStatus == SubScanTaskStatus.SUCCESS.name
        scanTaskDao.updateScanResult(parentTaskId, 1, overview, scanSuccess)
        if (scanTaskDao.taskFinished(parentTaskId).modifiedCount == 1L) {
            scannerMetrics.incTaskCountAndGet(ScanTaskStatus.FINISHED)
            logger.info("scan finished, task[$parentTaskId]")
        }
        return true
    }

    override fun pull(): SubScanTask? {
        return pullSubScanTask()?.let {
            return Converter.convert(it, scannerService.get(it.scanner))
        }
    }

    @Transactional(rollbackFor = [Throwable::class])
    override fun updateSubScanTaskStatus(subScanTaskId: String, subScanTaskStatus: String): Boolean {
        if (subScanTaskStatus == SubScanTaskStatus.EXECUTING.name) {
            val subScanTask = subScanTaskDao.findById(subScanTaskId) ?: return false
            // 已经是正在执行的状态了，直接返回
            if (subScanTask.status == SubScanTaskStatus.EXECUTING.name) {
                return false
            }

            val oldStatus = SubScanTaskStatus.valueOf(subScanTask.status)
            val updateResult = subScanTaskDao.updateStatus(
                subScanTaskId, SubScanTaskStatus.EXECUTING, oldStatus, subScanTask.lastModifiedDate
            )
            val modified = updateResult.modifiedCount == 1L
            if (modified) {
                scannerMetrics.subtaskStatusChange(oldStatus, SubScanTaskStatus.EXECUTING)
                // 更新任务实际开始扫描的时间
                scanTaskDao.updateStartedDateTimeIfNotExists(subScanTask.parentScanTaskId, LocalDateTime.now())
            }
            return modified
        }
        return false
    }

    // TODO 添加消息队列后开启定时任务
    //@Scheduled(fixedDelay = FIXED_DELAY, initialDelay = FIXED_DELAY)
    fun enqueueTimeoutSubTask() {
        val subtask = pullSubScanTask() ?: return
        val enqueued = scanTaskScheduler.schedule(Converter.convert(subtask, scannerService.get(subtask.scanner)))
        if (!enqueued) {
            return
        }
        val oldStatus = SubScanTaskStatus.valueOf(subtask.status)
        val updateResult =
            subScanTaskDao.updateStatus(subtask.id!!, SubScanTaskStatus.ENQUEUED, oldStatus, subtask.lastModifiedDate)
        if (updateResult.modifiedCount == 1L) {
            scannerMetrics.subtaskStatusChange(oldStatus, SubScanTaskStatus.ENQUEUED)
        }
    }

    @Scheduled(fixedDelay = FIXED_DELAY, initialDelay = FIXED_DELAY)
    @Transactional(rollbackFor = [Throwable::class])
    fun enqueueTimeoutTask() {
        val task = scanTaskDao.timeoutTask(DEFAULT_TASK_EXECUTE_TIMEOUT_SECONDS)
        if (task != null && scanTaskDao.resetTask(task.id!!, task.lastModifiedDate).modifiedCount == 1L) {
            scannerMetrics.taskStatusChange(ScanTaskStatus.valueOf(task.status), ScanTaskStatus.PENDING)
            scanTaskScheduler.schedule(Converter.convert(task))
        }
    }

    fun pullSubScanTask(): TSubScanTask? {
        var count = 0
        while (true) {
            // 优先返回待执行任务，再返回超时任务
            val task = subScanTaskDao.firstTaskByStatusIn(listOf(SubScanTaskStatus.CREATED.name))
                ?: subScanTaskDao.firstTimeoutTask(DEFAULT_TASK_EXECUTE_TIMEOUT_SECONDS)
                ?: return null

            // 处于执行中的任务，而且任务执行了最大允许的次数，直接设置为失败
            if (task.status == SubScanTaskStatus.EXECUTING.name && task.executedTimes >= DEFAULT_MAX_EXECUTE_TIMES) {
                logger.info("subTask[${task.id}] of parentTask[${task.parentScanTaskId}] exceed max execute times")
                self.updateScanTaskResult(task, SubScanTaskStatus.TIMEOUT.name, task.parentScanTaskId, emptyMap())
                continue
            }

            // 更新任务，更新成功说明任务没有被其他扫描执行器拉取过，可以返回
            val oldStatus = SubScanTaskStatus.valueOf(task.status)
            val updateResult =
                subScanTaskDao.updateStatus(task.id!!, SubScanTaskStatus.PULLED, oldStatus, task.lastModifiedDate)
            if (updateResult.modifiedCount != 0L) {
                scannerMetrics.subtaskStatusChange(oldStatus, SubScanTaskStatus.PULLED)
                return task
            }

            // 超过最大允许重试次数后说明当前冲突比较严重，有多个扫描器在拉任务，直接返回null
            if (++count >= MAX_RETRY_PULL_TASK_TIMES) {
                return null
            }
        }
    }

    override fun resultOverview(request: FileScanResultOverviewRequest): List<FileScanResultOverview> {
        with(request) {
            val subScanTaskMap = subScanTaskDao
                .findByCredentialsKeyAndSha256List(credentialsKeyFiles)
                .associateBy { "${it.credentialsKey}:${it.sha256}" }

            return fileScanResultDao.findScannerResults(scanner, credentialsKeyFiles).map {
                val status = subScanTaskMap["${it.credentialsKey}:${it.sha256}"]?.status
                    ?: SubScanTaskStatus.SUCCESS.name
                // 只查询对应scanner的结果，此处必定不为null
                val scannerResult = it.scanResult[scanner]!!
                FileScanResultOverview(
                    status = status,
                    sha256 = it.sha256,
                    scanDate = scannerResult.startDateTime.format(ISO_DATE_TIME),
                    overview = scannerResult.overview
                )
            }
        }
    }

    override fun resultDetail(request: FileScanResultDetailRequest): FileScanResultDetail {
        with(request) {
            val node = artifactInfo!!.run {
                nodeClient.getNodeDetail(projectId, repoName, getArtifactFullPath())
            }.data!!
            val repo = repositoryClient.getRepoInfo(node.projectId, node.repoName).data!!

            val scanner = scannerService.get(scanner)
            val scanResultDetail = scanExecutorResultManagers[scanner.type]?.load(
                repo.storageCredentialsKey, node.sha256!!, scanner.name, reportType, pageLimit
            )
            val status = if (scanResultDetail == null) {
                subScanTaskDao.findByCredentialsAndSha256(repo.storageCredentialsKey, node.sha256!!)?.status
                    ?: SubScanTaskStatus.NEVER_SCANNED.name
            } else {
                SubScanTaskStatus.SUCCESS.name
            }
            return FileScanResultDetail(status, node.sha256!!, scanResultDetail, reportType)
        }
    }

    private fun toLocalDateTime(timestamp: Long): LocalDateTime {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    }

    companion object {
        /**
         * 默认任务最长执行时间，超过后会触发重试
         */
        private const val DEFAULT_TASK_EXECUTE_TIMEOUT_SECONDS = 1200L

        /**
         * 最大允许重复执行次数
         */
        private const val DEFAULT_MAX_EXECUTE_TIMES = 3

        /**
         * 最大允许的拉取任务重试次数
         */
        private const val MAX_RETRY_PULL_TASK_TIMES = 3

        /**
         * 定时扫描超时任务入队
         */
        private const val FIXED_DELAY = 3000L
    }
}