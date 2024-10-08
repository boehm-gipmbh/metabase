UPDATE cache_config
   SET config = json_object(
           'multiplier', coalesce((select cast(ceil(IF(CAST(`value` AS DECIMAL(20, 1)) >= 2147483648, 2147483647.0,
                                                       CAST(`value` AS DECIMAL(20, 1)))) as unsigned) from setting where `key` = 'query-caching-ttl-ratio'), 10),
           'min_duration_ms', coalesce((select cast(ceil(IF(CAST(`value` AS DECIMAL(20, 1)) >= 2147483648, 2147483647.0,
                                                            CAST(`value` AS DECIMAL(20, 1)))) as unsigned) from setting where `key` = 'query-caching-min-ttl'), 60000)
         )
 WHERE model = 'root' AND
       model_id = 0 AND
       strategy = 'ttl' AND
       (json_extract(config, '$.multiplier') = 0 OR
        json_extract(config, '$.min_duration_ms') = 0);
