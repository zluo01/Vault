SELECT folder,
       COUNT(*)             AS items,
       COUNT(DISTINCT type) AS types
FROM media
GROUP BY folder;
