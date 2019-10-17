package com.tencent.bkrepo.repository.api

import com.tencent.bkrepo.common.api.pojo.IdValue
import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.api.pojo.Response
import com.tencent.bkrepo.repository.constant.SERVICE_NAME
import com.tencent.bkrepo.repository.pojo.node.NodeCopyRequest
import com.tencent.bkrepo.repository.pojo.node.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.node.NodeDeleteRequest
import com.tencent.bkrepo.repository.pojo.node.NodeDetail
import com.tencent.bkrepo.repository.pojo.node.NodeInfo
import com.tencent.bkrepo.repository.pojo.node.NodeMoveRequest
import com.tencent.bkrepo.repository.pojo.node.NodeSearchRequest
import com.tencent.bkrepo.repository.pojo.node.NodeSizeInfo
import com.tencent.bkrepo.repository.pojo.node.NodeRenameRequest
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * 资源节点服务接口
 *
 * @author: carrypan
 * @date: 2019-09-10
 */
@Api("节点服务接口")
@FeignClient(SERVICE_NAME, contextId = "NodeResource")
@RequestMapping("/service/node")
interface NodeResource {

    @ApiOperation("根据路径查看节点详情")
    @GetMapping("/query/{projectId}/{repoName}/{repoType}")
    fun queryDetail(
        @ApiParam(value = "所属项目", required = true)
        @PathVariable projectId: String,
        @ApiParam(value = "仓库名称", required = true)
        @PathVariable repoName: String,
        @ApiParam(value = "仓库类型", required = true)
        @PathVariable repoType: String,
        @ApiParam(value = "节点完整路径", required = true)
        @RequestParam fullPath: String
    ): Response<NodeDetail?>

    @ApiOperation("根据路径查看节点详情")
    @GetMapping("/query/{projectId}/{repoName}")
    fun queryDetail(
        @ApiParam(value = "所属项目", required = true)
        @PathVariable projectId: String,
        @ApiParam(value = "仓库名称", required = true)
        @PathVariable repoName: String,
        @ApiParam(value = "节点完整路径", required = true)
        @RequestParam fullPath: String
    ): Response<NodeDetail?>

    @ApiOperation("根据路径查看节点是否存在")
    @GetMapping("/exist/{projectId}/{repoName}")
    fun exist(
        @ApiParam(value = "所属项目", required = true)
        @PathVariable projectId: String,
        @ApiParam(value = "仓库名称", required = true)
        @PathVariable repoName: String,
        @ApiParam(value = "节点完整路径", required = true)
        @RequestParam fullPath: String
    ): Response<Boolean>

    @ApiOperation("列表查询指定目录下所有节点")
    @GetMapping("/list/{projectId}/{repoName}")
    fun list(
        @ApiParam(value = "所属项目", required = true)
        @PathVariable projectId: String,
        @ApiParam(value = "仓库名称", required = true)
        @PathVariable repoName: String,
        @ApiParam(value = "所属目录", required = true)
        @RequestParam path: String,
        @ApiParam(value = "是否包含目录", required = false, defaultValue = "true")
        @RequestParam includeFolder: Boolean = true,
        @ApiParam(value = "是否深度查询文件", required = false, defaultValue = "false")
        @RequestParam deep: Boolean = false
    ): Response<List<NodeInfo>>

    @ApiOperation("分页查询指定目录下所有节点")
    @GetMapping("/page/{projectId}/{repoName}/{page}/{size}")
    fun page(
        @ApiParam(value = "所属项目", required = true)
        @PathVariable projectId: String,
        @ApiParam(value = "仓库名称", required = true)
        @PathVariable repoName: String,
        @ApiParam(value = "当前页", required = true, example = "0")
        @PathVariable page: Int,
        @ApiParam(value = "分页大小", required = true, example = "20")
        @PathVariable size: Int,
        @ApiParam(value = "所属目录", required = true)
        @RequestParam path: String,
        @ApiParam(value = "是否包含目录", required = false, defaultValue = "true")
        @RequestParam includeFolder: Boolean = true,
        @ApiParam(value = "是否深度查询文件", required = false, defaultValue = "false")
        @RequestParam deep: Boolean = false
    ): Response<Page<NodeInfo>>

    @ApiOperation("搜索文件")
    @PostMapping("/search")
    fun search(
        @RequestBody nodeSearchRequest: NodeSearchRequest
    ): Response<List<NodeInfo>>

    @ApiOperation("创建节点")
    @PostMapping
    fun create(
        @RequestBody nodeCreateRequest: NodeCreateRequest
    ): Response<IdValue>

    @ApiOperation("重命名节点")
    @PutMapping("/rename")
    fun rename(
        @RequestBody nodeRenameRequest: NodeRenameRequest
    ): Response<Void>

    @ApiOperation("移动节点")
    @PutMapping("/move")
    fun move(
        @RequestBody nodeMoveRequest: NodeMoveRequest
    ): Response<Void>

    @ApiOperation("复制节点")
    @PutMapping("/copy")
    fun copy(
        @RequestBody nodeCopyRequest: NodeCopyRequest
    ): Response<Void>

    @ApiOperation("删除节点")
    @DeleteMapping("/delete")
    fun delete(
        @RequestBody nodeDeleteRequest: NodeDeleteRequest
    ): Response<Void>

    @ApiOperation("查询节点大小信息")
    @GetMapping("/size/{projectId}/{repoName}")
    fun getSize(
        @ApiParam(value = "所属项目", required = true)
        @PathVariable projectId: String,
        @ApiParam(value = "仓库名称", required = true)
        @PathVariable repoName: String,
        @ApiParam(value = "节点完整路径", required = true)
        @RequestParam fullPath: String
    ): Response<NodeSizeInfo>
}
