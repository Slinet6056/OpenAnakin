# OpenAnakin

OpenAnakin 是一个兼容 OpenAI API 的 Anakin AI 适配器。它允许使用 OpenAI API 客户端与 Anakin AI 服务进行交互。

## 功能

- 允许使用 OpenAI API 格式调用 Anakin AI 接口
- 支持流式和非流式响应
- 可配置的模型到 Anakin 应用 ID 的映射

## 配置

在 `application.yml` 文件中配置模型和对应的 Anakin 应用 ID：

```yaml
anakin:
  models:
    gpt-4o-mini: 31800
    gpt-4o: 32442
```

## 使用方法

1. 克隆仓库

2. 配置 application.yml

3. 运行 OpenAnakinApplication 类

4. 在与 OpenAI API 兼容的客户端中，设置 base URL 为 http://localhost:<端口号>/v1，并填入 API 密钥即可使用

## 通过 Docker 运行

您可以使用 Docker 来运行 OpenAnakin。按照以下步骤操作:

1. 准备 `application.yml` 配置文件,包含您的 Anakin 模型和应用 ID 映射。

2. 运行 Docker 容器,将配置文件映射到容器内:

    ```sh
    docker run -d -p 8080:8080 -v /path/to/your/application.yml:/app/application.yml ghcr.io/slinet6056/openanakin:master
    ```

    请将 `/path/to/your/application.yml` 替换为您本地 `application.yml` 文件的实际路径。

3. 现在您可以通过 `http://localhost:8080/v1` 访问 OpenAnakin 服务。

## 构建 JAR 包

要构建可执行的 JAR 包，请按照以下步骤操作：

1. 确保您的系统上安装了 Java 和 Maven

2. 在项目根目录下打开终端

3. 运行以下命令：

    ```sh
    mvn clean package
    ```

4. 构建完成后，可以在 `target` 目录下找到生成的 JAR 文件

5. 使用以下命令运行 JAR 文件：

    ```sh
    java -jar target/OpenAnakin-1.0-SNAPSHOT.jar  --server.port=8080 --anakin.models.gpt-4o-mini=31800 --anakin.models.gpt-4o=32442
    ```

注意：请将 "OpenAnakin-1.0-SNAPSHOT.jar" 替换为实际生成的 JAR 文件名，并根据需要调整端口和模型配置。

