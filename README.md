# Project1_DBMS_Performance_Evaluation
Project1_DBMS_Performance_Evaluation

## Docker部署
```bash

docker network create db-test-net

```

```bash

docker run -d --name postgresql-test `
  --network db-test-net `
  -e POSTGRES_USER=test `
  -e POSTGRES_PASSWORD=123456 `
  -e POSTGRES_DB=films `
  -p 5430:5432 `
  postgres:latest
  
```

```bash

docker run -d --name opengauss-test `
  --network db-test-net `
  -e GS_PASSWORD=123456Aa@ `
  -p 5431:5432 `
  opengauss/opengauss:latest


```

> To avoid port conflicts, openGauss was configured to use port 5431 on the host while keeping its internal port as 5432. PostgreSQL was set on port 5430. Both were connected via JDBC from the Java client for fair comparison.

**宿主机访问Postgres：** `psql -U test -p 5430 postgres`

**宿主机访问OpenGuss：** `gsql -d postgres -U gaussdb -p 5431`

OpenGuss新建用户：`omm=# create user test identified by "1234@Test";`

## JDBC部署问题
+ open Gauss的Jdbc驱动提供了两个jar包
+ 其中一个是postgres的某个老版本jar包
+ 它的模块名和postgres的新版本驱动jar包的模块名一模一样
+ Java的类识别器无法区分
+ `Class.forName("org.postgresql.Driver");`
+ 所幸删掉旧版本jar包引用后两个数据库都可以正确连接

## SQL数据库导入问题
+ open Gauss的数据库自动将空字符串转换为NULL
+ 由于people和title有not null约束
+ 所以开始SQL导入失败
+ 后来将空字符串全部转换为单空格字符串
+ 成功导入