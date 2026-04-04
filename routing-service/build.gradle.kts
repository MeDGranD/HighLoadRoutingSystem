import com.google.protobuf.gradle.id

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.5"
}

group = "ru.ilyubarskiy.mai"
version = "0.0.1-SNAPSHOT"
description = "Routing service"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springGrpcVersion"] = "1.0.2"

dependencies {

	implementation("org.jetbrains.kotlin:kotlin-reflect")

	implementation("io.grpc:grpc-services:1.77.1")

	implementation("io.grpc:grpc-kotlin-stub:1.4.1")
	implementation("io.grpc:grpc-stub:1.62.2")
	implementation("io.grpc:grpc-protobuf:1.62.2")
	implementation("io.grpc:grpc-netty-shaded:1.62.2")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

	implementation("org.springframework.grpc:spring-grpc-client-spring-boot-starter:1.0.1")
	implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter:1.0.1")

	implementation("com.graphhopper:graphhopper-core:11.0")

	implementation("io.minio:minio:8.5.15")

	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	implementation("net.javacrumbs.shedlock:shedlock-spring:5.13.0")
	implementation("net.javacrumbs.shedlock:shedlock-provider-redis-spring:5.13.0")

	//Redis
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.apache.commons:commons-pool2")

	implementation("org.jgrapht:jgrapht-core:1.5.2")

	implementation("org.springframework.kafka:spring-kafka")

	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
	testImplementation("org.springframework.grpc:spring-grpc-test:1.0.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.2")
	testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.3")

}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

protobuf {
	protoc {
		artifact = "com.google.protobuf:protoc:3.25.3"
	}
	plugins {
		id("grpc") {
			artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
		}
		id("grpckt") {
			artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
		}
	}
	generateProtoTasks {
		all().forEach {
			it.plugins {
				id("grpc") {
					option("@generated=omit")
				}
				id("grpckt")
			}
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
