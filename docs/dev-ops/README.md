# 后端部署资料

- [新手快速操作教程](docker-compose/QUICKSTART.md)：按步骤说明运行什么命令、每步效果和配置在哪里改。
- [WSL Docker Compose 部署教程](docker-compose/README.md)：默认 SQLite，可选 MySQL，包含 HTTPS、数据持久化、更新和备份说明。
- `docker-compose/`：可直接使用的 Compose、Dockerfile、环境变量模板和初始化脚本。
- `mysql/`：原有数据库脚本；其中核心初始化脚本含删表和示例数据，使用前请核对。Compose 新空库使用专门整理的 `docker-compose/mysql/001-schema.sql`。

容器方案根据当前后端强制 HTTPS、JDK 25 和 SQLite/MySQL 配置整理。实际验证范围与当前镜像下载限制见部署教程。
