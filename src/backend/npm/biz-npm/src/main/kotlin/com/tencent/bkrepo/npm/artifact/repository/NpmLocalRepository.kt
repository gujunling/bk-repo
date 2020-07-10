package com.tencent.bkrepo.npm.artifact.repository

import com.google.gson.JsonObject
import com.tencent.bkrepo.common.api.constant.StringPool
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.config.ATTRIBUTE_MD5MAP
import com.tencent.bkrepo.common.artifact.config.ATTRIBUTE_SHA256MAP
import com.tencent.bkrepo.common.artifact.exception.ArtifactNotFoundException
import com.tencent.bkrepo.common.artifact.exception.ArtifactValidateException
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactDownloadContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactListContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactMigrateContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactRemoveContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactSearchContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactTransferContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.local.LocalRepository
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.stream.ArtifactInputStream
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.artifact.util.http.HttpClientBuilderFactory
import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.common.query.model.Sort
import com.tencent.bkrepo.common.storage.util.FileDigestUtils
import com.tencent.bkrepo.npm.async.NpmDependentHandler
import com.tencent.bkrepo.npm.constants.APPLICATION_OCTET_STEAM
import com.tencent.bkrepo.npm.constants.ATTRIBUTE_OCTET_STREAM_SHA1
import com.tencent.bkrepo.npm.constants.AUTHOR
import com.tencent.bkrepo.npm.constants.DATE
import com.tencent.bkrepo.npm.constants.DESCRIPTION
import com.tencent.bkrepo.npm.constants.DIST
import com.tencent.bkrepo.npm.constants.ID
import com.tencent.bkrepo.npm.constants.KEYWORDS
import com.tencent.bkrepo.npm.constants.LAST_MODIFIED_DATE
import com.tencent.bkrepo.npm.constants.MAINTAINERS
import com.tencent.bkrepo.npm.constants.METADATA
import com.tencent.bkrepo.npm.constants.NAME
import com.tencent.bkrepo.npm.constants.NPM_FILE_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_METADATA
import com.tencent.bkrepo.npm.constants.NPM_PACKAGE_TGZ_FILE
import com.tencent.bkrepo.npm.constants.NPM_PKG_TGZ_FULL_PATH
import com.tencent.bkrepo.npm.constants.NPM_PKG_VERSION_FULL_PATH
import com.tencent.bkrepo.npm.constants.PACKAGE
import com.tencent.bkrepo.npm.constants.PKG_NAME
import com.tencent.bkrepo.npm.constants.SEARCH_REQUEST
import com.tencent.bkrepo.npm.constants.TARBALL
import com.tencent.bkrepo.npm.constants.VERSION
import com.tencent.bkrepo.npm.constants.VERSIONS
import com.tencent.bkrepo.npm.pojo.NpmSearchResponse
import com.tencent.bkrepo.npm.pojo.enums.NpmOperationAction
import com.tencent.bkrepo.npm.pojo.metadata.MetadataSearchRequest
import com.tencent.bkrepo.npm.utils.GsonUtils
import com.tencent.bkrepo.repository.api.MetadataResource
import com.tencent.bkrepo.repository.pojo.download.service.DownloadStatisticsAddRequest
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.node.service.NodeDeleteRequest
import com.tencent.bkrepo.repository.util.NodeUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.apache.commons.lang.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.TimeUnit

@Component
class NpmLocalRepository : LocalRepository() {

    @Autowired
    lateinit var metadataResource: MetadataResource

    @Value("\${npm.migration.remote.registry}")
    private val registry: String = StringPool.EMPTY

    @Value("\${npm.tarball.prefix}")
    private val tarballPrefix: String = StringPool.SLASH

    @Autowired
    private lateinit var npmDependentHandler: NpmDependentHandler

    private val okHttpClient: OkHttpClient by lazy {
        HttpClientBuilderFactory.create().readTimeout(TIMEOUT, TimeUnit.SECONDS).build()
    }

    override fun onUploadValidate(context: ArtifactUploadContext) {
        super.onUploadValidate(context)
        context.artifactFileMap.entries.forEach { (name, file) ->
            if (name == NPM_PACKAGE_TGZ_FILE) {
                // 校验MIME_TYPE
                context.contextAttributes[APPLICATION_OCTET_STEAM].takeIf { it == MediaType.APPLICATION_OCTET_STREAM_VALUE }
                    ?: throw ArtifactValidateException("Request MIME_TYPE is not ${MediaType.APPLICATION_OCTET_STREAM_VALUE}")
                // 计算sha1并校验
                val calculatedSha1 = FileDigestUtils.fileSha1(listOf(file.getInputStream()))
                val uploadSha1 = context.contextAttributes[ATTRIBUTE_OCTET_STREAM_SHA1] as String?
                if (uploadSha1 != null && calculatedSha1 != uploadSha1) {
                    throw ArtifactValidateException("File shasum validate failed.")
                }
            }
        }
    }

    override fun onUpload(context: ArtifactUploadContext) {
        context.artifactFileMap.entries.forEach { (name, _) ->
            val nodeCreateRequest = getNodeCreateRequest(name, context)
            nodeResource.create(nodeCreateRequest)
            storageService.store(
                nodeCreateRequest.sha256!!,
                context.getArtifactFile(name)!!,
                context.storageCredentials
            )
        }
    }

    private fun getNodeCreateRequest(name: String, context: ArtifactUploadContext): NodeCreateRequest {
        val repositoryInfo = context.repositoryInfo
        val artifactFile = context.getArtifactFile(name)
        val contextAttributes = context.contextAttributes
        val fileSha256Map = context.contextAttributes[ATTRIBUTE_SHA256MAP] as Map<*, *>
        val fileMd5Map = context.contextAttributes[ATTRIBUTE_MD5MAP] as Map<*, *>
        val sha256 = fileSha256Map[name] as? String
        val md5 = fileMd5Map[name] as? String

        return NodeCreateRequest(
            projectId = repositoryInfo.projectId,
            repoName = repositoryInfo.name,
            folder = false,
            fullPath = contextAttributes[name + "_full_path"] as String,
            size = artifactFile?.getSize(),
            sha256 = sha256,
            md5 = md5,
            operator = context.userId,
            metadata = parseMetaData(name, contextAttributes),
            overwrite = name != NPM_PACKAGE_TGZ_FILE
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseMetaData(name: String, contextAttributes: MutableMap<String, Any>): Map<String, String> {
        return if (name == NPM_PACKAGE_TGZ_FILE) contextAttributes[NPM_METADATA] as Map<String, String> else emptyMap()
    }

    override fun determineArtifactName(context: ArtifactTransferContext): String {
        val fullPath = context.contextAttributes[NPM_FILE_FULL_PATH] as String
        return NodeUtils.getName(fullPath)
    }

    override fun determineArtifactUri(context: ArtifactDownloadContext): String {
        return context.contextAttributes[NPM_FILE_FULL_PATH] as String
    }

    override fun countDownloads(context: ArtifactDownloadContext) {
        val artifactInfo = context.artifactInfo
        val artifact = artifactInfo.artifactUri.substringBefore("/-/").trimStart('/')
        val version = artifactInfo.artifactUri.substringAfterLast("$artifact${StringPool.DASH}").substringBefore(".tgz")
        downloadStatisticsResource.add(
            DownloadStatisticsAddRequest(
                artifactInfo.projectId,
                artifactInfo.repoName,
                artifact,
                version
            )
        )
    }

    override fun search(context: ArtifactSearchContext): JsonObject? {
        val fullPath = context.contextAttributes[NPM_FILE_FULL_PATH] as String
        val searchResponse = this.onSearch(context)
        if (searchResponse == null) {
            logger.warn("Artifact[$fullPath] does not exist")
        }
        return searchResponse
    }

    private fun onSearch(context: ArtifactSearchContext): JsonObject? {
        val repositoryInfo = context.repositoryInfo
        val projectId = repositoryInfo.projectId
        val repoName = repositoryInfo.name
        val fullPath = context.contextAttributes[NPM_FILE_FULL_PATH] as String
        val node = nodeResource.detail(projectId, repoName, fullPath).data
        if (node == null || node.nodeInfo.folder) return null
        val inputStream = storageService.load(node.nodeInfo.sha256!!, Range.ofFull(node.nodeInfo.size), context.storageCredentials).also {
            logger.info("search artifact [$fullPath] success!")
        }
        return inputStream?.let { getPkgInfo(context, it) }
    }

    private fun getPkgInfo(context: ArtifactSearchContext, inputStream: ArtifactInputStream): JsonObject {
        val fileJson = GsonUtils.transferInputStreamToJson(inputStream)
        val containsVersion = fileJson[ID].asString.substring(1).contains('@')
        if (containsVersion) {
            val name = fileJson.get(NAME).asString
            val version = fileJson[VERSION].asString
            val tgzFullPath = String.format(NPM_PKG_TGZ_FULL_PATH, name, name, version)
            val metadataInfo =
                metadataResource.query(context.artifactInfo.projectId, context.artifactInfo.repoName, tgzFullPath).data
            metadataInfo?.forEach { (key, value) ->
                if (StringUtils.isNotBlank(value)) fileJson.addProperty(key, value)
                if (key == KEYWORDS || key == MAINTAINERS) fileJson.add(key, GsonUtils.stringToArray(value))
            }
            val oldTarball = fileJson.getAsJsonObject(DIST)[TARBALL].asString
            val prefix = oldTarball.split(name)[0].trimEnd('/')
            val newTarball = oldTarball.replace(prefix, tarballPrefix.trimEnd('/'))
            fileJson.getAsJsonObject(DIST).addProperty(TARBALL, newTarball)
        } else {
            val name = fileJson.get(NAME).asString
            val versions = fileJson.getAsJsonObject(VERSIONS)
            versions.keySet().forEach {
                val tgzFullPath = String.format(NPM_PKG_TGZ_FULL_PATH, name, name, it)
                val metadataInfo =
                    metadataResource.query(context.artifactInfo.projectId, context.artifactInfo.repoName, tgzFullPath)
                        .data
                metadataInfo?.forEach { (key, value) ->
                    if (StringUtils.isNotBlank(value)) versions.getAsJsonObject(it).addProperty(key, value)
                    if (key == KEYWORDS || key == MAINTAINERS) versions.getAsJsonObject(it).add(
                        key,
                        GsonUtils.stringToArray(value)
                    )
                }
                val versionObject = versions.getAsJsonObject(it)
                val oldTarball = versionObject.getAsJsonObject(DIST)[TARBALL].asString
                val prefix = oldTarball.split(name)[0].trimEnd('/')
                val newTarball = oldTarball.replace(prefix, tarballPrefix.trimEnd('/'))
                versionObject.getAsJsonObject(DIST).addProperty(TARBALL, newTarball)
            }
        }
        return fileJson
    }

    override fun remove(context: ArtifactRemoveContext) {
        val repositoryInfo = context.repositoryInfo
        val projectId = repositoryInfo.projectId
        val repoName = repositoryInfo.name
        val fullPath = context.contextAttributes[NPM_FILE_FULL_PATH] as List<*>
        val userId = context.userId
        fullPath.forEach {
            nodeResource.delete(NodeDeleteRequest(projectId, repoName, it as String, userId))
        }
    }

    override fun list(context: ArtifactListContext): NpmSearchResponse {
        val searchRequest = context.contextAttributes[SEARCH_REQUEST] as MetadataSearchRequest
        val projectId = Rule.QueryRule("projectId", context.repositoryInfo.projectId)
        val repoName = Rule.QueryRule("repoName", context.repositoryInfo.name)
        val fullPath = Rule.QueryRule("fullPath", ".tgz", OperationType.SUFFIX)

        val nameMd = Rule.QueryRule("metadata.name", searchRequest.text, OperationType.MATCH)
        val descMd = Rule.QueryRule("metadata.description", searchRequest.text, OperationType.MATCH)
        val maintainerMd = Rule.QueryRule("metadata.maintainers", searchRequest.text, OperationType.MATCH)
        val versionMd = Rule.QueryRule("metadata.version", searchRequest.text, OperationType.MATCH)
        val keywordsMd = Rule.QueryRule("metadata.keywords", searchRequest.text, OperationType.MATCH)
        val metadata = Rule.NestedRule(
            mutableListOf(nameMd, descMd, maintainerMd, versionMd, keywordsMd),
            Rule.NestedRule.RelationType.OR
        )
        val rule = Rule.NestedRule(mutableListOf(projectId, repoName, fullPath, metadata))
        val queryModel = QueryModel(
            page = PageLimit(searchRequest.from, searchRequest.size),
            sort = Sort(listOf("lastModifiedDate"), Sort.Direction.DESC),
            select = mutableListOf("projectId", "repoName", "fullPath", "metadata", "lastModifiedDate"),
            rule = rule
        )
        val result = nodeResource.query(queryModel)
        val data = result.data ?: return NpmSearchResponse()
        return transferRecords(data.records)
    }

    private fun transferRecords(records: List<Map<String, Any>>): NpmSearchResponse {
        val listInfo = mutableListOf<Map<String, Any>>()
        if (records.isNullOrEmpty()) return NpmSearchResponse()
        records.forEach {
            val packageInfo = mutableMapOf<String, Any>()
            val metadataInfo = mutableMapOf<String, Any?>()
            val date = it[LAST_MODIFIED_DATE] as String
            val metadata = it[METADATA] as Map<*, *>
            metadataInfo[NAME] = metadata[NAME] as? String
            metadataInfo[DESCRIPTION] = metadata[DESCRIPTION] as? String
            metadataInfo[MAINTAINERS] = parseJsonArrayToList(metadata[MAINTAINERS] as? String)
            metadataInfo[VERSION] = metadata[VERSION] as? String
            metadataInfo[DATE] = date
            metadataInfo[KEYWORDS] = parseJsonArrayToList(metadata[KEYWORDS] as? String)
            metadataInfo[AUTHOR] = metadata[AUTHOR] as? String
            packageInfo[PACKAGE] = metadataInfo
            listInfo.add(packageInfo)
        }
        return NpmSearchResponse(listInfo)
    }

    private fun parseJsonArrayToList(jsonArray: String?): List<Map<String, Any>> {
        return jsonArray?.let { GsonUtils.gsonToList<Map<String, Any>>(it) } ?: emptyList()
    }

    override fun migrate(context: ArtifactMigrateContext) {
        val pkgJsonFile = searchPkgJson(context)
        installTgzFile(context, pkgJsonFile)
    }

    private fun installTgzFile(context: ArtifactMigrateContext, jsonObject: JsonObject) {
        val versions = jsonObject.getAsJsonObject(VERSIONS)
        versions.keySet().forEach { version ->
            var response: Response? = null
            val tgzFilePath: String?
            val tarball = versions.getAsJsonObject(version).getAsJsonObject(DIST).get(TARBALL).asString
            tgzFilePath = tarball.substringAfterLast(registry)
            context.contextAttributes[NPM_FILE_FULL_PATH] = "/$tgzFilePath"
            // hit cache continue
            getCacheArtifact(context)?.let {
                return@forEach
            }
            val request = Request.Builder().url(tarball).get().build()
            try {
                response = okHttpClient.newCall(request).execute()
                if (checkResponse(response)) {
                    val artifactFile = createTempFile(response.body()!!)
                    putArtifact(context, artifactFile)
                }
            } catch (exception: IOException) {
                logger.error("http send url [$tarball] for artifact [$tgzFilePath] failed : ${exception.message}")
                throw exception
            } finally {
                response?.body()?.close()
            }
        }
    }

    private fun searchPkgJson(context: ArtifactMigrateContext): JsonObject {
        getCacheArtifact(context)?.let {
            return GsonUtils.transferInputStreamToJson(it)
        }
        val pkgName = context.contextAttributes[PKG_NAME] as String
        val url = registry.trimEnd('/') + '/' + pkgName
        var response: Response? = null
        return try {
            val request = Request.Builder().url(url).get().build()
            response = okHttpClient.newCall(request).execute()
            if (checkResponse(response)) {
                val artifactFile = createTempFile(response.body()!!)
                putArtifact(context, artifactFile)
                putVersionArtifact(context, artifactFile)
                GsonUtils.transferInputStreamToJson(artifactFile.getInputStream())
            } else throw ArtifactNotFoundException("download from remote for [$pkgName] failed.")
        } catch (exception: IOException) {
            logger.error("http send [$url] for search [$pkgName.json] file failed, {}", exception.message)
            throw exception
        } finally {
            response?.body()?.close()
        }
    }

    private fun getCacheArtifact(context: ArtifactTransferContext): ArtifactInputStream? {
        val repositoryInfo = context.repositoryInfo
        val fullPath = context.contextAttributes[NPM_FILE_FULL_PATH] as String
        val node = nodeResource.detail(repositoryInfo.projectId, repositoryInfo.name, fullPath).data
        if (node == null || node.nodeInfo.folder) return null
        val inputStream = storageService.load(node.nodeInfo.sha256!!, Range.ofFull(node.nodeInfo.size), context.storageCredentials)
        inputStream?.let { logger.debug("Cached remote artifact[$fullPath] is hit") }
        return inputStream
    }

    private fun putVersionArtifact(context: ArtifactMigrateContext, artifactFile: ArtifactFile) {
        val jsonFile = GsonUtils.transferInputStreamToJson(artifactFile.getInputStream())
        val name = jsonFile[NAME].asString
        val versionsFile = jsonFile.getAsJsonObject(VERSIONS)
        versionsFile.keySet().forEach { version ->
            val versionFile = versionsFile.getAsJsonObject(version)
            val artifactVersionFile = ArtifactFileFactory.build(GsonUtils.gsonToInputStream(versionFile))
            context.contextAttributes[NPM_FILE_FULL_PATH] =
                String.format(NPM_PKG_VERSION_FULL_PATH, name, name, version)
            val nodeCreateRequest = getNodeCreateRequest(context, artifactVersionFile)
            nodeResource.create(nodeCreateRequest)
            storageService.store(nodeCreateRequest.sha256!!, artifactVersionFile, context.storageCredentials)
        }
        // 添加依赖
        npmDependentHandler.updatePkgDepts(context.userId, context.artifactInfo, jsonFile, NpmOperationAction.MIGRATION)
    }

    private fun putArtifact(context: ArtifactMigrateContext, artifactFile: ArtifactFile) {
        val pkgFile = ArtifactFileFactory.build(artifactFile.getInputStream())
        val nodeCreateRequest = getNodeCreateRequest(context, pkgFile)
        nodeResource.create(nodeCreateRequest)
        storageService.store(nodeCreateRequest.sha256!!, pkgFile, context.storageCredentials)
    }

    private fun getNodeCreateRequest(context: ArtifactTransferContext, file: ArtifactFile): NodeCreateRequest {
        val repositoryInfo = context.repositoryInfo
        val sha256 = FileDigestUtils.fileSha256(listOf(file.getInputStream()))
        val md5 = FileDigestUtils.fileMd5(listOf(file.getInputStream()))
        return NodeCreateRequest(
            projectId = repositoryInfo.projectId,
            repoName = repositoryInfo.name,
            folder = false,
            fullPath = context.contextAttributes[NPM_FILE_FULL_PATH] as String,
            size = file.getSize(),
            sha256 = sha256,
            md5 = md5,
            overwrite = true,
            operator = context.userId
        )
    }

    /**
     * 创建临时文件并将响应体写入文件
     */
    protected fun createTempFile(body: ResponseBody): ArtifactFile {
        return ArtifactFileFactory.build(body.byteStream())
    }

    /**
     * 检查下载响应
     */
    private fun checkResponse(response: Response): Boolean {
        if (!response.isSuccessful) {
            logger.warn("Download file from remote failed: [${response.code()}]")
            return false
        }
        return true
    }

    fun dependentMigrate(context: ArtifactMigrateContext) {
        val pkgJsonFile = searchPkgJson(context)
        npmDependentHandler.updatePkgDepts(context.userId, context.artifactInfo, pkgJsonFile, NpmOperationAction.MIGRATION)
    }

    companion object {
        const val TIMEOUT = 60L
        val logger: Logger = LoggerFactory.getLogger(NpmLocalRepository::class.java)
    }
}
