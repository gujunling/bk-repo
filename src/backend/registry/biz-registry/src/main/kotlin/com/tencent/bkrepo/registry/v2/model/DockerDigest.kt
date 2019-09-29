package com.tencent.bkrepo.registry.v2.model

import org.apache.commons.lang.StringUtils

class DockerDigest(digest: String) {
    var alg: String? = null
        private set
    var hex: String? = null
        private set

    init {
        val sepIndex = StringUtils.indexOf(digest, ":")
        require(sepIndex >= 0) { "could not find ':' in digest: $digest" }
        this.alg = StringUtils.substring(digest, 0, sepIndex)
        this.hex = StringUtils.substring(digest, sepIndex + 1)
    }

    fun filename(): String {
        return this.alg + "__" + this.hex
    }

    override fun toString(): String {
        return this.alg + ":" + this.hex
    }
}
