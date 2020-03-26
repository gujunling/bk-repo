package com.tencent.bkrepo.replication.controller

import com.tencent.bkrepo.auth.api.ServicePermissionResource
import com.tencent.bkrepo.auth.api.ServiceRoleResource
import com.tencent.bkrepo.auth.api.ServiceUserResource
import com.tencent.bkrepo.auth.pojo.CreatePermissionRequest
import com.tencent.bkrepo.auth.pojo.CreateRoleRequest
import com.tencent.bkrepo.auth.pojo.CreateUserRequest
import com.tencent.bkrepo.auth.pojo.Permission
import com.tencent.bkrepo.auth.pojo.Role
import com.tencent.bkrepo.auth.pojo.User
import com.tencent.bkrepo.auth.pojo.enums.ResourceType
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.common.artifact.api.ArtifactFileMap
import com.tencent.bkrepo.common.artifact.permission.Principal
import com.tencent.bkrepo.common.artifact.permission.PrincipalType
import com.tencent.bkrepo.common.service.util.ResponseBuilder
import com.tencent.bkrepo.common.storage.core.StorageService
import com.tencent.bkrepo.replication.api.DataReplicationService
import com.tencent.bkrepo.replication.config.DEFAULT_VERSION
import com.tencent.bkrepo.replication.pojo.request.NodeReplicaRequest
import com.tencent.bkrepo.replication.pojo.request.RoleReplicaRequest
import com.tencent.bkrepo.replication.pojo.request.UserReplicaRequest
import com.tencent.bkrepo.repository.api.NodeResource
import com.tencent.bkrepo.repository.api.ProjectResource
import com.tencent.bkrepo.repository.api.RepositoryResource
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.project.ProjectCreateRequest
import com.tencent.bkrepo.repository.pojo.project.ProjectInfo
import com.tencent.bkrepo.repository.pojo.repo.RepoCreateRequest
import com.tencent.bkrepo.repository.pojo.repo.RepositoryInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@Principal(type = PrincipalType.ADMIN)
@RestController
class DataReplicationController : DataReplicationService {

    @Autowired
    private lateinit var projectResource: ProjectResource

    @Autowired
    private lateinit var repositoryResource: RepositoryResource

    @Autowired
    private lateinit var nodeResource: NodeResource

    @Autowired
    private lateinit var permissionResource: ServicePermissionResource

    @Autowired
    private lateinit var userResource: ServiceUserResource

    @Autowired
    private lateinit var roleResource: ServiceRoleResource

    @Autowired
    private lateinit var storageService: StorageService

    @Value("\${spring.application.version}")
    private var version: String = DEFAULT_VERSION

    override fun ping(token: String) = ResponseBuilder.success()

    override fun version(token: String) = ResponseBuilder.success(version)

    override fun checkNodeExist(token: String, projectId: String, repoName: String, fullPath: String): Response<Boolean> {
        return nodeResource.exist(projectId, repoName, fullPath)
    }

    override fun replicaProject(token: String, projectCreateRequest: ProjectCreateRequest): Response<ProjectInfo> {
        with(projectCreateRequest) {
            val projectInfo = projectResource.query(name).data ?: run {
                projectResource.create(this).data!!
            }
            return ResponseBuilder.success(projectInfo)
        }
    }

    override fun replicaRepo(token: String, repoCreateRequest: RepoCreateRequest): Response<RepositoryInfo> {
        with(repoCreateRequest) {
            val repositoryInfo = repositoryResource.detail(projectId, name).data ?: run {
                repositoryResource.create(this).data!!
            }
            return ResponseBuilder.success(repositoryInfo)
        }
    }

    override fun replicaUser(token: String, userReplicaRequest: UserReplicaRequest): Response<User> {
        with(userReplicaRequest) {
            val userInfo = userResource.detail(userId).data ?: run {
                val request = CreateUserRequest(userId, name, pwd, admin)
                userResource.createUser(request)
                userResource.detail(userId).data!!
            }
            val remoteTokenStringList = this.tokens.map { it.id }
            val selfTokenStringList = userInfo.tokens.map { it.id }
            remoteTokenStringList.forEach {
                if (!selfTokenStringList.contains(it)) {
                    userResource.addUserToken(userId, token)
                }
            }
            return ResponseBuilder.success(userInfo)
        }
    }

    override fun replicaRole(token: String, roleReplicaRequest: RoleReplicaRequest): Response<Role> {
        with(roleReplicaRequest) {
            val existRole = if (repoName == null) {
                roleResource.detailByRidAndProjectId(roleId, projectId).data
            } else {
                roleResource.detailByRidAndProjectIdAndRepoName(roleId, projectId, repoName!!).data
            }
            val roleInfo = existRole ?: run {
                val request = CreateRoleRequest(roleId, name, type, projectId, repoName, admin)
                val id = roleResource.createRole(request).data!!
                roleResource.detail(id).data!!
            }

            return ResponseBuilder.success(roleInfo)
        }
    }

    override fun replicaPermission(token: String, permissionCreateRequest: CreatePermissionRequest): Response<Void> {
        permissionResource.createPermission(permissionCreateRequest)
        return ResponseBuilder.success()
    }

    override fun replicaUserRoleRelationShip(token: String, rid: String, userIdList: List<String>): Response<Void> {
        userResource.addUserRoleBatch(rid, userIdList)
        return ResponseBuilder.success()
    }

    override fun listPermission(token: String, resourceType: ResourceType, projectId: String, repoName: String?): Response<List<Permission>> {
        return permissionResource.listPermission(resourceType, projectId, repoName)
    }

    @PostMapping("/node", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun replicaNode(token: String, fileMap: ArtifactFileMap, nodeReplicaRequest: NodeReplicaRequest): Response<NodeInfo> {
        with(nodeReplicaRequest) {
            val file = fileMap["file"]!!
            // 校验
            if (file.getSize() != size) {
                throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "size")
            }
            if (sha256.isBlank()) {
                throw ErrorCodeException(CommonMessageCode.PARAMETER_MISSING, "sha256")
            }
            // 保存文件
            val repoInfo = repositoryResource.detail(projectId, repoName).data!!
            storageService.store(sha256, file, repoInfo.storageCredentials)
            // 保存节点
            val request = NodeCreateRequest(
                projectId = projectId,
                repoName = repoName,
                fullPath = fullPath,
                folder = false,
                expires = expires,
                overwrite = true,
                size = size,
                sha256 = sha256,
                md5 = md5,
                metadata = metadata,
                operator = userId
            )
            return nodeResource.create(request)
        }
    }

}
