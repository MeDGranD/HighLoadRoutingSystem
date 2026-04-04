import com.google.protobuf.gradle.id

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.5"
}

group = "ru.ilyubarskiy.mai"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(24)
	}
}

repositories {
	mavenCentral()
}

extra["springGrpcVersion"] = "1.0.2"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("io.grpc:grpc-services")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.grpc:spring-grpc-client-spring-boot-starter")
	implementation("org.springframework.grpc:spring-grpc-server-spring-boot-starter")
	implementation("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.grpc:spring-grpc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	implementation("io.grpc:grpc-kotlin-stub:1.4.1")
	implementation("io.grpc:grpc-stub:1.62.2")
	implementation("io.grpc:grpc-protobuf:1.62.2")
	implementation("io.grpc:grpc-netty-shaded:1.62.2")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.grpc:spring-grpc-dependencies:${property("springGrpcVersion")}")
	}
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
