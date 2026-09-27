# PowerShell执行

# 停 Qdrant
Stop-Process -Name qdrant -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# 删 collection 目录
Remove-Item -Recurse -Force "D:\DevelopmentTool\qdrant\storage\collections\agent-rag" -ErrorAction SilentlyContinue

# 重启 Qdrant
Start-Process -FilePath "D:\DevelopmentTool\qdrant\qdrant.exe" `
    -ArgumentList "--config-path", "D:\DevelopmentTool\qdrant\config\config.yaml" `
-WorkingDirectory "D:\DevelopmentTool\qdrant"
Start-Sleep -Seconds 3

# 重建 collection
curl.exe -X PUT "http://localhost:6333/collections/agent-rag" -H "Content-Type: application/json" -d '{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}'
