plugins {
    `java`
}

group = "ink.myata"
version = "rl-build-0.9"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    // Запасной путь без доступа к репозиториям: положи в libs/ jar-файлы с сервера
    // (libraries/org/purpurmc/purpur/purpur-api/*/purpur-api-*.jar, plugins/MythicMobs*.jar).
    // Если libs/ не пустой, онлайн-зависимости не подтягиваются.
    val localLibs = fileTree("libs") { include("*.jar") }
    if (localLibs.files.isNotEmpty()) {
        compileOnly(localLibs)
    } else {
        // Purpur совместим с Paper API
        compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
        // На сервере стоит MythicMobs 5.13.1; версию сверять на mvn.lumine.io
        compileOnly("io.lumine:Mythic-Dist:5.13.1")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("mythicsupport")
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    validateDistributionUrl = false
}
