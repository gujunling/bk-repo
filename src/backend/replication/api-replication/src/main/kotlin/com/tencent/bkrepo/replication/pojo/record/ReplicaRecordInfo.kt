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

package com.tencent.bkrepo.replication.pojo.record

import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty
import java.time.LocalDateTime

@ApiModel("同步任务执行记录")
data class ReplicaRecordInfo(
    @ApiModelProperty("记录唯一id")
    val id: String,
    @ApiModelProperty("关联任务key")
    val taskKey: String,
    @ApiModelProperty("任务状态")
    var status: ExecutionStatus,
    @ApiModelProperty("开始时间")
    var startTime: LocalDateTime,
    @ApiModelProperty("结束时间")
    var endTime: LocalDateTime? = null,
    @ApiModelProperty("错误原因，未执行或执行成功则为null")
    var errorReason: String? = null,
    @ApiModelProperty("已同步字节数")
    var replicatedBytes: Long? = 0,
    @ApiModelProperty("总字节数")
    var totalBytes: Long? = 0,
    @ApiModelProperty("执行结果总览")
    var replicaOverview: ReplicaOverview? = null
)
