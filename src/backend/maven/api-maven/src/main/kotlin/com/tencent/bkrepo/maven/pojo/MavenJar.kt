package com.tencent.bkrepo.maven.pojo

data class MavenJar(
    val name: String?,
    override val groupId: String,
    override val artifactId: String,
    override val version: String,
    val packaging: String? = "jar"
) : MavenGAVC(groupId, artifactId, version)
