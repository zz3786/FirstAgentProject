# Qdrant 配置与命令手册

> 环境：Windows + PowerShell + Qdrant 本地部署
> 用途：RAG 向量库管理（agent-rag collection）

---

## 一、环境信息

| 项目 | 值 |
|------|-----|
| Qdrant 程序路径 | `D:\zhangzhao\qdrant\qdrant.exe` |
| 配置文件路径 | `D:\zhangzhao\qdrant\config\config.yaml` |
| 数据存储路径 | `D:\zhangzhao\qdrant\storage` |
| 快照存储路径 | `D:\zhangzhao\qdrant\snapshots` |
| REST 端口 | `6333` |
| gRPC 端口 | `6334` |
| Collection 名 | `agent-rag` |
| 向量维度 | `1024` |
| 距离算法 | `Cosine` |
| Shell | PowerShell |

> ⚠️ **PowerShell 里的 `curl` 是 `Invoke-WebRequest` 的别名**——必须用 `curl.exe`。

---

## 二、配置文件

### 2.1 `D:\zhangzhao\qdrant\config\config.yaml`

```yaml
storage:
  # 数据存储路径
  storage_path: "D:/zhangzhao/qdrant/storage"

  # 快照存储路径
  snapshots_path: "D:/zhangzhao/qdrant/snapshots"

  # 将 payload 存储在磁盘上以节省内存
  on_disk_payload: true

  # HNSW 索引默认参数
  hnsw_index:
    m: 16
    ef_construct: 100
    full_scan_threshold: 10000
    max_indexing_threads: 0

  # WAL 配置
  wal:
    wal_capacity_mb: 32
    wal_segments_ahead: 0

# 日志级别
log_level: INFO
```

### 2.2 一键生成配置（PowerShell）

```powershell
# 建 config 目录
New-Item -ItemType Directory -Force -Path "D:\zhangzhao\qdrant\config" | Out-Null

# 写配置文件
@"
storage:
  storage_path: "D:/zhangzhao/qdrant/storage"
  snapshots_path: "D:/zhangzhao/qdrant/snapshots"
  on_disk_payload: true
  hnsw_index:
    m: 16
    ef_construct: 100
    full_scan_threshold: 10000
    max_indexing_threads: 0
  wal:
    wal_capacity_mb: 32
    wal_segments_ahead: 0

log_level: INFO
"@ | Out-File -Encoding UTF8 "D:\zhangzhao\qdrant\config\config.yaml"

# 验证
Get-Content "D:\zhangzhao\qdrant\config\config.yaml"
```

---

## 三、启动方式

### 3.1 推荐：bat 脚本（最稳）

**文件**：`D:\zhangzhao\qdrant\start-qdrant.bat`

```bat
@echo off
chcp 65001 >nul
cd /d "D:\zhangzhao\qdrant"
start "" "D:\zhangzhao\qdrant\qdrant.exe" --config-path "D:\zhangzhao\qdrant\config\config.yaml"
```

**桌面建快捷方式**：
1. 桌面右键 → 新建 → 快捷方式
2. 位置：`D:\zhangzhao\qdrant\start-qdrant.bat`
3. 命名：`启动Qdrant`
4. 完成

**为什么用 bat**：`cd /d` 强制切换工作目录——保证 `.qdrant-initialized` 生成在 `D:\zhangzhao\qdrant\`，不污染桌面。

### 3.2 临时启动（PowerShell 一行）

```powershell
Start-Process -FilePath "D:\zhangzhao\qdrant\qdrant.exe" `
    -ArgumentList "--config-path", "D:\zhangzhao\qdrant\config\config.yaml" `
    -WorkingDirectory "D:\zhangzhao\qdrant"
```

### 3.3 停止 Qdrant

```powershell
Stop-Process -Name qdrant -Force -ErrorAction SilentlyContinue
```

---

## 四、Collection 管理命令

### 4.1 查看所有 collection

```powershell
curl.exe http://localhost:6333/collections
```

### 4.2 查看指定 collection

```powershell
curl.exe http://localhost:6333/collections/agent-rag
```

**期望返回**（关键字段）：

```json
{
  "result": {
    "status": "green",
    "points_count": 10,
    "config": {
      "params": {
        "vectors": {
          "size": 1024,
          "distance": "Cosine"
        }
      }
    }
  }
}
```

### 4.3 删除 collection

```powershell
curl.exe -X DELETE "http://localhost:6333/collections/agent-rag"
```

**期望**：`{"result":true,"status":"ok","time":...}`

### 4.4 重建 collection

```powershell
curl.exe -X PUT "http://localhost:6333/collections/agent-rag" -H "Content-Type: application/json" -d '{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}'
```

**期望**：`{"result":true,"status":"ok","time":...}`

### 4.5 查看 collection 内的 points（调试用）

```powershell
curl.exe -X POST "http://localhost:6333/collections/agent-rag/points/scroll" -H "Content-Type: application/json" -d "{\"limit\": 3, \"with_vector\": false, \"with_payload\": true}"
```

---

## 五、完全重置流程（一键脚本）

### 5.1 PowerShell 一键重置

```powershell
# 1. 停 Qdrant
Write-Host "[1/5] 停止 Qdrant..." -ForegroundColor Cyan
Stop-Process -Name qdrant -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 2. 清理桌面残留
Write-Host "[2/5] 清理桌面残留..." -ForegroundColor Cyan
Remove-Item -Recurse -Force "$env:USERPROFILE\Desktop\storage" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$env:USERPROFILE\Desktop\snapshots" -ErrorAction SilentlyContinue
Remove-Item -Force "$env:USERPROFILE\Desktop\.qdrant-initialized" -ErrorAction SilentlyContinue

# 3. 启动 Qdrant
Write-Host "[3/5] 启动 Qdrant..." -ForegroundColor Cyan
Start-Process -FilePath "D:\zhangzhao\qdrant\qdrant.exe" `
    -ArgumentList "--config-path", "D:\zhangzhao\qdrant\config\config.yaml" `
    -WorkingDirectory "D:\zhangzhao\qdrant"
Start-Sleep -Seconds 3

# 4. 重建 collection
Write-Host "[4/5] 重建 collection..." -ForegroundColor Cyan
curl.exe -X PUT "http://localhost:6333/collections/agent-rag" -H "Content-Type: application/json" -d '{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}'

# 5. 验证
Write-Host "`n[5/5] 验证目录位置：" -ForegroundColor Cyan
Write-Host "  D:\zhangzhao\qdrant\storage    : $(Test-Path 'D:\zhangzhao\qdrant\storage')"
Write-Host "  D:\zhangzhao\qdrant\snapshots  : $(Test-Path 'D:\zhangzhao\qdrant\snapshots')"
Write-Host "  桌面 storage    (应为 False): $(Test-Path "$env:USERPROFILE\Desktop\storage")"
Write-Host "  桌面 snapshots  (应为 False): $(Test-Path "$env:USERPROFILE\Desktop\snapshots")"
```

### 5.2 bat 一键重置脚本

**文件**：`D:\zhangzhao\qdrant\reset-qdrant.bat`

```bat
@echo off
chcp 65001 >nul
echo ========================================
echo  Qdrant 重置脚本
echo ========================================

echo [1/4] 停止 Qdrant...
taskkill /F /IM qdrant.exe 2>nul
timeout /t 2 /nobreak >nul

echo [2/4] 删除 collection 目录...
if exist "D:\zhangzhao\qdrant\storage\collections\agent-rag" (
    rmdir /S /Q "D:\zhangzhao\qdrant\storage\collections\agent-rag"
    echo     已删除 collection
) else (
    echo     collection 不存在，跳过
)

echo [3/4] 启动 Qdrant...
cd /d "D:\zhangzhao\qdrant"
start "" "D:\zhangzhao\qdrant\qdrant.exe" --config-path "D:\zhangzhao\qdrant\config\config.yaml"
timeout /t 3 /nobreak >nul

echo [4/4] 重建 collection...
curl.exe -X PUT "http://localhost:6333/collections/agent-rag" -H "Content-Type: application/json" -d "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"

echo.
echo ========================================
echo  完成！验证：
echo  curl.exe http://localhost:6333/collections/agent-rag
echo ========================================
pause
```

---

## 六、故障排查

### 6.1 重建报 `Collection data already exists`

**原因**：API 删除只删元数据，磁盘目录还在。

**解决**：

```powershell
# 1. 停 Qdrant
Stop-Process -Name qdrant -Force

# 2. 搜真实路径
Get-ChildItem -Path D:\ -Filter "agent-rag" -Recurse -Directory -ErrorAction SilentlyContinue | Select-Object FullName

# 3. 删目录（用上一步的路径）
Remove-Item -Recurse -Force "D:\zhangzhao\qdrant\storage\collections\agent-rag"

# 4. 重启 Qdrant
Start-Process -FilePath "D:\zhangzhao\qdrant\qdrant.exe" `
    -ArgumentList "--config-path", "D:\zhangzhao\qdrant\config\config.yaml" `
    -WorkingDirectory "D:\zhangzhao\qdrant"

# 5. 重建
curl.exe -X PUT "http://localhost:6333/collections/agent-rag" -H "Content-Type: application/json" -d '{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}'
```

### 6.2 数据文件生成到桌面

**原因**：快捷方式或启动命令的工作目录是桌面。

**解决**：用 bat 脚本启动（`cd /d` 切目录）。

### 6.3 Qdrant 版本兼容警告

```
Qdrant client version 1.13.0 is incompatible with server version 1.19.1
```

**原因**：Spring AI 内置的 Qdrant Java 客户端版本与服务器版本差距大。

**解决**：在 `application.yml` 加：

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        check-compatibility: false
```

### 6.4 端口被占用

```powershell
# 查谁占了 6333/6334
netstat -ano | findstr "6333"
netstat -ano | findstr "6334"
```

---

## 七、常用命令速查

| 操作 | 命令 |
|------|------|
| **启动 Qdrant** | 双击桌面 `启动Qdrant` 快捷方式（bat 脚本） |
| **停止 Qdrant** | `Stop-Process -Name qdrant -Force` |
| **查所有 collection** | `curl.exe http://localhost:6333/collections` |
| **查 collection** | `curl.exe http://localhost:6333/collections/agent-rag` |
| **删除 collection** | `curl.exe -X DELETE http://localhost:6333/collections/agent-rag` |
| **重建 collection** | 见 4.4 节 |
| **查 points** | 见 4.5 节 |
| **一键重置** | 跑 `reset-qdrant.bat` 或 5.1 节的 PowerShell |
| **搜数据目录** | `Get-ChildItem -Path D:\ -Filter "agent-rag" -Recurse -Directory -ErrorAction SilentlyContinue` |

---

## 八、目录结构

```
D:\zhangzhao\qdrant\
├── qdrant.exe                       ← 程序
├── start-qdrant.bat                 ← 启动脚本
├── reset-qdrant.bat                 ← 重置脚本
├── config\
│   └── config.yaml                  ← 配置文件
├── storage\                         ← 数据存储
│   └── collections\
│       └── agent-rag\
│           ├── 0\
│           │   ├── segments\        ← 向量数据
│           │   └── wal\             ← 预写日志
│           └── config.json          ← 集合配置
├── snapshots\                       ← 快照备份
│   └── agent-rag\
└── .qdrant-initialized              ← 启动标记
```

---

## 九、关键注意事项

1. **PowerShell 里必须用 `curl.exe`**，不是 `curl`（`curl` 是 `Invoke-WebRequest` 的别名）
2. **JSON 参数转义**：PowerShell 里用 `-d '{\"key\":\"value\"}'`（单引号包裹 + 双引号转义）
3. **工作目录**：用 bat 脚本 + `cd /d` 强制切换，避免数据生成到桌面
4. **维度必须一致**：Collection 的 `size` 必须和 Embedding 模型输出维度一致（`text-embedding-v4` 配 1024）
5. **API 删除不彻底**：删 collection 后如果重建失败，手动删磁盘目录

---

## 十、一键验证脚本

**保存为 `D:\zhangzhao\qdrant\check-qdrant.ps1`**：

```powershell
Write-Host "========== Qdrant 状态检查 ==========" -ForegroundColor Cyan

Write-Host "`n[进程]" -ForegroundColor Yellow
Get-Process -Name qdrant -ErrorAction SilentlyContinue | Select-Object Name, Id

Write-Host "`n[端口]" -ForegroundColor Yellow
netstat -ano | findstr "6333"
netstat -ano | findstr "6334"

Write-Host "`n[Collections]" -ForegroundColor Yellow
curl.exe -s http://localhost:6333/collections

Write-Host "`n[agent-rag Collection]" -ForegroundColor Yellow
curl.exe -s http://localhost:6333/collections/agent-rag

Write-Host "`n[目录位置]" -ForegroundColor Yellow
Write-Host "  storage:   $(Test-Path 'D:\zhangzhao\qdrant\storage')"
Write-Host "  snapshots: $(Test-Path 'D:\zhangzhao\qdrant\snapshots')"
Write-Host "  桌面 storage (应为 False):   $(Test-Path "$env:USERPROFILE\Desktop\storage")"
Write-Host "  桌面 snapshots (应为 False): $(Test-Path "$env:USERPROFILE\Desktop\snapshots")"

Write-Host "`n========== 检查完成 ==========" -ForegroundColor Cyan
```

**执行**：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\zhangzhao\qdrant\check-qdrant.ps1"
```