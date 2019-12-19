package com.tencent.bkrepo.repository.model

import com.tencent.bkrepo.common.artifact.pojo.RepositoryType
import com.tencent.bkrepo.common.artifact.pojo.RepositoryCategory
import java.time.LocalDateTime
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 仓库模型
 *
 * @author: carrypan
 * @date: 2019-09-10
 */
@Document("repository")
@CompoundIndexes(
        CompoundIndex(name = "projectId_name_idx", def = "{'projectId': 1, 'name': 1}", unique = true)
)
data class TRepository(
    var id: String? = null,
    var createdBy: String,
    var createdDate: LocalDateTime,
    var lastModifiedBy: String,
    var lastModifiedDate: LocalDateTime,

    var name: String,
    var type: RepositoryType,
    var category: RepositoryCategory,
    var public: Boolean,
    var description: String? = null,
    var configuration: String,
    var storageCredentials: String? = null,

    var projectId: String
)
