dependencies {
    implementation(project(":common:common-api"))
    implementation(project(":common:common-artifact:artifact-api"))
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework.cloud:spring-cloud-openfeign-core")
}