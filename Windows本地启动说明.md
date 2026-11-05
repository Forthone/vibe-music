# Vibe Music 本地启动说明

本项目已克隆到 `D:\code\vibe-music`。本地复现使用 Java 17、Node.js、Maven、pnpm、Docker Desktop，以及从 MinIO 官方源码编译的 Windows 程序。

## 打开地址与登录账号

| 页面 | 地址 | 登录方式 |
| --- | --- | --- |
| 用户端 | http://127.0.0.1:8090 | 邮箱 `user651@example.com`，密码 `123456abc` |
| 管理后台 | http://127.0.0.1:8089 | 用户名 `admin_1`，密码 `123456abc` |
| 后端接口 | http://127.0.0.1:8080 | 直接打开根地址不会出现前端页面 |
| MinIO 文件管理 | http://localhost:9001 | 用户名 `minioadmin`，密码 `vibe_minio_local_2026` |

这些是独立本地环境的示例账号。用户端使用已有用户即可登录，不需要先配置邮件注册。

## 下次启动

先打开 Docker Desktop，等它运行完成。然后在 PowerShell 执行：

```powershell
cd 'D:\code\vibe-music'
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
```

脚本会启动项目专用的 MySQL、Redis、MinIO、Java 后端和两套 Vite 前端。看到进程启动提示后，等待后端启动完成，再打开上述两个页面。日志写在 `.local\logs`。

修改 Java 代码后，需要重新打包时执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Build
```

前端源码保存后，Vite 开发服务器通常会自动更新页面。

## 关闭项目

```powershell
cd 'D:\code\vibe-music'
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
```

脚本只停止本项目记录的进程和两个 Docker 容器，不删除数据库和文件，不关闭整个 Docker Desktop。之后可以在 Docker Desktop 托盘菜单中点击 Quit Docker Desktop。

不要执行带 `down -v` 的命令，它会删除容器数据卷。

## 数据与原始歌曲包

原项目 SQL 已导入新的 `vibe_music` 数据库。原始 SQL 中的封面、歌曲地址都指向 `localhost:9000/vibe-music-data`，但 Git 仓库没有包含这些媒体文件。

原作者在[服务端仓库说明](https://github.com/Alex-LiSun/vibe-music-server#文件下载-)中提供了[百度网盘资源包](https://pan.baidu.com/s/1IHU2EBodNmmjCeYi7_Tw5g?pwd=1234)，提取码为 `1234`。下载后，通过 MinIO 控制台把资源导入 `vibe-music-data` 桶，保持原有目录和文件名，不要多套一层 `vibe-music-data` 目录。

比如 `songs/xxx.mp3` 应能通过 `http://localhost:9000/vibe-music-data/songs/xxx.mp3` 访问。歌曲文件、封面、歌手头像、歌单图和轮播图缺少时，对应内容无法播放或显示；数据库里有歌曲名字不代表音频已导入。

为验证文件服务，另建了明确标注的 `Local Playback Test` 测试歌曲，内容为 5 秒低音量合成测试音，不是原始曲库。可在用户端搜索该名称验证播放。原曲库仍需导入作者资源包。

## 本地环境配置

| 服务 | 本机端口 | 数据位置 |
| --- | --- | --- |
| MySQL | 23306 | Docker 数据卷 `vibe-music-local_mysql-data` |
| Redis | 26379 | Docker 数据卷 `vibe-music-local_redis-data` |
| MinIO S3 | 9000 | 项目 `.local\minio-data` |
| MinIO 控制台 | 9001 | 同上 |

MySQL 数据库名 `vibe_music`，应用账号 `vibe`，密码 `vibe_local_2026`；root 密码 `vibe_local_root_2026`。均为本次创建的本地开发凭证，和之前商城的数据库分开。

后端开发配置文件：`vibe-music-backend\vibe-music-server\src\main\resources\application-dev.yml`。该文件已加入 Git 忽略规则。

MinIO 官方下载站已返回 410，两个官方镜像地址在本机也无法拉取，因此本次从[官方源码](https://github.com/minio/minio/tree/RELEASE.2025-04-22T22-12-26Z)编译，程序保存在 `.local\tools\minio.exe`。编译所需的便携 Go 工具、源码和缓存已在迁移时清理，保留编译后的 `.local\tools\minio.exe`。MinIO 仅用于此本地学习环境。

## AI 与邮件暂不启用

按你的选择，百炼 API Key 和邮箱 SMTP 授权码暂未配置。AI 聊天、发送验证码、邮件注册和邮件重置密码暂不能正常使用；普通用户及后台账号密码登录可用。

后续在启动脚本所在 PowerShell 中设置 `DASHSCOPE_API_KEY`，邮件配置设置 `MAIL_HOST`、`MAIL_USERNAME`、`MAIL_PASSWORD`，然后重启后端即可读取。请使用自己的配置，不要提交到 GitHub，也不要在聊天里发送密钥。

## 本次兼容调整

- 增加音乐项目独立的 Docker Compose 配置、本地后端配置及启动停止脚本。
- 管理端移除 Unix 环境变量写法，使启动和构建命令兼容 Windows。
- 将 CDN 插件改成启用时才创建，避免关闭 CDN 时仍检查非直接依赖并阻止启动。
- 补齐管理端直接使用的 `tippy.js` 依赖，移除导致图标脚本解析失败的 console 清理插件。
- 为 esbuild、vue-demi 和管理端的原生 watcher 开启必要的构建脚本。

两套前端使用各自 `pnpm-lock.yaml` 安装，后端 Maven 打包、两套前端生产构建均已通过。


## 2026年10月28日迁移记录

项目目录已迁移至 `D:\code\vibe-music`。源码、Git 历史、配置、已编译后端、MinIO 程序和上传文件均已迁移；920 个复制文件已逐一通过 SHA256 校验。前端依赖已在 D 盘重建，`pnpm-workspace.yaml` 将后续依赖缓存配置在项目 `.local/pnpm-store`。

Go 编译依赖、源码、便携编译器及安装包已清理；C 盘旧项目文件和未使用的 pnpm 缓存已清理。C 盘可用空间从约 13.04 GB 增至约 16.79 GB。

Docker 的 MySQL、Redis 数据仍保存在原命名数据卷中，容器脚本挂载已指向 D 盘。数据库逻辑备份位于 `.local/backups/vibe_music_after_move.sql`；迁移验证记录位于 `.local/migration-verification.json`。

使用 IDEA 时，请重新打开 `D:\code\vibe-music\vibe-music-backend`，关闭原先的 C 盘项目。访问地址与账号均保持不变。
