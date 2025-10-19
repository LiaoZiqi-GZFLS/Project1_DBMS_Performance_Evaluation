WITH cols AS (
    SELECT
        t.oid          AS tbl_oid,
        t.relname      AS table_name,
        a.attname      AS column_name,
        a.attnum       AS position,
        format_type(a.atttypid, a.atttypmod) AS data_type,
        CASE WHEN a.attnotnull THEN 'YES' ELSE 'NO' END AS not_null,
        pg_get_expr(d.adbin, d.adrelid) AS default_val
    FROM pg_class      t
             JOIN pg_namespace   n ON n.oid = t.relnamespace
             JOIN pg_attribute   a ON a.attrelid = t.oid
             LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
    WHERE t.relkind = 'r'
      AND n.nspname NOT IN ('pg_catalog','information_schema')
      AND a.attnum > 0
      AND NOT a.attisdropped
),
     cons AS (
         SELECT
             oid            AS con_oid,        -- 真正的约束OID
             conrelid       AS tbl_oid,
             conname        AS constraint_name,
             contype,
             CASE contype
                 WHEN 'p' THEN 'PRIMARY KEY'
                 WHEN 'u' THEN 'UNIQUE'
                 WHEN 'f' THEN 'FOREIGN KEY'
                 WHEN 'c' THEN 'CHECK'
                 END AS constraint_type,
             conkey,                           -- smallint[]
             pg_get_constraintdef(oid, true) AS definition
         FROM pg_constraint
     )
SELECT
    c.table_name,
    c.column_name,
    c.position,
    c.data_type,
    c.not_null,
    c.default_val,
    string_agg(
            k.constraint_name || ':' || k.constraint_type ||
            CASE WHEN k.definition IS NOT NULL THEN '('||k.definition||')' ELSE '' END,
            '; ' ORDER BY k.constraint_name
    ) AS constraints
FROM cols c
         LEFT JOIN cons k
                   ON k.tbl_oid = c.tbl_oid
                       AND (k.contype IN ('p','u','c')          -- 主键/唯一/检查 对整个表生效
                           OR
                            (k.contype = 'f' AND               -- 外键：判断列是否在 conkey 数组里
                             c.position = ANY(k.conkey)))
GROUP BY c.table_name, c.column_name, c.position, c.data_type, c.not_null, c.default_val
ORDER BY c.table_name, c.position;