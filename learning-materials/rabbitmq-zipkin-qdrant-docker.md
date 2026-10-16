# RabbitMQ（带管理界面）
docker run -d --name fap-rabbitmq \
-p 5672:5672 -p 15672:15672 \
-e RABBITMQ_DEFAULT_USER=fap \
-e RABBITMQ_DEFAULT_PASS=fap123456 \
-e RABBITMQ_DEFAULT_VHOST=/fap \
rabbitmq:3.13-management

# Zipkin
# ① 停 + 删旧容器
docker stop fap-zipkin && docker rm fap-zipkin

# ② 用新参数启动
docker run -d --name fap-zipkin \
-p 9411:9411 \
-e ZIPKIN_UI_BASEPATH=/zipkin \
openzipkin/zipkin:latest

# ③ 确认起来了
docker ps --filter name=fap-zipkin


访问：

RabbitMQ 管理台：http://localhost:15672 （fap / fap123456）

Zipkin UI：http://localhost:9411



docker stop qdrant-server
docker rm qdrant-server

docker run -d \
--name qdrant-server \
-p 6333:6333 \
-p 6334:6334 \
-v /opt/qdrant_data:/qdrant/storage \
-e QDRANT__SERVICE__API_KEY='Hfcx@123456' \
qdrant/qdrant:v1.7.4

QDRANT__SERVICE__API_KEY 是密码