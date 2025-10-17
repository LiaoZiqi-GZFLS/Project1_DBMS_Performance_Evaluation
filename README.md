# Project1_DBMS_Performance_Evaluation
Project1_DBMS_Performance_Evaluation

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

**宿主机访问OpenGuss：**

OpenGuss新建用户：`omm=# create user test identified by "1234@Test";`