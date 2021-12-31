/*
 *
 *  * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *  *
 *  * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *  *
 *  * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *  *
 *  * A copy of the MIT License is included in this file.
 *  *
 *  *
 *  * Terms of the MIT License:
 *  * ---------------------------------------------------
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 *
 */

package com.tencent.bkrepo.opdata.registry.consul

import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.opdata.config.OkHttpConfiguration.Companion.OP_OKHTTP_CLIENT_NAME
import com.tencent.bkrepo.opdata.pojo.enums.InstanceStatus
import com.tencent.bkrepo.opdata.pojo.registry.InstanceInfo
import com.tencent.bkrepo.opdata.pojo.registry.ServiceInfo
import com.tencent.bkrepo.opdata.registry.RegistryApi
import com.tencent.bkrepo.opdata.registry.consul.pojo.ConsulInstanceCheck
import com.tencent.bkrepo.opdata.registry.consul.pojo.ConsulInstanceCheck.Companion.STATUS_PASSING
import com.tencent.bkrepo.opdata.registry.consul.pojo.ConsulInstanceHealth
import com.tencent.bkrepo.opdata.registry.consul.pojo.ConsulInstanceId
import com.tencent.bkrepo.opdata.util.parseResAndThrowExceptionOnRequestFailed
import com.tencent.bkrepo.opdata.util.requestBuilder
import com.tencent.bkrepo.opdata.util.throwExceptionOnRequestFailed
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.Util.EMPTY_REQUEST
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.consul.ConditionalOnConsulEnabled
import org.springframework.cloud.consul.ConsulProperties
import org.springframework.stereotype.Component

@Component
@ConditionalOnConsulEnabled
class ConsulRegistryApi @Autowired constructor(
    @Qualifier(OP_OKHTTP_CLIENT_NAME) private val httpClient: OkHttpClient,
    private val consulProperties: ConsulProperties
) : RegistryApi {

    override fun services(): List<ServiceInfo> {
        val url = urlBuilder().addPathSegments(CONSUL_LIST_SERVICES_PATH).build()
        val req = url.requestBuilder().build()
        val res = httpClient.newCall(req).execute()
        return res.use {
            parseResAndThrowExceptionOnRequestFailed(res) { res ->
                res.body()!!.string().readJsonString<Map<String, List<String>>>().map {
                    ServiceInfo(it.key, emptyList())
                }
            }
        }
    }

    override fun instances(serviceName: String): List<InstanceInfo> {
        return listConsulInstanceHealth(serviceName).map { convertToInstanceInfo(it) }
    }

    override fun deregister(serviceName: String, instanceId: String): InstanceInfo {
        val consulInstanceId = ConsulInstanceId.create(instanceId)

        // 获取服务所在节点，必须在服务所在节点上注销，服务实例才不会再次自动注册
        val consulInstanceHealth = consulInstanceHealth(serviceName, instanceId)

        // 确定注销后服务实例状态
        var instanceStatus = InstanceStatus.DEREGISTER
        consulInstanceHealth.consulInstanceChecks
            .filter { it.serviceName.isNotEmpty() }
            .forEach {
                if (it.status != STATUS_PASSING) {
                    logger.warn("consul instance status: ${it.status}, node: ${it.node}, serviceId: ${it.serviceId}")
                    instanceStatus = InstanceStatus.OFFLINE
                }
            }

        // 注销服务实例
        val consulNode = consulInstanceHealth.consulNode
        val url = HttpUrl.Builder()
            .scheme(consulProperties.scheme ?: CONSUL_DEFAULT_SCHEME)
            .host(consulNode.address)
            .port(consulProperties.port)
            .addPathSegments(CONSUL_DEREGISTER_PATH)
            .addPathSegment(consulInstanceId.serviceId)
            .build()
        val req = url.requestBuilder().put(EMPTY_REQUEST).build()
        val res = httpClient.newCall(req).execute()
        throwExceptionOnRequestFailed(res)

        // 返回服务实例信息
        return convertToInstanceInfo(consulInstanceHealth).copy(status = instanceStatus)
    }

    override fun instanceInfo(serviceName: String, instanceId: String): InstanceInfo {
        val consulInstanceHealth = consulInstanceHealth(serviceName, instanceId)
        return convertToInstanceInfo(consulInstanceHealth)
    }

    private fun consulInstanceHealth(serviceName: String, instanceId: String): ConsulInstanceHealth {
        val consulInstances = listConsulInstanceHealth(serviceName, instanceId)
        require(consulInstances.size == 1)
        return consulInstances[0]
    }

    private fun listConsulInstanceHealth(
        serviceName: String,
        instanceId: String? = null
    ): List<ConsulInstanceHealth> {
        val urlBuilder = urlBuilder().addPathSegments(CONSUL_LIST_SERVICE_HEALTH_PATH).addPathSegment(serviceName)
        val url = if (instanceId.isNullOrEmpty()) {
            urlBuilder.build()
        } else {
            val consulInstanceId = ConsulInstanceId.create(instanceId)
            val filterExpression = "$CONSUL_FILTER_SELECTOR_SERVICE_ID == ${consulInstanceId.serviceId} and" +
                " $CONSUL_FILTER_SELECTOR_NODE_NAME == ${consulInstanceId.nodeName}"
            urlBuilder.addQueryParameter(CONSUL_QUERY_PARAM_FILTER, filterExpression).build()
        }
        val req = url.requestBuilder().build()
        val res = httpClient.newCall(req).execute()
        return res.use {
            parseResAndThrowExceptionOnRequestFailed(res) { res -> res.body()!!.string().readJsonString() }
        }
    }

    private fun convertToInstanceInfo(consulInstanceHealth: ConsulInstanceHealth): InstanceInfo {
        val consulInstance = consulInstanceHealth.consulInstance
        val consulNode = consulInstanceHealth.consulNode
        // 过滤非服务实例的健康检查信息
        val consulInstanceStatusList = consulInstanceHealth.consulInstanceChecks.filter { it.serviceName.isNotEmpty() }
        // 应至少有一条服务实例的健康检查信息
        require(consulInstanceStatusList.isNotEmpty())

        val consulInstanceId =
            ConsulInstanceId.create(consulNode.datacenter, consulNode.nodeName, consulInstance.id)
        return InstanceInfo(
            id = consulInstanceId.instanceIdStr(),
            host = consulInstance.address,
            port = consulInstance.port,
            status = convertToInstanceStatus(consulInstanceStatusList)
        )
    }

    /**
     * 服务的全部检查通过才算正常运行
     */
    private fun convertToInstanceStatus(instanceStatus: List<ConsulInstanceCheck>): InstanceStatus {
        instanceStatus.forEach {
            if (it.status != STATUS_PASSING) {
                return InstanceStatus.OFFLINE
            }
        }
        return InstanceStatus.RUNNING
    }

    private fun urlBuilder() = HttpUrl.Builder()
        .scheme(consulProperties.scheme ?: CONSUL_DEFAULT_SCHEME)
        .host(consulProperties.host)
        .port(consulProperties.port)

    companion object {
        private val logger = LoggerFactory.getLogger(ConsulRegistryApi::class.java)

        private const val CONSUL_DEFAULT_SCHEME = "http"

        private const val CONSUL_QUERY_PARAM_FILTER = "filter"
        private const val CONSUL_FILTER_SELECTOR_SERVICE_ID = "Service.ID"
        private const val CONSUL_FILTER_SELECTOR_NODE_NAME = "Node.Node"

        private const val CONSUL_LIST_SERVICES_PATH = "v1/catalog/services"
        private const val CONSUL_LIST_SERVICE_HEALTH_PATH = "v1/health/service"
        private const val CONSUL_DEREGISTER_PATH = "v1/agent/service/deregister"
    }
}
