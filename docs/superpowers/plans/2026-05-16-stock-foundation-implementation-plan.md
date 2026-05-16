# Stock Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Foundation-first backend for `stock-web-v2`: multi-module structure, environment-driven YAML config, common API/error contracts, Flyway schema, Redis-backed auth, seed asset query, Actuator, OpenAPI, and integration tests.

**Architecture:** Keep Spring Boot `4.0.4` and convert the current single-module skeleton into a modular monolith foundation. Runtime configuration is supplied by `.env` and environment variables; `application.yaml`, `application-dev.yaml`, and `application-demo.yaml` contain only shared settings and `${...}` expressions. Business scope is limited to Auth and seed Asset queries.

**Tech Stack:** Java 21, Spring Boot 4.0.4, Spring WebMVC, Spring Security, Spring Data JDBC, Flyway, PostgreSQL/TimescaleDB, Redis, springdoc OpenAPI, Testcontainers, JUnit 5, AssertJ.

---

## Scope Check

This plan implements only the approved Foundation-first spec in `docs/superpowers/specs/2026-05-16-stock-foundation-design.md`.

Out of scope for this plan:

- Portfolio / BrokerAccount / Cash Balance.
- Trades / Holdings / weighted-average cost.
- Watchlist persistence.
- Overview aggregation.
- WebSocket / market-data history / K-line.
- Alerts / Notifications.
- Backtest / Analytics.
- Broker API key storage.
- AI/MCP access.

Boot 4.x is retained. Do not downgrade Spring Boot as part of this plan.

## File Structure Map

Create this structure:

```text
stock-web-v2/
├── .env.example
├── pom.xml
├── scripts/
│   └── run-dev.ps1
├── stock-common/
│   ├── pom.xml
│   └── src/
│       ├── main/java/dowob/xyz/stockwebv2/common/
│       │   ├── api/ApiError.java
│       │   ├── api/ApiMeta.java
│       │   ├── api/ApiResponse.java
│       │   ├── api/EmptyResponse.java
│       │   ├── api/PageResponse.java
│       │   ├── error/BusinessException.java
│       │   ├── error/DuplicateResourceException.java
│       │   ├── error/ErrorCode.java
│       │   ├── error/ResourceNotFoundException.java
│       │   └── model/*.java
│       └── test/java/dowob/xyz/stockwebv2/common/
│           └── api/ApiResponseTest.java
├── stock-db-migration/
│   ├── pom.xml
│   └── src/main/resources/db/migration/
│       ├── V1__foundation_schema.sql
│       └── V2__foundation_seed_assets.sql
├── stock-infrastructure/
│   ├── pom.xml
│   └── src/main/java/dowob/xyz/stockwebv2/infrastructure/
│       ├── config/PasswordConfig.java
│       ├── config/RedisConfig.java
│       ├── event/DomainEvent.java
│       ├── event/EventPublisher.java
│       ├── event/EventSubscriber.java
│       ├── search/SearchService.java
│       ├── security/JwtProperties.java
│       ├── security/JwtService.java
│       └── web/TraceIdFilter.java
├── stock-module-user/
│   ├── pom.xml
│   └── src/main/java/dowob/xyz/stockwebv2/user/
│       ├── api/*.java
│       ├── domain/*.java
│       ├── repository/UserRepository.java
│       └── service/*.java
├── stock-module-asset/
│   ├── pom.xml
│   └── src/main/java/dowob/xyz/stockwebv2/asset/
│       ├── api/*.java
│       ├── domain/*.java
│       ├── repository/*.java
│       └── service/*.java
└── stock-start/
    ├── pom.xml
    └── src/
        ├── main/java/dowob/xyz/stockwebv2/start/
        │   ├── StockWebV2Application.java
        │   ├── config/SecurityConfig.java
        │   ├── error/GlobalExceptionHandler.java
        │   └── support/TestOnlyController.java
        ├── main/resources/
        │   ├── application.yaml
        │   ├── application-dev.yaml
        │   └── application-demo.yaml
        └── test/java/dowob/xyz/stockwebv2/start/
            ├── FoundationApplicationIT.java
            └── support/ContainerIT.java
```

Delete or move the original single-module files after the new `stock-start` module is compiling:

- `src/main/java/dowob/xyz/stockwebv2/StockWebV2Application.java`
- `src/main/resources/application.properties`
- `src/test/java/dowob/xyz/stockwebv2/StockWebV2ApplicationTests.java`

Preserve any unrelated user changes when editing existing files.

---

## Task 1: Maven Multi-Module Skeleton

**Files:**
- Modify: `pom.xml`
- Create: `stock-common/pom.xml`
- Create: `stock-db-migration/pom.xml`
- Create: `stock-infrastructure/pom.xml`
- Create: `stock-module-user/pom.xml`
- Create: `stock-module-asset/pom.xml`
- Create: `stock-start/pom.xml`
- Move/Create: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`
- Delete after replacement: `src/main/java/dowob/xyz/stockwebv2/StockWebV2Application.java`
- Delete after replacement: `src/main/resources/application.properties`
- Delete after replacement: `src/test/java/dowob/xyz/stockwebv2/StockWebV2ApplicationTests.java`

- [ ] **Step 1: Run the failing module build check**

Run:

```powershell
.\mvnw.cmd -q -pl stock-common test
```

Expected: FAIL because `stock-common` is not a Maven module yet.

- [ ] **Step 2: Replace the root `pom.xml` with a parent POM**

Use this complete `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.4</version>
        <relativePath/>
    </parent>

    <groupId>dowob.xyz</groupId>
    <artifactId>stock-web-v2</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>stock-web-v2</name>

    <modules>
        <module>stock-common</module>
        <module>stock-db-migration</module>
        <module>stock-infrastructure</module>
        <module>stock-module-user</module>
        <module>stock-module-asset</module>
        <module>stock-start</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <springdoc.version>3.0.2</springdoc.version>
        <testcontainers.version>1.20.6</testcontainers.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <release>${java.version}</release>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 3: Create module POM files**

Create `stock-common/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-common</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `stock-db-migration/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-db-migration</artifactId>
</project>
```

Create `stock-infrastructure/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-infrastructure</artifactId>
    <dependencies>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-jose</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `stock-module-user/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-module-user</artifactId>
    <dependencies>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-infrastructure</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `stock-module-asset/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-module-asset</artifactId>
    <dependencies>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create `stock-start/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dowob.xyz</groupId>
        <artifactId>stock-web-v2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>stock-start</artifactId>
    <dependencies>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-db-migration</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-infrastructure</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-module-user</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>stock-module-asset</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 4: Create the new application entrypoint**

Create `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`:

```java
package dowob.xyz.stockwebv2.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dowob.xyz.stockwebv2")
public class StockWebV2Application {

    public static void main(String[] args) {
        SpringApplication.run(StockWebV2Application.class, args);
    }
}
```

- [ ] **Step 5: Remove the old single-module application files**

Delete:

```text
src/main/java/dowob/xyz/stockwebv2/StockWebV2Application.java
src/main/resources/application.properties
src/test/java/dowob/xyz/stockwebv2/StockWebV2ApplicationTests.java
```

- [ ] **Step 6: Run the module build check**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -am test
```

Expected: PASS with no tests executed or only generated empty module output.

- [ ] **Step 7: Commit**

```powershell
git add pom.xml stock-common stock-db-migration stock-infrastructure stock-module-user stock-module-asset stock-start src
git commit -m "build: split stock foundation modules"
```

---

## Task 2: Environment-Driven YAML and Dev Run Script

**Files:**
- Modify: `.gitignore`
- Create: `.env.example`
- Create: `scripts/run-dev.ps1`
- Create: `stock-start/src/main/resources/application.yaml`
- Create: `stock-start/src/main/resources/application-dev.yaml`
- Create: `stock-start/src/main/resources/application-demo.yaml`

- [ ] **Step 1: Write the failing config file checks**

Run:

```powershell
Test-Path .\.env.example
Test-Path .\scripts\run-dev.ps1
Test-Path .\stock-start\src\main\resources\application.yaml
Test-Path .\stock-start\src\main\resources\application-dev.yaml
Test-Path .\stock-start\src\main\resources\application-demo.yaml
```

Expected: at least one `False`.

- [ ] **Step 2: Ensure `.env` is ignored**

Append these lines to `.gitignore` only if they are not already present:

```gitignore
# Local runtime environment
.env
.env.*
!.env.example
```

- [ ] **Step 3: Create `.env.example`**

Create `.env.example`:

```properties
SPRING_PROFILES_ACTIVE=dev

STOCK_DB_URL=jdbc:postgresql://10.0.0.214:30120/stock_v2_db
STOCK_DB_USERNAME=
STOCK_DB_PASSWORD=

STOCK_REDIS_HOST=10.0.0.214
STOCK_REDIS_PORT=30121
STOCK_REDIS_DATABASE=1
STOCK_REDIS_PASSWORD=

STOCK_JWT_PRIVATE_KEY=
STOCK_JWT_ACCESS_TOKEN_TTL=PT30M
STOCK_JWT_REFRESH_TOKEN_TTL=P14D

STOCK_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:4200
STOCK_MANAGEMENT_PORT=11181
```

- [ ] **Step 4: Create `application.yaml`**

Create `stock-start/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: stock-web-v2
  profiles:
    default: dev
  jackson:
    time-zone: Asia/Taipei
    serialization:
      write-dates-as-timestamps: false

server:
  port: ${SERVER_PORT:11180}

management:
  server:
    port: ${STOCK_MANAGEMENT_PORT:11181}
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized

springdoc:
  api-docs:
    enabled: ${STOCK_OPENAPI_ENABLED:true}
  swagger-ui:
    enabled: ${STOCK_SWAGGER_UI_ENABLED:true}

stock:
  cors:
    allowed-origins: ${STOCK_CORS_ALLOWED_ORIGINS:http://localhost:5173}
  jwt:
    private-key: ${STOCK_JWT_PRIVATE_KEY:}
    access-token-ttl: ${STOCK_JWT_ACCESS_TOKEN_TTL:PT30M}
    refresh-token-ttl: ${STOCK_JWT_REFRESH_TOKEN_TTL:P14D}
```

- [ ] **Step 5: Create `application-dev.yaml`**

Create `stock-start/src/main/resources/application-dev.yaml`:

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
  datasource:
    url: ${STOCK_DB_URL}
    username: ${STOCK_DB_USERNAME}
    password: ${STOCK_DB_PASSWORD}
  data:
    redis:
      host: ${STOCK_REDIS_HOST}
      port: ${STOCK_REDIS_PORT}
      database: ${STOCK_REDIS_DATABASE:1}
      password: ${STOCK_REDIS_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration

logging:
  level:
    dowob.xyz.stockwebv2: DEBUG
```

- [ ] **Step 6: Create `application-demo.yaml`**

Create `stock-start/src/main/resources/application-demo.yaml`:

```yaml
# Demo/reference profile. All runtime values come from environment variables.
# Required: STOCK_DB_URL, STOCK_DB_USERNAME, STOCK_DB_PASSWORD.
# Example STOCK_DB_URL: jdbc:postgresql://10.0.0.214:30120/stock_v2_db
# Required: STOCK_REDIS_HOST, STOCK_REDIS_PORT.
# Optional: STOCK_REDIS_DATABASE defaults to 1.
# Optional: STOCK_REDIS_PASSWORD is empty for Redis instances without requirepass.
# Optional for dev/test/e2e only: STOCK_JWT_PRIVATE_KEY. Required outside dev/test/e2e.
# Optional: STOCK_CORS_ALLOWED_ORIGINS. Comma-separated frontend origins.

spring:
  datasource:
    url: ${STOCK_DB_URL}
    username: ${STOCK_DB_USERNAME}
    password: ${STOCK_DB_PASSWORD}
  data:
    redis:
      host: ${STOCK_REDIS_HOST}
      port: ${STOCK_REDIS_PORT}
      database: ${STOCK_REDIS_DATABASE:1}
      password: ${STOCK_REDIS_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration

springdoc:
  api-docs:
    enabled: ${STOCK_OPENAPI_ENABLED:true}
  swagger-ui:
    enabled: ${STOCK_SWAGGER_UI_ENABLED:true}
```

- [ ] **Step 7: Create the dev run script**

Create `scripts/run-dev.ps1`:

```powershell
param(
    [string]$Module = "stock-start"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw ".env not found at $envFile. Create it from .env.example and fill required values."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith("#")) {
        return
    }
    $idx = $line.IndexOf("=")
    if ($idx -le 0) {
        throw "Invalid .env line: $line"
    }
    $key = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1).Trim()
    [Environment]::SetEnvironmentVariable($key, $value, "Process")
}

if (-not $env:SPRING_PROFILES_ACTIVE) {
    $env:SPRING_PROFILES_ACTIVE = "dev"
}

Push-Location $repoRoot
try {
    & .\mvnw.cmd -pl $Module -am spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "spring-boot:run failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
```

- [ ] **Step 8: Verify config files and script exist**

Run:

```powershell
Test-Path .\.env.example
Test-Path .\scripts\run-dev.ps1
Test-Path .\stock-start\src\main\resources\application.yaml
Test-Path .\stock-start\src\main\resources\application-dev.yaml
Test-Path .\stock-start\src\main\resources\application-demo.yaml
```

Expected: all values are `True`.

- [ ] **Step 9: Commit**

```powershell
git add .gitignore .env.example scripts/run-dev.ps1 stock-start/src/main/resources
git commit -m "chore: add environment driven runtime config"
```

---

## Task 3: Common API, Error, and Model Contracts

**Files:**
- Create: `stock-common/src/test/java/dowob/xyz/stockwebv2/common/api/ApiResponseTest.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiError.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiMeta.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/EmptyResponse.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/PageResponse.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ResourceNotFoundException.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/DuplicateResourceException.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/Role.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/Permission.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/UserStatus.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/AssetType.java`
- Create: `stock-common/src/main/java/dowob/xyz/stockwebv2/common/model/CurrencyCode.java`

- [ ] **Step 1: Write the failing common contract tests**

Create `stock-common/src/test/java/dowob/xyz/stockwebv2/common/api/ApiResponseTest.java`:

```java
package dowob.xyz.stockwebv2.common.api;

import dowob.xyz.stockwebv2.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void successWrapsDataAndMeta() {
        ApiMeta meta = new ApiMeta("trace-1", OffsetDateTime.parse("2026-05-16T10:30:00+08:00"));

        ApiResponse<String> response = ApiResponse.success("ok", meta);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.error()).isNull();
        assertThat(response.meta()).isEqualTo(meta);
    }

    @Test
    void failureWrapsErrorAndMeta() {
        ApiMeta meta = new ApiMeta("trace-2", OffsetDateTime.parse("2026-05-16T10:31:00+08:00"));
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, "Validation failed", Map.of("email", "invalid"));

        ApiResponse<Void> response = ApiResponse.failure(error, meta);

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.error().fields()).containsEntry("email", "invalid");
        assertThat(response.meta().traceId()).isEqualTo("trace-2");
    }

    @Test
    void pageResponseCarriesItemsAndTotals() {
        PageResponse<String> page = PageResponse.of(List.of("AAPL", "NVDA"), 0, 2, 5);

        assertThat(page.items()).containsExactly("AAPL", "NVDA");
        assertThat(page.page()).isEqualTo(0);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void errorCodeContainsHttpStatus() {
        assertThat(ErrorCode.AUTH_REDIS_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.httpStatus()).isEqualTo(404);
    }
}
```

- [ ] **Step 2: Run the common tests to verify they fail**

Run:

```powershell
.\mvnw.cmd -q -pl stock-common test
```

Expected: FAIL because `ApiResponse`, `ApiMeta`, `ApiError`, `PageResponse`, and `ErrorCode` do not exist.

- [ ] **Step 3: Add API records**

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiMeta.java`:

```java
package dowob.xyz.stockwebv2.common.api;

import java.time.OffsetDateTime;

public record ApiMeta(String traceId, OffsetDateTime timestamp) {
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiError.java`:

```java
package dowob.xyz.stockwebv2.common.api;

import dowob.xyz.stockwebv2.common.error.ErrorCode;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> fields) {

    public static ApiError of(ErrorCode code, String message, Map<String, String> fields) {
        return new ApiError(code.name(), message, fields == null ? Map.of() : Map.copyOf(fields));
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/ApiResponse.java`:

```java
package dowob.xyz.stockwebv2.common.api;

public record ApiResponse<T>(boolean success, T data, ApiError error, ApiMeta meta) {

    public static <T> ApiResponse<T> success(T data, ApiMeta meta) {
        return new ApiResponse<>(true, data, null, meta);
    }

    public static ApiResponse<EmptyResponse> empty(ApiMeta meta) {
        return success(EmptyResponse.INSTANCE, meta);
    }

    public static <T> ApiResponse<T> failure(ApiError error, ApiMeta meta) {
        return new ApiResponse<>(false, null, error, meta);
    }
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/EmptyResponse.java`:

```java
package dowob.xyz.stockwebv2.common.api;

public enum EmptyResponse {
    INSTANCE
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/api/PageResponse.java`:

```java
package dowob.xyz.stockwebv2.common.api;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / (double) size);
        return new PageResponse<>(List.copyOf(items), page, size, totalElements, totalPages);
    }
}
```

- [ ] **Step 4: Add error and enum contracts**

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ErrorCode.java`:

```java
package dowob.xyz.stockwebv2.common.error;

public enum ErrorCode {
    VALIDATION_FAILED(400, "Validation failed"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    AUTH_INVALID_CREDENTIALS(401, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED(401, "Access token expired"),
    AUTH_REFRESH_TOKEN_INVALID(401, "Refresh token invalid"),
    AUTH_FORBIDDEN(403, "Forbidden"),
    AUTH_REDIS_UNAVAILABLE(503, "Authentication state unavailable"),
    DUPLICATE_RESOURCE(409, "Duplicate resource"),
    INTERNAL_ERROR(500, "Internal server error");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/BusinessException.java`:

```java
package dowob.xyz.stockwebv2.common.error;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/ResourceNotFoundException.java`:

```java
package dowob.xyz.stockwebv2.common.error;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resourceName) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resourceName + " not found");
    }
}
```

Create `stock-common/src/main/java/dowob/xyz/stockwebv2/common/error/DuplicateResourceException.java`:

```java
package dowob.xyz.stockwebv2.common.error;

public class DuplicateResourceException extends BusinessException {
    public DuplicateResourceException(String resourceName) {
        super(ErrorCode.DUPLICATE_RESOURCE, resourceName + " already exists");
    }
}
```

Create `Role.java`, `Permission.java`, `UserStatus.java`, `AssetType.java`, and `CurrencyCode.java`:

```java
package dowob.xyz.stockwebv2.common.model;

public enum Role {
    USER,
    ADMIN
}
```

```java
package dowob.xyz.stockwebv2.common.model;

public enum Permission {
    WATCHLIST_MANAGE,
    TRADE_EXECUTE,
    PORTFOLIO_VIEW,
    PROFILE_EDIT,
    ASSET_ADMIN
}
```

```java
package dowob.xyz.stockwebv2.common.model;

public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}
```

```java
package dowob.xyz.stockwebv2.common.model;

public enum AssetType {
    STOCK,
    CRYPTO,
    FX,
    BOND
}
```

```java
package dowob.xyz.stockwebv2.common.model;

public enum CurrencyCode {
    USD,
    TWD,
    EUR,
    JPY
}
```

- [ ] **Step 5: Run common tests**

Run:

```powershell
.\mvnw.cmd -q -pl stock-common test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add stock-common
git commit -m "feat: add common api and error contracts"
```

---

## Task 4: Global Error Handling and Trace IDs

**Files:**
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/TraceIdFilter.java`
- Create: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`
- Create: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/support/TestOnlyController.java`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ErrorHandlingIT.java`

- [ ] **Step 1: Write the failing error handling integration test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/ErrorHandlingIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorHandlingIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void businessExceptionReturnsApiResponseAndTraceId() throws Exception {
        mockMvc.perform(get("/test-only/error/business").header("X-Trace-Id", "trace-test-1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success", equalTo(false)))
            .andExpect(jsonPath("$.data").isEmpty())
            .andExpect(jsonPath("$.error.code", equalTo("DUPLICATE_RESOURCE")))
            .andExpect(jsonPath("$.meta.traceId", equalTo("trace-test-1")))
            .andExpect(jsonPath("$.meta.timestamp", notNullValue()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=ErrorHandlingIT test
```

Expected: FAIL because `TestOnlyController`, `TraceIdFilter`, and `GlobalExceptionHandler` do not exist.

- [ ] **Step 3: Add trace ID filter**

Create `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/web/TraceIdFilter.java`:

```java
package dowob.xyz.stockwebv2.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String TRACE_ID = "traceId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
```

- [ ] **Step 4: Add global exception handler**

Create `stock-start/src/main/java/dowob/xyz/stockwebv2/start/error/GlobalExceptionHandler.java`:

```java
package dowob.xyz.stockwebv2.start.error;

import dowob.xyz.stockwebv2.common.api.ApiError;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        ApiError error = ApiError.of(code, exception.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.failure(error, meta()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fields.put(error.getField(), error.getDefaultMessage())
        );
        ApiError error = ApiError.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fields);
        return ResponseEntity.badRequest().body(ApiResponse.failure(error, meta()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
        return ResponseEntity.status(500).body(ApiResponse.failure(error, meta()));
    }

    private ApiMeta meta() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return new ApiMeta(traceId == null ? "missing-trace-id" : traceId, OffsetDateTime.now());
    }
}
```

- [ ] **Step 5: Add test-only controller under test profile**

Create `stock-start/src/main/java/dowob/xyz/stockwebv2/start/support/TestOnlyController.java`:

```java
package dowob.xyz.stockwebv2.start.support;

import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test-only/error")
@Profile("test")
class TestOnlyController {

    @GetMapping("/business")
    void business() {
        throw new DuplicateResourceException("test-resource");
    }
}
```

- [ ] **Step 6: Add a test profile YAML**

Create `stock-start/src/test/resources/application-test.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
    username: sa
    password:
  flyway:
    enabled: false
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
      - org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
stock:
  jwt:
    private-key:
    access-token-ttl: PT30M
    refresh-token-ttl: P14D
```

If H2 is not available from the current dependencies, add this test dependency to `stock-start/pom.xml`:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 7: Run the error handling test**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=ErrorHandlingIT test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add stock-infrastructure stock-start
git commit -m "feat: add api error handling and trace ids"
```

---

## Task 5: Foundation Flyway Schema and Seed Assets

**Files:**
- Create: `stock-db-migration/src/main/resources/db/migration/V1__foundation_schema.sql`
- Create: `stock-db-migration/src/main/resources/db/migration/V2__foundation_seed_assets.sql`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/FoundationMigrationIT.java`

- [ ] **Step 1: Write the failing migration integration test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/support/ContainerIT.java`:

```java
package dowob.xyz.stockwebv2.start.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
public abstract class ContainerIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres")
    )
        .withDatabaseName("stock_v2_test")
        .withUsername("stock")
        .withPassword("stock");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> 0);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
}
```

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/FoundationMigrationIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationMigrationIT extends ContainerIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesFoundationTablesAndSeedsAssets() {
        Integer users = jdbcTemplate.queryForObject("select count(*) from information_schema.tables where table_name = 'users'", Integer.class);
        Integer assets = jdbcTemplate.queryForObject("select count(*) from assets", Integer.class);
        Integer prices = jdbcTemplate.queryForObject("select count(*) from asset_latest_prices", Integer.class);
        Integer fx = jdbcTemplate.queryForObject("select count(*) from fx_rates", Integer.class);

        assertThat(users).isEqualTo(1);
        assertThat(assets).isGreaterThanOrEqualTo(17);
        assertThat(prices).isGreaterThanOrEqualTo(17);
        assertThat(fx).isGreaterThanOrEqualTo(3);
    }
}
```

- [ ] **Step 2: Run the migration test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=FoundationMigrationIT test
```

Expected: FAIL because the migration SQL does not exist.

- [ ] **Step 3: Add the foundation schema migration**

Create `stock-db-migration/src/main/resources/db/migration/V1__foundation_schema.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    token_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_uuid UNIQUE (uuid),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE assets (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL DEFAULT uuid_generate_v4(),
    symbol VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    asset_type VARCHAR(30) NOT NULL,
    market VARCHAR(50),
    currency VARCHAR(10) NOT NULL,
    sector VARCHAR(100),
    tradeable BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_assets_uuid UNIQUE (uuid),
    CONSTRAINT uk_assets_symbol UNIQUE (symbol)
);

CREATE TABLE asset_latest_prices (
    asset_id BIGINT PRIMARY KEY REFERENCES assets(id),
    price NUMERIC(24, 8) NOT NULL,
    change NUMERIC(24, 8),
    change_percent NUMERIC(12, 6),
    volume_text VARCHAR(50),
    high NUMERIC(24, 8),
    low NUMERIC(24, 8),
    price_time TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE fx_rates (
    id BIGSERIAL PRIMARY KEY,
    base_currency VARCHAR(10) NOT NULL,
    quote_currency VARCHAR(10) NOT NULL,
    rate NUMERIC(24, 8) NOT NULL,
    rate_time TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_fx_rates_pair UNIQUE (base_currency, quote_currency)
);
```

- [ ] **Step 4: Add seed asset migration**

Create `stock-db-migration/src/main/resources/db/migration/V2__foundation_seed_assets.sql`:

```sql
INSERT INTO assets(symbol, name, asset_type, market, currency, sector, tradeable, active)
VALUES
('AAPL', 'Apple Inc.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('NVDA', 'NVIDIA Corp.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('TSLA', 'Tesla, Inc.', 'STOCK', 'US', 'USD', 'Auto', TRUE, TRUE),
('MSFT', 'Microsoft Corp.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('2330.TW', '台積電 TSMC', 'STOCK', 'TW', 'TWD', 'Tech', TRUE, TRUE),
('GOOGL', 'Alphabet Inc.', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('AMZN', 'Amazon.com', 'STOCK', 'US', 'USD', 'Retail', TRUE, TRUE),
('META', 'Meta Platforms', 'STOCK', 'US', 'USD', 'Tech', TRUE, TRUE),
('BTC', 'Bitcoin', 'CRYPTO', 'CRYPTO', 'USD', 'Crypto', TRUE, TRUE),
('ETH', 'Ethereum', 'CRYPTO', 'CRYPTO', 'USD', 'Crypto', TRUE, TRUE),
('SOL', 'Solana', 'CRYPTO', 'CRYPTO', 'USD', 'Crypto', TRUE, TRUE),
('USD/TWD', '美元/新台幣', 'FX', 'FX', 'TWD', NULL, FALSE, TRUE),
('EUR/USD', '歐元/美元', 'FX', 'FX', 'USD', NULL, FALSE, TRUE),
('USD/JPY', '美元/日圓', 'FX', 'FX', 'JPY', NULL, FALSE, TRUE),
('US10Y', 'US 10-Year Treasury', 'BOND', 'US', 'USD', 'Bond', FALSE, TRUE),
('US2Y', 'US 2-Year Treasury', 'BOND', 'US', 'USD', 'Bond', FALSE, TRUE),
('DE10Y', 'German 10-Year Bund', 'BOND', 'DE', 'EUR', 'Bond', FALSE, TRUE),
('JP10Y', 'Japan 10-Year', 'BOND', 'JP', 'JPY', 'Bond', FALSE, TRUE),
('TW10Y', '中華民國 10-Year', 'BOND', 'TW', 'TWD', 'Bond', FALSE, TRUE)
ON CONFLICT (symbol) DO NOTHING;

INSERT INTO asset_latest_prices(asset_id, price, change, change_percent, volume_text, high, low, price_time)
SELECT id, price, change, change_percent, volume_text, high, low, NOW()
FROM (
    VALUES
    ('AAPL', 218.40, 1.42, 0.66, '52.1M', 219.10, 215.80),
    ('NVDA', 1142.83, 28.40, 2.55, '38.4M', 1148.20, 1118.00),
    ('TSLA', 178.22, -3.18, -1.75, '94.3M', 182.60, 177.40),
    ('MSFT', 432.85, 2.10, 0.49, '18.2M', 433.90, 430.10),
    ('2330.TW', 945.00, 12.00, 1.29, '32.0M', 950.00, 932.00),
    ('GOOGL', 174.62, 0.84, 0.48, '21.0M', 175.10, 172.80),
    ('AMZN', 192.34, -1.04, -0.54, '32.4M', 195.20, 191.40),
    ('META', 514.20, 4.32, 0.85, '14.8M', 516.00, 508.40),
    ('BTC', 67842.40, 1842.10, 2.79, '$28.4B', 68120.00, 65400.00),
    ('ETH', 3482.18, 84.20, 2.48, '$14.2B', 3510.00, 3380.00),
    ('SOL', 168.42, -2.18, -1.28, '$3.4B', 172.20, 166.80),
    ('USD/TWD', 32.418, 0.024, 0.07, NULL, 32.450, 32.380),
    ('EUR/USD', 1.0832, -0.0014, -0.13, NULL, 1.0851, 1.0820),
    ('USD/JPY', 156.42, 0.32, 0.20, NULL, 156.80, 155.90),
    ('US10Y', 4.218, 0.014, 0.014, NULL, NULL, NULL),
    ('US2Y', 4.842, -0.012, -0.012, NULL, NULL, NULL),
    ('DE10Y', 2.458, 0.008, 0.008, NULL, NULL, NULL),
    ('JP10Y', 0.984, 0.018, 0.018, NULL, NULL, NULL),
    ('TW10Y', 1.642, 0.004, 0.004, NULL, NULL, NULL)
) AS seed(symbol, price, change, change_percent, volume_text, high, low)
JOIN assets a ON a.symbol = seed.symbol
ON CONFLICT (asset_id) DO UPDATE SET
    price = EXCLUDED.price,
    change = EXCLUDED.change,
    change_percent = EXCLUDED.change_percent,
    volume_text = EXCLUDED.volume_text,
    high = EXCLUDED.high,
    low = EXCLUDED.low,
    price_time = EXCLUDED.price_time,
    updated_at = NOW();

INSERT INTO fx_rates(base_currency, quote_currency, rate, rate_time)
VALUES
('USD', 'TWD', 32.418, NOW()),
('EUR', 'USD', 1.0832, NOW()),
('USD', 'JPY', 156.42, NOW()),
('TWD', 'USD', 0.030847, NOW())
ON CONFLICT (base_currency, quote_currency) DO UPDATE SET
    rate = EXCLUDED.rate,
    rate_time = EXCLUDED.rate_time,
    updated_at = NOW();
```

- [ ] **Step 5: Run migration test**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=FoundationMigrationIT test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add stock-db-migration stock-start/src/test
git commit -m "feat: add foundation database migrations"
```

---

## Task 6: Infrastructure Security, Redis, and JWT Services

**Files:**
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/config/PasswordConfig.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/config/RedisConfig.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtProperties.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/security/JwtService.java`
- Create: `stock-infrastructure/src/test/java/dowob/xyz/stockwebv2/infrastructure/security/JwtServiceTest.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/event/DomainEvent.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/event/EventPublisher.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/event/EventSubscriber.java`
- Create: `stock-infrastructure/src/main/java/dowob/xyz/stockwebv2/infrastructure/search/SearchService.java`

- [ ] **Step 1: Write failing JWT service tests**

Create `stock-infrastructure/src/test/java/dowob/xyz/stockwebv2/infrastructure/security/JwtServiceTest.java`:

```java
package dowob.xyz.stockwebv2.infrastructure.security;

import dowob.xyz.stockwebv2.common.model.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    @Test
    void createsAndParsesAccessToken() {
        JwtProperties properties = new JwtProperties("", Duration.ofMinutes(30), Duration.ofDays(14));
        JwtService jwtService = new JwtService(properties);

        String token = jwtService.createAccessToken(42L, Role.USER, 7);
        JwtService.JwtClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo(Role.USER);
        assertThat(claims.tokenVersion()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: Run the JWT test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-infrastructure -Dtest=JwtServiceTest test
```

Expected: FAIL because `JwtProperties` and `JwtService` do not exist.

- [ ] **Step 3: Add config and abstraction interfaces**

Create `PasswordConfig.java`:

```java
package dowob.xyz.stockwebv2.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
```

Create `RedisConfig.java`:

```java
package dowob.xyz.stockwebv2.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

Create event/search abstractions:

```java
package dowob.xyz.stockwebv2.infrastructure.event;

public interface DomainEvent {
    int version();
}
```

```java
package dowob.xyz.stockwebv2.infrastructure.event;

public interface EventPublisher {
    void publish(DomainEvent event);
}
```

```java
package dowob.xyz.stockwebv2.infrastructure.event;

public interface EventSubscriber<T extends DomainEvent> {
    void handle(T event);
}
```

```java
package dowob.xyz.stockwebv2.infrastructure.search;

import java.util.List;

public interface SearchService<T> {
    List<T> search(String query, int limit);
}
```

- [ ] **Step 4: Add JWT properties and service**

Create `JwtProperties.java`:

```java
package dowob.xyz.stockwebv2.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "stock.jwt")
public record JwtProperties(String privateKey, Duration accessTokenTtl, Duration refreshTokenTtl) {
}
```

Create `JwtService.java`:

```java
package dowob.xyz.stockwebv2.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import dowob.xyz.stockwebv2.common.model.Role;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        ECKey key = resolveKey(properties.privateKey());
        ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(key));
        this.encoder = new NimbusJwtEncoder(jwkSource);
        try {
            this.decoder = NimbusJwtDecoder.withPublicKey(key.toECPublicKey()).build();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to create JWT decoder", exception);
        }
    }

    public String createAccessToken(Long userId, Role role, int tokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(String.valueOf(userId))
            .issuedAt(now)
            .expiresAt(exp)
            .claim("role", role.name())
            .claim("tokenVersion", tokenVersion)
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public JwtClaims parse(String token) {
        Jwt jwt = decoder.decode(token);
        return new JwtClaims(
            Long.valueOf(jwt.getSubject()),
            Role.valueOf(jwt.getClaimAsString("role")),
            ((Number) jwt.getClaim("tokenVersion")).intValue()
        );
    }

    public Map<String, Object> debugClaims(String token) {
        JwtClaims claims = parse(token);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sub", claims.userId());
        map.put("role", claims.role().name());
        map.put("tokenVersion", claims.tokenVersion());
        return map;
    }

    public record JwtClaims(Long userId, Role role, int tokenVersion) {
    }

    private ECKey resolveKey(String privateKeyPem) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            return generateKey();
        }
        try {
            JWK jwk = JWK.parseFromPEMEncodedObjects(privateKeyPem);
            if (jwk instanceof ECKey ecKey) {
                return ecKey;
            }
            throw new IllegalArgumentException("STOCK_JWT_PRIVATE_KEY must be an EC private key");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse STOCK_JWT_PRIVATE_KEY", exception);
        }
    }

    private ECKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            return new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                .privateKey((ECPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate development JWT key", exception);
        }
    }
}
```

- [ ] **Step 5: Enable configuration properties**

Modify `stock-start/src/main/java/dowob/xyz/stockwebv2/start/StockWebV2Application.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.infrastructure.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "dowob.xyz.stockwebv2")
@EnableConfigurationProperties(JwtProperties.class)
public class StockWebV2Application {

    public static void main(String[] args) {
        SpringApplication.run(StockWebV2Application.class, args);
    }
}
```

- [ ] **Step 6: Run infrastructure tests**

Run:

```powershell
.\mvnw.cmd -q -pl stock-infrastructure -Dtest=JwtServiceTest test
.\mvnw.cmd -q -pl stock-start -am test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add stock-infrastructure stock-start
git commit -m "feat: add foundation infrastructure services"
```

---

## Task 7: User Registration and Login Domain

**Files:**
- Create: `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/domain/User.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/UserRepository.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/RegisterRequest.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/LoginRequest.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthResponse.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/MeResponse.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/AuthService.java`

- [ ] **Step 1: Write failing auth service tests**

Create `stock-module-user/src/test/java/dowob/xyz/stockwebv2/user/service/AuthServiceTest.java`:

```java
package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    @Test
    void registerCreatesActiveUserWithHashedPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10));

        User user = service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThat(user.id()).isEqualTo(1L);
        assertThat(user.email()).isEqualTo("yuan@example.com");
        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.tokenVersion()).isEqualTo(1);
        assertThat(user.passwordHash()).isNotEqualTo("Password1");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10));
        service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThatThrownBy(() -> service.register(new RegisterRequest("yuan@example.com", "yuan2", "Password1")))
            .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void verifyCredentialsRejectsWrongPassword() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        AuthService service = new AuthService(repository, new BCryptPasswordEncoder(10));
        service.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));

        assertThatThrownBy(() -> service.verifyCredentials("yuan@example.com", "bad"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    static class InMemoryUserRepository implements UserRepository {
        private final Map<Long, User> byId = new ConcurrentHashMap<>();
        private long seq = 1L;

        @Override
        public Optional<User> findByEmail(String email) {
            return byId.values().stream().filter(user -> user.email().equals(email)).findFirst();
        }

        @Override
        public Optional<User> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public User save(User user) {
            Long id = user.id() == null ? seq++ : user.id();
            User saved = user.withId(id);
            byId.put(id, saved);
            return saved;
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-module-user -Dtest=AuthServiceTest test
```

Expected: FAIL because user domain and service classes do not exist.

- [ ] **Step 3: Add user API and domain objects**

Create `RegisterRequest.java`:

```java
package dowob.xyz.stockwebv2.user.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Email @NotBlank String email,
    @NotBlank @Size(min = 3, max = 50) String username,
    @NotBlank @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$") String password
) {
}
```

Create `LoginRequest.java`:

```java
package dowob.xyz.stockwebv2.user.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
}
```

Create `AuthResponse.java`:

```java
package dowob.xyz.stockwebv2.user.api;

public record AuthResponse(String accessToken, String refreshToken, MeResponse user) {
}
```

Create `MeResponse.java`:

```java
package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;

import java.util.UUID;

public record MeResponse(Long id, UUID uuid, String email, String username, Role role, UserStatus status) {
}
```

Create `User.java`:

```java
package dowob.xyz.stockwebv2.user.domain;

import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("users")
public record User(
    @Id Long id,
    UUID uuid,
    String email,
    String username,
    String passwordHash,
    Role role,
    UserStatus status,
    int tokenVersion,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static User newUser(String email, String username, String passwordHash) {
        OffsetDateTime now = OffsetDateTime.now();
        return new User(null, UUID.randomUUID(), email, username, passwordHash, Role.USER, UserStatus.ACTIVE, 1, now, now);
    }

    public User withId(Long id) {
        return new User(id, uuid, email, username, passwordHash, role, status, tokenVersion, createdAt, updatedAt);
    }

    public MeResponse toMeResponse() {
        return new MeResponse(id, uuid, email, username, role, status);
    }
}
```

- [ ] **Step 4: Add repository and auth service**

Create `UserRepository.java`:

```java
package dowob.xyz.stockwebv2.user.repository;

import dowob.xyz.stockwebv2.user.domain.User;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    Optional<User> findById(Long id);
    User save(User user);
}
```

Create `AuthService.java`:

```java
package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.DuplicateResourceException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(existing -> {
            throw new DuplicateResourceException("email");
        });
        User user = User.newUser(request.email(), request.username(), passwordEncoder.encode(request.password()));
        return userRepository.save(user);
    }

    public User verifyCredentials(String email, String password) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage()));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, ErrorCode.AUTH_INVALID_CREDENTIALS.defaultMessage());
        }
        return user;
    }
}
```

- [ ] **Step 5: Run auth service tests**

Run:

```powershell
.\mvnw.cmd -q -pl stock-module-user -Dtest=AuthServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add stock-module-user
git commit -m "feat: add user registration domain service"
```

---

## Task 8: JDBC User Repository and Redis Refresh Tokens

**Files:**
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java`

- [ ] **Step 1: Write failing persistence test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthPersistenceIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.start.support.ContainerIT;
import dowob.xyz.stockwebv2.user.api.RegisterRequest;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.service.AuthService;
import dowob.xyz.stockwebv2.user.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPersistenceIT extends ContainerIT {

    @Autowired
    AuthService authService;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void registerPersistsUserAndRefreshTokenUsesRedis() {
        User user = authService.register(new RegisterRequest("yuan@example.com", "yuan", "Password1"));
        String refreshToken = refreshTokenService.issue(user, "test-device");

        assertThat(user.id()).isNotNull();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(redisTemplate.hasKey("user:refresh:" + refreshToken)).isTrue();
        assertThat(redisTemplate.opsForSet().members("user:refresh:index:" + user.id())).contains(refreshToken);
    }
}
```

- [ ] **Step 2: Run persistence test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=AuthPersistenceIT test
```

Expected: FAIL because no JDBC repository or refresh token service exists.

- [ ] **Step 3: Add JDBC user repository**

Create `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/repository/JdbcUserRepository.java`:

```java
package dowob.xyz.stockwebv2.user.repository;

import dowob.xyz.stockwebv2.common.model.Role;
import dowob.xyz.stockwebv2.common.model.UserStatus;
import dowob.xyz.stockwebv2.user.domain.User;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
@Primary
public class JdbcUserRepository implements UserRepository {
    private final JdbcClient jdbcClient;

    public JdbcUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbcClient.sql("select * from users where email = :email")
            .param("email", email)
            .query(this::map)
            .optional();
    }

    @Override
    public Optional<User> findById(Long id) {
        return jdbcClient.sql("select * from users where id = :id")
            .param("id", id)
            .query(this::map)
            .optional();
    }

    @Override
    public User save(User user) {
        if (user.id() != null) {
            throw new IllegalArgumentException("User updates are not part of foundation save()");
        }
        return jdbcClient.sql("""
                insert into users(uuid, email, username, password_hash, role, status, token_version, created_at, updated_at)
                values (:uuid, :email, :username, :passwordHash, :role, :status, :tokenVersion, :createdAt, :updatedAt)
                returning *
                """)
            .param("uuid", user.uuid())
            .param("email", user.email())
            .param("username", user.username())
            .param("passwordHash", user.passwordHash())
            .param("role", user.role().name())
            .param("status", user.status().name())
            .param("tokenVersion", user.tokenVersion())
            .param("createdAt", user.createdAt())
            .param("updatedAt", user.updatedAt())
            .query(this::map)
            .single();
    }

    private User map(ResultSet rs, int rowNum) throws SQLException {
        return new User(
            rs.getLong("id"),
            UUID.fromString(rs.getString("uuid")),
            rs.getString("email"),
            rs.getString("username"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            UserStatus.valueOf(rs.getString("status")),
            rs.getInt("token_version"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
```

- [ ] **Step 4: Add refresh token service**

Create `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/service/RefreshTokenService.java`:

```java
package dowob.xyz.stockwebv2.user.service;

import dowob.xyz.stockwebv2.common.error.BusinessException;
import dowob.xyz.stockwebv2.common.error.ErrorCode;
import dowob.xyz.stockwebv2.user.domain.User;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private final StringRedisTemplate redisTemplate;

    public RefreshTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issue(User user, String deviceInfo) {
        try {
            String token = UUID.randomUUID().toString();
            String refreshKey = "user:refresh:" + token;
            redisTemplate.opsForHash().putAll(refreshKey, Map.of(
                "userId", String.valueOf(user.id()),
                "tokenVersion", String.valueOf(user.tokenVersion()),
                "deviceInfo", deviceInfo == null ? "unknown" : deviceInfo,
                "createdAt", OffsetDateTime.now().toString()
            ));
            redisTemplate.expire(refreshKey, Duration.ofDays(14));
            redisTemplate.opsForSet().add("user:refresh:index:" + user.id(), token);
            redisTemplate.opsForHash().putAll("user:auth:" + user.id(), Map.of(
                "tokenVersion", String.valueOf(user.tokenVersion()),
                "status", user.status().name()
            ));
            return token;
        } catch (RedisConnectionFailureException exception) {
            throw new BusinessException(ErrorCode.AUTH_REDIS_UNAVAILABLE, ErrorCode.AUTH_REDIS_UNAVAILABLE.defaultMessage());
        }
    }

    public void revoke(String token) {
        redisTemplate.delete("user:refresh:" + token);
    }
}
```

- [ ] **Step 5: Run persistence test**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=AuthPersistenceIT test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add stock-module-user stock-start/src/test
git commit -m "feat: persist users and refresh tokens"
```

---

## Task 9: Auth Controller and Security Integration

**Files:**
- Create: `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`
- Create: `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`

- [ ] **Step 1: Write failing auth flow integration test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AuthFlowIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthFlowIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void registerLoginMeLogoutFlowWorks() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"yuan@example.com","username":"yuan","password":"Password1"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.accessToken", notNullValue()))
            .andExpect(jsonPath("$.data.refreshToken", notNullValue()));

        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"yuan@example.com","password":"Password1"}
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String accessToken = loginBody.replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
        String refreshToken = loginBody.replaceAll(".*\\\"refreshToken\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.email", equalTo("yuan@example.com")));

        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)));
    }
}
```

- [ ] **Step 2: Run auth flow test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=AuthFlowIT test
```

Expected: FAIL because controller and security config are missing.

- [ ] **Step 3: Add auth controller**

Create `stock-module-user/src/main/java/dowob/xyz/stockwebv2/user/api/AuthController.java`:

```java
package dowob.xyz.stockwebv2.user.api;

import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.EmptyResponse;
import dowob.xyz.stockwebv2.infrastructure.security.JwtService;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import dowob.xyz.stockwebv2.user.domain.User;
import dowob.xyz.stockwebv2.user.repository.UserRepository;
import dowob.xyz.stockwebv2.user.service.AuthService;
import dowob.xyz.stockwebv2.user.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService, JwtService jwtService, UserRepository userRepository) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @PostMapping("/auth/register")
    ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return ApiResponse.success(authResponse(user, "register"), meta());
    }

    @PostMapping("/auth/login")
    ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = authService.verifyCredentials(request.email(), request.password());
        return ApiResponse.success(authResponse(user, "login"), meta());
    }

    @PostMapping("/auth/logout")
    ApiResponse<EmptyResponse> logout(@RequestBody Map<String, String> request) {
        refreshTokenService.revoke(request.get("refreshToken"));
        return ApiResponse.empty(meta());
    }

    @GetMapping("/me")
    ApiResponse<MeResponse> me(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        User user = userRepository.findById(userId).orElseThrow();
        return ApiResponse.success(user.toMeResponse(), meta());
    }

    private AuthResponse authResponse(User user, String deviceInfo) {
        String accessToken = jwtService.createAccessToken(user.id(), user.role(), user.tokenVersion());
        String refreshToken = refreshTokenService.issue(user, deviceInfo);
        return new AuthResponse(accessToken, refreshToken, user.toMeResponse());
    }

    private ApiMeta meta() {
        return new ApiMeta(MDC.get(TraceIdFilter.TRACE_ID), OffsetDateTime.now());
    }
}
```

- [ ] **Step 4: Add minimal security config**

Create `stock-start/src/main/java/dowob/xyz/stockwebv2/start/config/SecurityConfig.java`:

```java
package dowob.xyz.stockwebv2.start.config;

import dowob.xyz.stockwebv2.infrastructure.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
            .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
            .anyRequest().authenticated()
        );
        http.addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class JwtAuthFilter extends OncePerRequestFilter {
        private final JwtService jwtService;

        JwtAuthFilter(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                JwtService.JwtClaims claims = jwtService.parse(header.substring(7));
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    String.valueOf(claims.userId()),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
        }
    }
}
```

- [ ] **Step 5: Run auth flow test**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=AuthFlowIT test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add stock-module-user stock-start
git commit -m "feat: add auth endpoints and security flow"
```

---

## Task 10: Asset Query Module

**Files:**
- Create: `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/domain/Asset.java`
- Create: `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetDto.java`
- Create: `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/repository/AssetRepository.java`
- Create: `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/service/AssetQueryService.java`
- Create: `stock-module-asset/src/main/java/dowob/xyz/stockwebv2/asset/api/AssetController.java`
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AssetApiIT.java`

- [ ] **Step 1: Write failing asset API test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/AssetApiIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AssetApiIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void publicAssetsReturnsSeedAssetsWithLatestPrice() throws Exception {
        mockMvc.perform(get("/api/v1/assets?query=NVDA&page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success", equalTo(true)))
            .andExpect(jsonPath("$.data.items.length()", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.items[0].symbol", equalTo("NVDA")))
            .andExpect(jsonPath("$.data.items[0].latestPrice").value(1142.83));
    }
}
```

- [ ] **Step 2: Run asset API test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=AssetApiIT test
```

Expected: FAIL because asset module API does not exist.

- [ ] **Step 3: Add asset DTO and repository**

Create `Asset.java`:

```java
package dowob.xyz.stockwebv2.asset.domain;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.util.UUID;

public record Asset(
    Long id,
    UUID uuid,
    String symbol,
    String name,
    AssetType assetType,
    String market,
    CurrencyCode currency,
    String sector,
    boolean tradeable,
    boolean active,
    BigDecimal latestPrice,
    BigDecimal change,
    BigDecimal changePercent,
    String volumeText,
    BigDecimal high,
    BigDecimal low
) {
}
```

Create `AssetDto.java`:

```java
package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;

import java.math.BigDecimal;
import java.util.UUID;

public record AssetDto(
    UUID uuid,
    String symbol,
    String name,
    AssetType assetType,
    String market,
    CurrencyCode currency,
    String sector,
    boolean tradeable,
    BigDecimal latestPrice,
    BigDecimal change,
    BigDecimal changePercent,
    String volumeText,
    BigDecimal high,
    BigDecimal low
) {
}
```

Create `AssetRepository.java`:

```java
package dowob.xyz.stockwebv2.asset.repository;

import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.common.model.AssetType;
import dowob.xyz.stockwebv2.common.model.CurrencyCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Repository
public class AssetRepository {
    private final JdbcClient jdbcClient;

    public AssetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Asset> search(String query, int page, int size) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        return jdbcClient.sql("""
                select a.*, p.price latest_price, p.change, p.change_percent, p.volume_text, p.high, p.low
                from assets a
                left join asset_latest_prices p on p.asset_id = a.id
                where a.active = true
                  and (:query = '%%' or a.symbol ilike :query or a.name ilike :query)
                order by a.symbol asc
                limit :limit offset :offset
                """)
            .param("query", like)
            .param("limit", size)
            .param("offset", page * size)
            .query(this::map)
            .list();
    }

    public long count(String query) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        Long count = jdbcClient.sql("""
                select count(*)
                from assets a
                where a.active = true
                  and (:query = '%%' or a.symbol ilike :query or a.name ilike :query)
                """)
            .param("query", like)
            .query(Long.class)
            .single();
        return count;
    }

    private Asset map(ResultSet rs, int rowNum) throws SQLException {
        return new Asset(
            rs.getLong("id"),
            UUID.fromString(rs.getString("uuid")),
            rs.getString("symbol"),
            rs.getString("name"),
            AssetType.valueOf(rs.getString("asset_type")),
            rs.getString("market"),
            CurrencyCode.valueOf(rs.getString("currency")),
            rs.getString("sector"),
            rs.getBoolean("tradeable"),
            rs.getBoolean("active"),
            rs.getBigDecimal("latest_price"),
            rs.getBigDecimal("change"),
            rs.getBigDecimal("change_percent"),
            rs.getString("volume_text"),
            rs.getBigDecimal("high"),
            rs.getBigDecimal("low")
        );
    }
}
```

- [ ] **Step 4: Add asset service and controller**

Create `AssetQueryService.java`:

```java
package dowob.xyz.stockwebv2.asset.service;

import dowob.xyz.stockwebv2.asset.api.AssetDto;
import dowob.xyz.stockwebv2.asset.domain.Asset;
import dowob.xyz.stockwebv2.asset.repository.AssetRepository;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import org.springframework.stereotype.Service;

@Service
public class AssetQueryService {
    private final AssetRepository assetRepository;

    public AssetQueryService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public PageResponse<AssetDto> search(String query, int page, int size) {
        var items = assetRepository.search(query, page, size).stream().map(this::toDto).toList();
        long total = assetRepository.count(query);
        return PageResponse.of(items, page, size, total);
    }

    private AssetDto toDto(Asset asset) {
        return new AssetDto(
            asset.uuid(),
            asset.symbol(),
            asset.name(),
            asset.assetType(),
            asset.market(),
            asset.currency(),
            asset.sector(),
            asset.tradeable(),
            asset.latestPrice(),
            asset.change(),
            asset.changePercent(),
            asset.volumeText(),
            asset.high(),
            asset.low()
        );
    }
}
```

Create `AssetController.java`:

```java
package dowob.xyz.stockwebv2.asset.api;

import dowob.xyz.stockwebv2.asset.service.AssetQueryService;
import dowob.xyz.stockwebv2.common.api.ApiMeta;
import dowob.xyz.stockwebv2.common.api.ApiResponse;
import dowob.xyz.stockwebv2.common.api.PageResponse;
import dowob.xyz.stockwebv2.infrastructure.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
    private final AssetQueryService assetQueryService;

    public AssetController(AssetQueryService assetQueryService) {
        this.assetQueryService = assetQueryService;
    }

    @GetMapping
    ApiResponse<PageResponse<AssetDto>> search(
        @RequestParam(defaultValue = "") String query,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return ApiResponse.success(assetQueryService.search(query, safePage, safeSize), meta());
    }

    private ApiMeta meta() {
        return new ApiMeta(MDC.get(TraceIdFilter.TRACE_ID), OffsetDateTime.now());
    }
}
```

- [ ] **Step 5: Permit public assets endpoint**

Modify `SecurityConfig` authorization block:

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
    .requestMatchers("/api/v1/assets/**").permitAll()
    .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
    .anyRequest().authenticated()
);
```

- [ ] **Step 6: Run asset API test**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=AssetApiIT test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add stock-module-asset stock-start
git commit -m "feat: add seed asset query api"
```

---

## Task 11: Actuator, OpenAPI, and Final Foundation Verification

**Files:**
- Create: `stock-start/src/test/java/dowob/xyz/stockwebv2/start/FoundationSmokeIT.java`
- Modify if needed: `stock-start/src/main/resources/application.yaml`
- Modify if needed: `stock-start/pom.xml`

- [ ] **Step 1: Write failing smoke test**

Create `stock-start/src/test/java/dowob/xyz/stockwebv2/start/FoundationSmokeIT.java`:

```java
package dowob.xyz.stockwebv2.start;

import dowob.xyz.stockwebv2.start.support.ContainerIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class FoundationSmokeIT extends ContainerIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void actuatorHealthAndOpenApiAreAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status", equalTo("UP")));

        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi", notNullValue()));
    }
}
```

- [ ] **Step 2: Run smoke test**

Run:

```powershell
.\mvnw.cmd -q -pl stock-start -Dtest=FoundationSmokeIT test
```

Expected: PASS. If `/v3/api-docs` is blocked by security, update `SecurityConfig` permit rules exactly as in Task 10 Step 5 and rerun.

- [ ] **Step 3: Run full test suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 4: Run dependency resolve**

Run:

```powershell
.\mvnw.cmd -q dependency:resolve
```

Expected: PASS. Boot `4.0.4` remains in the parent POM.

- [ ] **Step 5: Run whitespace check**

Run:

```powershell
git diff --check
```

Expected: no output.

- [ ] **Step 6: Commit**

```powershell
git add stock-start pom.xml
git commit -m "test: verify foundation runtime endpoints"
```

---

## Task 12: Local Dev Resource Verification

**Files:**
- No source changes required unless verification exposes a missing environment key.

- [ ] **Step 1: Create local `.env` from `.env.example`**

Run:

```powershell
Copy-Item .env.example .env
```

Edit `.env` and fill:

```properties
STOCK_DB_USERNAME=<provided dev database username>
STOCK_DB_PASSWORD=<provided dev database password>
```

Keep these values:

```properties
STOCK_DB_URL=jdbc:postgresql://10.0.0.214:30120/stock_v2_db
STOCK_REDIS_HOST=10.0.0.214
STOCK_REDIS_PORT=30121
STOCK_REDIS_DATABASE=1
```

- [ ] **Step 2: Start the app with `.env` loaded**

Run:

```powershell
.\scripts\run-dev.ps1
```

Expected: application starts on `SERVER_PORT` default `11180`; management starts on `STOCK_MANAGEMENT_PORT` default `11181`.

- [ ] **Step 3: Check health**

Run in a second PowerShell:

```powershell
Invoke-RestMethod http://localhost:11181/actuator/health
```

Expected response includes:

```json
{
  "status": "UP"
}
```

- [ ] **Step 4: Check OpenAPI**

Run:

```powershell
Invoke-RestMethod http://localhost:11180/v3/api-docs
```

Expected: response contains an `openapi` field.

- [ ] **Step 5: Check assets**

Run:

```powershell
Invoke-RestMethod "http://localhost:11180/api/v1/assets?query=AAPL&page=0&size=20"
```

Expected: `success` is `true` and `data.items[0].symbol` is `AAPL`.

- [ ] **Step 6: Commit only if verification required a source/config fix**

If no source/config change was needed, do not create a commit for this task. If a committed fix was needed:

```powershell
git add <changed-files>
git commit -m "fix: align foundation local runtime config"
```

---

## Final Verification Checklist

Run these commands before claiming the branch is complete:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -q dependency:resolve
git diff --check
```

Runtime checks with local `.env`:

```powershell
.\scripts\run-dev.ps1
Invoke-RestMethod http://localhost:11181/actuator/health
Invoke-RestMethod http://localhost:11180/v3/api-docs
Invoke-RestMethod "http://localhost:11180/api/v1/assets?query=NVDA&page=0&size=20"
```

Completion means:

- Spring Boot remains `4.0.4`.
- Multi-module build passes.
- `.env.example` exists; `.env` is ignored.
- `application.yaml`, `application-dev.yaml`, and `application-demo.yaml` use environment values for runtime configuration.
- Flyway creates foundation schema and seed assets.
- Auth register/login/me/logout path works.
- Asset query endpoint works.
- Actuator health and OpenAPI are available.
- No portfolio/trading/holding/market-data/alert/backtest half implementation is present.
