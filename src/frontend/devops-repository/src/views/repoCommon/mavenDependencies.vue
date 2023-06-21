<template>
    <div>
        <div class="display-block" :data-title="$t('correctDependencies')" v-bkloading="{ isLoading: isCorrectLoading }">
            <bk-table
                class="mt10"
                height="300px"
                :data="correctList"
                :outer-border="false"
                :row-border="false"
                size="small">
                <template #empty>
                    <empty-data :is-loading="isCorrectLoading"></empty-data>
                </template>
                <bk-table-column label="groupId" prop="groupId" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="artifactId" prop="artifactId" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="version" prop="version" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="type" prop="type" show-overflow-tooltip></bk-table-column>
                <bk-table-column label="classifier" prop="classifier" show-overflow-tooltip></bk-table-column>
            </bk-table>
            <bk-pagination
                class="p10"
                size="small"
                align="right"
                show-total-count
                :current.sync="correctDependencies.current"
                :limit="correctDependencies.limit"
                :count="correctDependencies.count"
                :limit-list="correctDependencies.limitList"
                @change="current => handlerCorrectPaginationChange({ current })"
                @limit-change="limit => handlerCorrectPaginationChange({ limit })">
            </bk-pagination>
        </div>
        <div class="display-block" :data-title="$t('plugins')" v-bkloading="{ isLoading: isPluginLoading }">
            <bk-table
                class="mt10"
                height="300px"
                :data="pluginList"
                :outer-border="false"
                :row-border="false"
                size="small">
                <template #empty>
                    <empty-data :is-loading="isPluginLoading"></empty-data>
                </template>
                <bk-table-column label="groupId" prop="groupId" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="artifactId" prop="artifactId" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="version" prop="version" show-overflow-tooltip>
                </bk-table-column>
            </bk-table>
            <bk-pagination
                class="p10"
                size="small"
                align="right"
                show-total-count
                :current.sync="plugins.current"
                :limit="plugins.limit"
                :count="plugins.count"
                :limit-list="plugins.limitList"
                @change="current => handlerPluginsPaginationChange({ current })"
                @limit-change="limit => handlerPluginsPaginationChange({ limit })">
            </bk-pagination>
        </div>
        <div class="display-block" :data-title="$t('reverseDependencies')" v-bkloading="{ isLoading: isReverseLoading }">
            <bk-table
                class="mt10"
                height="300px"
                :data="reverseList"
                :outer-border="false"
                :row-border="false"
                size="small">
                <template #empty>
                    <empty-data :is-loading="isReverseLoading"></empty-data>
                </template>
                <bk-table-column label="groupId" prop="groupId" show-overflow-tooltip>
                    <template slot-scope="{ row }">
                        <span class="hover-btn" @click="onJumpDetail(row)">
                            {{row.groupId}}
                        </span>
                    </template>
                </bk-table-column>
                <bk-table-column label="artifactId" prop="artifactId" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="version" prop="version" show-overflow-tooltip>
                </bk-table-column>
                <bk-table-column label="type" prop="type" show-overflow-tooltip></bk-table-column>
                <bk-table-column label="classifier" prop="classifier" show-overflow-tooltip></bk-table-column>
            </bk-table>
            <bk-pagination
                class="p10"
                size="small"
                location="right"
                align="right"
                show-total-count
                :current.sync="reverseDependencies.current"
                :limit="reverseDependencies.limit"
                :count="reverseDependencies.count"
                :limit-list="reverseDependencies.limitList"
                @change="current => handlerReversePaginationChange({ current })"
                @limit-change="limit => handlerReversePaginationChange({ limit })">
            </bk-pagination>
        </div>
    </div>
</template>

<script>
    import { mapActions } from 'vuex'

    export default {
        data () {
            return {
                // 正向依赖
                correctDependencies: {
                    current: 1,
                    limit: 10,
                    count: 0,
                    limitList: [10, 20, 40]
                },
                correctList: [],
                isCorrectLoading: false,

                // 插件
                plugins: {
                    current: 1,
                    limit: 10,
                    count: 0,
                    limitList: [10, 20, 40]
                },
                pluginList: [],
                isPluginLoading: false,

                // 反向依赖
                reverseDependencies: {
                    current: 1,
                    limit: 10,
                    count: 0,
                    limitList: [10, 20, 40]
                },
                reverseList: [],
                isReverseLoading: false
            }
        },
        computed: {
            projectId () {
                return this.$route.params.projectId || ''
            },
            repoType () {
                return this.$route.params.repoType || ''
            },
            repoName () {
                return this.$route.query.repoName || ''
            },
            packageKey () {
                return this.$route.query.packageKey || ''
            },
            version () {
                return this.$route.query.version || ''
            },
            // 当前仓库类型，本地/远程/虚拟
            storeType () {
                return this.$route.query.storeType || ''
            },
            // 虚拟仓库的仓库来源，虚拟仓库时需要更换repoName为此值
            sourceRepoName () {
                return this.$route.query.sourceName || ''
            }
        },
        created () {
            this.getCorrectList()
            this.getPluginList()
            this.getReverseList()
        },
        methods: {
            ...mapActions(['getCorrectDependencies', 'getCorrectPlugins', 'getReverseDependencies']),
            // 抽离接口请求传参的公共代码，根据传参不同，返回不同的请求参数
            getCommonParams (item) {
                return {
                    projectId: this.projectId,
                    repoName: this.storeType === 'virtual' ? this.sourceRepoName : this.repoName,
                    packageKey: this.packageKey,
                    version: this.version,
                    pageNumber: item?.current || 1,
                    pageSize: item?.limit || 10
                }
            },
            // 抽离接口返回数据的公共代码，根据参数不同，设置不同的数据
            setBackData (list, dependencies, res) {
                this[list] = res.records
                this[dependencies].count = res.count
                this[dependencies].current = res.pageNumber
                this[dependencies].limit = res.pageSize
            },
            // 正向依赖
            handlerCorrectPaginationChange ({ current = 1, limit = this.correctDependencies.limit } = {}) {
                this.correctDependencies.current = current
                this.correctDependencies.limit = limit
                this.getCorrectList()
            },
            // 获取正向依赖列表数据
            getCorrectList () {
                this.isCorrectLoading = true
                this.getCorrectDependencies(this.getCommonParams(this.correctDependencies)).then(res => {
                    this.setBackData('correctList', 'correctDependencies', res)
                }).finally(() => {
                    this.isCorrectLoading = false
                })
            },
            // 插件
            handlerPluginsPaginationChange ({ current = 1, limit = this.plugins.limit } = {}) {
                this.plugins.current = current
                this.plugins.limit = limit
                this.getPluginList()
            },
            // 获取插件列表数据
            getPluginList () {
                this.isPluginLoading = true
                this.getCorrectPlugins(this.getCommonParams(this.plugins)).then(res => {
                    this.setBackData('pluginList', 'plugins', res)
                }).finally(() => {
                    this.isPluginLoading = false
                })
            },
            // 反向依赖
            handlerReversePaginationChange ({ current = 1, limit = this.reverseDependencies.limit } = {}) {
                this.reverseDependencies.current = current
                this.reverseDependencies.limit = limit
                this.getReverseList()
            },
            // 获取反向依赖列表数据
            getReverseList () {
                this.isReverseLoading = true
                this.getReverseDependencies(this.getCommonParams(this.reverseDependencies)).then(res => {
                    this.setBackData('reverseList', 'reverseDependencies', res)
                }).finally(() => {
                    this.isReverseLoading = false
                })
            },
            // 反向依赖列表中点击跳转
            onJumpDetail (row) {
                let frontUrl
                // 集成CI模式下跳转的路径与独立部署模式下的不同，因为底座与制品库的配置，window.location.origin拿到的是iframeURL的值，不是真实访问的域名
                if (MODE_CONFIG === 'ci') {
                    frontUrl = window.DEVOPS_SITE_URL + '/console/repository/' + `${row.projectId || this.projectId || ''}` + '/repoList'
                } else {
                    frontUrl = window.location.origin + '/ui/' + `${row.projectId || this.projectId || ''}`
                }
                const href = `${frontUrl}/${this.repoType}/package?repoName=${row.repoName || this.repoName}&packageKey=${row.packageKey}&version=${row.version}`
                window.open(href, '_blank')
            }
        }
    }
</script>
<style lang="scss" scoped>
// 解决空数据时也会出现滚动条的问题
::v-deep  .bk-table .bk-table-empty-text{
    padding: 20px 0;
}
</style>
