package com.tencent.bkrepo.repository.service

import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.query.model.QueryModel

/**
 * 节点自定义查询服务接口
 */
interface NodeQueryService {
    /**
     * 根据[queryModel]查询节点
     */
    fun query(queryModel: QueryModel): Page<Map<String, Any?>>
}