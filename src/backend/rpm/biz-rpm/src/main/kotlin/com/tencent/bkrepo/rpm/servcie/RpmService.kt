package com.tencent.bkrepo.rpm.servcie

import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.repository.RpmLocalConfiguration
import com.tencent.bkrepo.common.artifact.repository.context.*
import com.tencent.bkrepo.common.security.permission.Permission
import com.tencent.bkrepo.repository.api.RepositoryClient
import com.tencent.bkrepo.repository.pojo.repo.RepoUpdateRequest
import com.tencent.bkrepo.rpm.FILELISTS_XML
import com.tencent.bkrepo.rpm.OTHERS_XML
import com.tencent.bkrepo.rpm.REPOMD_XML
import com.tencent.bkrepo.rpm.artifact.RpmArtifactInfo
import com.tencent.bkrepo.rpm.artifact.repository.RpmLocalRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class RpmService {

    @Autowired
    lateinit var repositoryClient: RepositoryClient

    @Permission(type = ResourceType.REPO, action = PermissionAction.READ)
    fun install(rpmArtifactInfo: RpmArtifactInfo) {
        val context = ArtifactDownloadContext()
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        repository.download(context)
    }

    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun deploy(rpmArtifactInfo: RpmArtifactInfo, file: ArtifactFile) {
        val context = ArtifactUploadContext(file)
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        repository.upload(context)
    }

    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun delete(rpmArtifactInfo: RpmArtifactInfo) {
        val context = ArtifactRemoveContext()
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        repository.remove(context)
    }

    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun addGroups(rpmArtifactInfo: RpmArtifactInfo, groups: MutableSet<String>) {
        val context = ArtifactSearchContext()
        // 移除groups 中不允许的元素
        groups.removeAll(mutableSetOf(REPOMD_XML, FILELISTS_XML, OTHERS_XML))
        val rpmLocalConfiguration = context.repositoryInfo.configuration as RpmLocalConfiguration
        rpmLocalConfiguration.groupXmlSet.addAll(groups)
        val repoUpdateRequest = RepoUpdateRequest(
            context.artifactInfo.projectId,
            context.artifactInfo.repoName,
            context.repositoryInfo.category,
            context.repositoryInfo.public,
            context.repositoryInfo.description,
            rpmLocalConfiguration,
            context.userId
        )
        repositoryClient.update(repoUpdateRequest)
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        (repository as RpmLocalRepository).flushRepoMdXML(context)
    }

    @Permission(type = ResourceType.REPO, action = PermissionAction.WRITE)
    fun deleteGroups(rpmArtifactInfo: RpmArtifactInfo, groups: MutableSet<String>) {
        val context = ArtifactSearchContext()
        val rpmLocalConfiguration = context.repositoryInfo.configuration as RpmLocalConfiguration
        rpmLocalConfiguration.groupXmlSet.removeAll(groups)
        val repoUpdateRequest = RepoUpdateRequest(
            context.artifactInfo.projectId,
            context.artifactInfo.repoName,
            context.repositoryInfo.category,
            context.repositoryInfo.public,
            context.repositoryInfo.description,
            rpmLocalConfiguration,
            context.userId
        )
        repositoryClient.update(repoUpdateRequest)
        val repository = RepositoryHolder.getRepository(context.repositoryInfo.category)
        (repository as RpmLocalRepository).flushRepoMdXML(context)
    }
}
