WITH owned AS (
 SELECT c.oid,n.nspname AS schema_name,c.relname AS table_name,obj_description(c.oid,'pg_class') AS description
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE c.relkind='r' AND n.nspname IN ('identity','classification','catalog','metadata','standard','integration','delivery','apidocs','audit','analytics')
), columns AS (
 SELECT o.schema_name,o.table_name,a.attnum AS ordinal,a.attname AS name,
 format_type(a.atttypid,a.atttypmod) AS data_type,a.attnotnull AS not_null,
 pg_get_expr(d.adbin,d.adrelid) AS default_value,col_description(a.attrelid,a.attnum) AS description
 FROM owned o JOIN pg_attribute a ON a.attrelid=o.oid
 LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
 WHERE a.attnum>0 AND NOT a.attisdropped
), keys AS (
 SELECT o.schema_name,o.table_name,k.conname AS name,k.contype AS kind,
 (SELECT jsonb_agg(a.attname ORDER BY u.ordinality) FROM unnest(k.conkey) WITH ORDINALITY u(attnum,ordinality) JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=u.attnum) AS columns,
 pg_get_constraintdef(k.oid) AS definition
 FROM owned o JOIN pg_constraint k ON k.conrelid=o.oid WHERE k.contype IN ('p','u','c')
), fks AS (
 SELECT k.conname AS name,o.schema_name AS child_schema,o.table_name AS child_table,
 pn.nspname AS parent_schema,pc.relname AS parent_table,
 (SELECT jsonb_agg(a.attname ORDER BY u.ordinality) FROM unnest(k.conkey) WITH ORDINALITY u(attnum,ordinality) JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=u.attnum) AS child_columns,
 (SELECT jsonb_agg(a.attname ORDER BY u.ordinality) FROM unnest(k.confkey) WITH ORDINALITY u(attnum,ordinality) JOIN pg_attribute a ON a.attrelid=k.confrelid AND a.attnum=u.attnum) AS parent_columns,
 (SELECT bool_and(a.attnotnull) FROM unnest(k.conkey) u(attnum) JOIN pg_attribute a ON a.attrelid=k.conrelid AND a.attnum=u.attnum) AS required_parent,
 EXISTS(SELECT 1 FROM pg_index ix WHERE ix.indrelid=k.conrelid AND ix.indisunique AND ix.indpred IS NULL AND ix.indexprs IS NULL AND (ix.indkey::smallint[])[0:ix.indnkeyatts-1] <@ k.conkey) AS child_unique,
 k.confdeltype AS on_delete,k.confupdtype AS on_update,pg_get_constraintdef(k.oid) AS definition
 FROM owned o JOIN pg_constraint k ON k.conrelid=o.oid AND k.contype='f'
 JOIN pg_class pc ON pc.oid=k.confrelid JOIN pg_namespace pn ON pn.oid=pc.relnamespace
), indexes AS (
 SELECT o.schema_name,o.table_name,ic.relname AS name,i.indisunique AS is_unique,i.indisprimary AS is_primary,
 pg_get_indexdef(i.indexrelid) AS definition,pg_get_expr(i.indpred,i.indrelid) AS predicate
 FROM owned o JOIN pg_index i ON i.indrelid=o.oid JOIN pg_class ic ON ic.oid=i.indexrelid
)
SELECT jsonb_pretty(jsonb_build_object(
 'version',(SELECT max(version::int) FROM flyway_schema_history WHERE success),'migration_count',(SELECT count(*) FROM flyway_schema_history WHERE success),
 'tables',(SELECT jsonb_agg(to_jsonb(o)-'oid' ORDER BY schema_name,table_name) FROM owned o),
 'columns',(SELECT jsonb_agg(to_jsonb(c) ORDER BY schema_name,table_name,ordinal) FROM columns c),
 'keys',(SELECT jsonb_agg(to_jsonb(k) ORDER BY schema_name,table_name,kind,name) FROM keys k),
 'foreign_keys',(SELECT jsonb_agg(to_jsonb(f) ORDER BY child_schema,child_table,name) FROM fks f),
 'indexes',(SELECT jsonb_agg(to_jsonb(i) ORDER BY schema_name,table_name,name) FROM indexes i)
));
