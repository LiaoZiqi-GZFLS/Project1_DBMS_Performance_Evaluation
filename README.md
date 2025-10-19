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

## ✅ 那么我们先明确应该选择哪些测试指标 + 哪些地方可以加入文件对比：
| 测试维度  | PostgreSQL vs openGauss     | Java 文件读取是否可加入？           | 可行性                      | 备注                           |
|-------|-----------------------------|---------------------------|--------------------------|------------------------------|
| 1. SELECT 查询性能 | ✅                           | ✅                         | ✔ 已实现                    | 比较 SQL 查询 vs. 遍历 movies.txt  |
| 2. UPDATE 性能（事务+回滚）| ✅                           | ✅                         | ✔ 已实现                    | 文件部分等价为字符串替换                 |
| 3. 多线程 SELECT（并发查询/QPS）| ✅                           | ⚠ 可选                      | ✔ 高价值                    | 文件无法并发高效检索，但可模拟（多线程读取同一文件）   |
| 4. 多线程 UPDATE/热点行竞争| ✅	                          | ❌                         | ✔	| 文件无法模拟事务与锁                   |
| 5. 批量插入（INSERT vs 批量写文件）| ✅                           | ✅                         | ✔ 推荐                     | 可比较 SQL 批量 insert 和 文件append |
| 6. DELETE/INSERT + 事务回滚| ✅                           | ❌                         | ✔                        | 文件无法恢复原数据结构                  |
| 7. 分析型复杂查询（EXPLAIN + 全表扫描 vs 文件搜索）| ✅                           | ✅                         | ✔ 推荐	                    | SQL执行时间 vs Java遍历文件过滤排序      |
| 8. 并发锁冲突/死锁| ✅| ❌| ✔	                       | 文件无锁机制，无法比较                  |
| 9. TPC-H/OLAP复杂查询| ✅| ❌	| ⚠复杂	| 文件难以做到 join/group by         |