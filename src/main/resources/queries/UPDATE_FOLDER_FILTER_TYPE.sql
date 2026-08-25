UPDATE folders
SET filter_type = CASE WHEN filter_type = 'OR' THEN 'AND' ELSE 'OR' END
WHERE position = ?
