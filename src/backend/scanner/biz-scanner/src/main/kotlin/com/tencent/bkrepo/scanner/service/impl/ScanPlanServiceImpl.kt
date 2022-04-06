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

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.exception.NotFoundException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.mongo.dao.util.Pages
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.scanner.pojo.scanner.SubScanTaskStatus
import com.tencent.bkrepo.common.security.util.SecurityUtils
import com.tencent.bkrepo.repository.api.PackageClient
import com.tencent.bkrepo.scanner.dao.FinishedSubScanTaskDao
import com.tencent.bkrepo.scanner.dao.ScanPlanDao
import com.tencent.bkrepo.scanner.dao.ScanTaskDao
import com.tencent.bkrepo.scanner.dao.SubScanTaskDao
import com.tencent.bkrepo.scanner.message.ScannerMessageCode
import com.tencent.bkrepo.scanner.model.TScanPlan
import com.tencent.bkrepo.scanner.pojo.PlanType
import com.tencent.bkrepo.scanner.pojo.ScanPlan
import com.tencent.bkrepo.scanner.pojo.ScanTaskStatus
import com.tencent.bkrepo.scanner.pojo.request.ArtifactPlanRelationRequest
import com.tencent.bkrepo.scanner.pojo.request.PlanArtifactRequest
import com.tencent.bkrepo.scanner.pojo.request.UpdateScanPlanRequest
import com.tencent.bkrepo.scanner.pojo.response.ArtifactPlanRelation
import com.tencent.bkrepo.scanner.pojo.response.ArtifactScanResultOverview
import com.tencent.bkrepo.scanner.pojo.response.PlanArtifactInfo
import com.tencent.bkrepo.scanner.pojo.response.ScanPlanInfo
import com.tencent.bkrepo.scanner.service.ScanPlanService
import com.tencent.bkrepo.scanner.utils.Request
import com.tencent.bkrepo.scanner.utils.ScanParamUtil
import com.tencent.bkrepo.scanner.utils.ScanPlanConverter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class ScanPlanServiceImpl(
    private val packageClient: PackageClient,
    private val scanPlanDao: ScanPlanDao,
    private val scanTaskDao: ScanTaskDao,
    private val subScanTaskDao: SubScanTaskDao,
    private val finishedSubScanTaskDao: FinishedSubScanTaskDao
) : ScanPlanService {
    override fun create(request: ScanPlan): ScanPlan {
        val operator = SecurityUtils.getUserId()
        logger.info("userId:$operator, create scanPlan[${request.name}]")
        with(request) {
            if (name.isNullOrEmpty() || name!!.length > PLAN_NAME_LENGTH_MAX) {
                throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "name cannot be empty or length > 32")
            }

            if (!PlanType.contains(type)) {
                throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "invalid scan plan type[$type]")
            }

            if (scanPlanDao.existsByProjectIdAndName(projectId!!, name!!)) {
                logger.error("scan plan [$name] is exist.")
                throw ErrorCodeException(CommonMessageCode.RESOURCE_EXISTED, name.toString())
            }

            val now = LocalDateTime.now()
            val tScanPlan = TScanPlan(
                projectId = projectId!!,
                name = name!!,
                type = type!!,
                description = description ?: "",
                scanner = scanner!!,
                scanOnNewArtifact = scanOnNewArtifact ?: false,
                repoNames = repoNames ?: emptyList(),
                rule = rule!!.toJsonString(),
                createdBy = operator,
                createdDate = now,
                lastModifiedBy = operator,
                lastModifiedDate = now
            )
            return ScanPlanConverter.convert(scanPlanDao.insert(tScanPlan))
        }
    }

    override fun list(projectId: String, type: String?): List<ScanPlan> {
        return scanPlanDao.list(projectId, type).map { ScanPlanConverter.convert(it) }
    }

    override fun page(
        projectId: String,
        type: String?,
        planNameContains: String?,
        pageLimit: PageLimit
    ): Page<ScanPlanInfo> {
        val page = scanPlanDao.page(projectId, type, planNameContains, pageLimit)
        val scanTaskMap = scanTaskDao.findByPlanIdIn(page.records.map { it.id!! }).associateBy { it.planId }
        return Page(
            page.pageNumber,
            page.pageSize,
            page.totalRecords,
            page.records.map { ScanPlanConverter.convert(it, scanTaskMap[it.id!!]) }
        )
    }

    override fun find(projectId: String, id: String): ScanPlan? {
        return scanPlanDao.find(projectId, id)?.let { ScanPlanConverter.convert(it) }
    }

    override fun delete(projectId: String, id: String) {
        logger.info("deleteScanPlan userId:${SecurityUtils.getUserId()}, projectId:$projectId, planId:$id")

        if (!scanPlanDao.exists(projectId, id)) {
            throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, projectId, id)
        }

        // 方案正在使用，不能删除
        checkRunning(id)
        scanPlanDao.delete(projectId, id)
    }

    override fun update(request: UpdateScanPlanRequest): ScanPlan {
        val operator = SecurityUtils.getUserId()
        logger.info("userId:$operator, updateScanPlan:[${request.id}]")
        with(request) {
            if (id.isNullOrEmpty()) {
                throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "planId is empty")
            }
            val plan = scanPlanDao.find(projectId!!, id!!)
                ?: throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, request.toString())

            scanPlanDao.update(ScanPlanConverter.convert(request, plan.repoNames, plan.rule.readJsonString()))
            return scanPlanDao.findById(request.id!!)!!.let { ScanPlanConverter.convert(it) }
        }
    }

    override fun scanPlanInfo(projectId: String, id: String): ScanPlanInfo? {
        val scanPlan = scanPlanDao.find(projectId, id)
            ?: throw throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, projectId, id)
        val scanTask = scanTaskDao.latestTask(id)
        return ScanPlanConverter.convert(scanPlan, scanTask)
    }

    override fun planArtifactPage(request: PlanArtifactRequest): Page<PlanArtifactInfo> {
        with(request) {
            val pageRequest = Pages.ofRequest(pageNumber, pageSize)

            // 获取扫描方案最新一次扫描任务
            val scanTask = parentScanTaskId?.let { scanTaskDao.findById(it) }
                ?: scanTaskDao.latestTask(id)
                ?: return Pages.ofResponse(pageRequest, 0L, emptyList())
            parentScanTaskId = scanTask.id

            // 查询已结束的子任务
            val containsFinishedStatus = subScanTaskStatus.isNullOrEmpty()
                || subScanTaskStatus!!.firstOrNull { SubScanTaskStatus.finishedStatus(it) } != null
            val finishedSubScanTasks = if (containsFinishedStatus) {
                finishedSubScanTaskDao.findSubScanTasks(request, scanTask.scannerType)
            } else {
                null
            }

            // 查询执行中的子任务
            val containsUnfinishedStatus = subScanTaskStatus.isNullOrEmpty()
                || subScanTaskStatus?.firstOrNull { !SubScanTaskStatus.finishedStatus(it) } != null
            val unfinishedSubScanTasks = if (containsUnfinishedStatus) {
                subScanTaskDao.findSubScanTasks(request, scanTask.scannerType)
            } else {
                null
            }

            // 合并结果返回
            val totalRecords = (finishedSubScanTasks?.totalRecords ?: 0L) + (unfinishedSubScanTasks?.totalRecords ?: 0L)
            val subTasks = (finishedSubScanTasks?.records ?: emptyList()) + (unfinishedSubScanTasks?.records
                ?: emptyList())
            return Pages.ofResponse(
                pageRequest,
                totalRecords,
                subTasks.map { ScanPlanConverter.convertToPlanArtifactInfo(it, scanTask.createdBy) }
            )
        }
    }

    override fun planArtifact(projectId: String, subScanTaskId: String): ArtifactScanResultOverview {
        val subtask = finishedSubScanTaskDao.find(projectId, subScanTaskId)
            ?: subScanTaskDao.find(projectId, subScanTaskId)
            ?: throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, projectId, subScanTaskId)
        return ScanPlanConverter.convert(subtask)
    }

    override fun artifactPlanList(request: ArtifactPlanRelationRequest): List<ArtifactPlanRelation> {
        with(request) {
            ScanParamUtil.checkParam(
                repoType = RepositoryType.valueOf(repoType),
                artifactName = fullPath ?: "",
                packageKey = packageKey,
                version = version,
                fullPath = fullPath
            )
            if (fullPath == null) {
                fullPath = Request.request {
                    packageClient.findVersionByName(projectId, repoName, packageKey!!, version!!)
                }?.contentPath ?: throw NotFoundException(CommonMessageCode.RESOURCE_NOT_FOUND, packageKey!!, version!!)
            }
            val subtasks = finishedSubScanTaskDao.findSubScanTasks(request) + subScanTaskDao.findSubScanTasks(request)
            return subtasks.map {
                ScanPlanConverter.convertToArtifactPlanRelation(it)
            }
        }
    }

    override fun artifactPlanStatus(request: ArtifactPlanRelationRequest): String? {
        val relations = artifactPlanList(request)

        if (relations.isEmpty()) {
            return null
        }

        return ScanPlanConverter.artifactStatus(relations.map { it.status })
    }

    private fun checkRunning(planId: String) {
        if (scanTaskDao.existsByPlanIdAndStatus(planId, runningStatus)) {
            throw ErrorCodeException(ScannerMessageCode.SCAN_PLAN_DELETE_FAILED)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ScanPlanServiceImpl::class.java)
        private val runningStatus = listOf(
            ScanTaskStatus.PENDING.name,
            ScanTaskStatus.SCANNING_SUBMITTING.name,
            ScanTaskStatus.SCANNING_SUBMITTED.name
        )
        private const val PLAN_NAME_LENGTH_MAX = 32
    }
}