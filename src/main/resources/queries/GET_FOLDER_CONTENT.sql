-- Binds: ?1 = tags JSON array (e.g. '[{"group":"GENRE","label":"Action"}]' or '[]')
--        ?2 = folder position
--
-- - filter_tags CTE unpacks the JSON array into rows via json_each.
-- - filter_groups CTE counts how many tags were selected per category.
-- - NOT EXISTS checks every filter category: the matched tag count must be
--     >= 1 in OR mode, or = tag_count in AND mode (folders.filter_type).
-- - When no tags are passed, filter_groups is empty, NOT EXISTS is
--   vacuously true, and all media in the folder are returned.
WITH filter_tags AS (SELECT json_extract(value, '$.group') AS category,
                            json_extract(value, '$.label') AS name
                     FROM json_each(?1)),
     filter_groups AS (SELECT category, COUNT(*) AS tag_count
                       FROM filter_tags
                       GROUP BY category)
SELECT media.type as t,
       media.path,
       media.title,
       media.posters,
       media.year,
       media.runtime,
       media.file,
       folders.name AS folder_name,
       folders.path AS folder_path
FROM media
         JOIN folders ON media.folder = folders.name
WHERE folders.position = ?2
  AND NOT EXISTS (SELECT 1
                  FROM filter_groups fg
                  WHERE (SELECT COUNT(DISTINCT tags.name)
                         FROM tags
                                  JOIN filter_tags ft ON tags.name = ft.name AND tags.category = ft.category
                         WHERE tags.path = media.path
                           AND tags.category = fg.category) < CASE WHEN folders.filter_type = 'OR' THEN 1 ELSE fg.tag_count END)
ORDER BY CASE
             WHEN folders.sort_type = 'TITLE_DSC' THEN media.title
             WHEN folders.sort_type = 'YEAR_DSC' THEN media.year
             END DESC,
         CASE
             WHEN folders.sort_type = 'TITLE_ASC' THEN media.title
             WHEN folders.sort_type = 'YEAR_ASC' THEN media.year
             ELSE media.path
             END;
